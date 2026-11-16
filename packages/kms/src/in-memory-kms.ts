import {
  createCipheriv,
  createDecipheriv,
  generateKeyPairSync,
  hkdfSync,
  randomBytes,
  sign as signData,
  type KeyObject,
} from 'node:crypto';
import { KmsError } from './errors.js';
import {
  ciphertextKeyVersion,
  type KeyInfo,
  type KeyVersionInfo,
  type Kms,
  type RewrapItem,
  type SignResult,
} from './kms.js';

export type InMemoryKeyType = 'aes256-gcm96' | 'ed25519';

export interface InMemoryKeyOptions {
  readonly type?: InMemoryKeyType;
  readonly derived?: boolean;
}

export interface InMemoryKmsOptions {
  readonly now?: () => Date;
  /**
   * Mirrors the two vault policies: the API may use keys but not rotate them or
   * raise `min_decryption_version`; the worker may do both.
   */
  readonly allowKeyManagement?: boolean;
  /** Share the keys of another instance: the same vault seen through a different policy. */
  readonly sharedWith?: InMemoryKms;
}

interface VersionEntry {
  readonly version: number;
  readonly createdAt: Date;
  /** Symmetric keys. */
  readonly secret: Buffer | null;
  /** Signing keys. */
  readonly privateKey: KeyObject | null;
  readonly publicKey: Buffer | null;
}

interface KeyEntry {
  readonly name: string;
  readonly type: InMemoryKeyType;
  readonly derived: boolean;
  readonly versions: Map<number, VersionEntry>;
  latestVersion: number;
  minDecryptionVersion: number;
}

const NONCE_BYTES = 12;
const TAG_BYTES = 16;

/**
 * A `Kms` that lives in process memory, for tests. It keeps the semantics of
 * OpenBao Transit that the system relies on:
 *
 * - ciphertext is `vault:v<N>:<base64>` and names the version it was made with;
 * - derived keys need a context, and the wrong context fails to decrypt;
 * - `rewrap` moves ciphertext to the newest version without exposing plaintext;
 * - versions below `minDecryptionVersion` are refused;
 * - rotation and configuration can be denied, like the API's policy.
 */
export class InMemoryKms implements Kms {
  private readonly keys: Map<string, KeyEntry>;
  private readonly now: () => Date;
  private readonly allowKeyManagement: boolean;
  private available = true;

  public constructor(options: InMemoryKmsOptions = {}) {
    this.keys = options.sharedWith?.keys ?? new Map<string, KeyEntry>();
    this.now = options.now ?? options.sharedWith?.now ?? ((): Date => new Date());
    this.allowKeyManagement = options.allowKeyManagement ?? true;
  }

  /** Test set-up: what the bootstrap script does against the real vault. */
  public createKey(name: string, options: InMemoryKeyOptions = {}): void {
    if (this.keys.has(name)) {
      return;
    }
    const entry: KeyEntry = {
      name,
      type: options.type ?? 'aes256-gcm96',
      derived: options.derived ?? false,
      versions: new Map(),
      latestVersion: 0,
      minDecryptionVersion: 1,
    };
    this.keys.set(name, entry);
    this.addVersion(entry);
  }

  /** Simulates an outage (sealed or unreachable vault). */
  public setAvailable(available: boolean): void {
    this.available = available;
  }

  /** A second handle on the same keys with different permissions (API vs worker). */
  public withPolicy(options: { allowKeyManagement: boolean }): InMemoryKms {
    return new InMemoryKms({ sharedWith: this, allowKeyManagement: options.allowKeyManagement });
  }

  public encrypt(keyName: string, plaintext: Uint8Array, context?: string): Promise<string> {
    return this.run(() => {
      const entry = this.symmetricKey(keyName);
      return this.seal(entry, entry.latestVersion, plaintext, context);
    });
  }

  public decrypt(keyName: string, ciphertext: string, context?: string): Promise<Buffer> {
    return this.run(() => this.unseal(this.symmetricKey(keyName), ciphertext, context));
  }

  public rewrap(keyName: string, items: readonly RewrapItem[]): Promise<string[]> {
    return this.run(() => {
      const entry = this.symmetricKey(keyName);
      return items.map((item) => {
        const plaintext = this.unseal(entry, item.ciphertext, item.context);
        try {
          return this.seal(entry, entry.latestVersion, plaintext, item.context);
        } finally {
          plaintext.fill(0);
        }
      });
    });
  }

  public sign(keyName: string, data: Uint8Array, keyVersion?: number): Promise<SignResult> {
    return this.run(() => {
      const entry = this.key(keyName);
      if (entry.type !== 'ed25519') {
        throw new KmsError('REJECTED', 'Key does not support signing.');
      }
      const version = keyVersion ?? entry.latestVersion;
      const versionEntry = entry.versions.get(version);
      if (
        versionEntry === undefined ||
        versionEntry.privateKey === null ||
        version < entry.minDecryptionVersion
      ) {
        throw new KmsError('REJECTED', 'Signing key version is not available.');
      }
      return { signature: signData(null, data, versionEntry.privateKey), keyVersion: version };
    });
  }

  public getKeyInfo(keyName: string): Promise<KeyInfo> {
    return this.run(() => this.describe(this.key(keyName)));
  }

  public rotateKey(keyName: string): Promise<KeyInfo> {
    return this.run(() => {
      this.assertManagement();
      const entry = this.key(keyName);
      this.addVersion(entry);
      return this.describe(entry);
    });
  }

  public setMinDecryptionVersion(keyName: string, version: number): Promise<void> {
    return this.run(() => {
      this.assertManagement();
      const entry = this.key(keyName);
      if (!Number.isInteger(version) || version < 1 || version > entry.latestVersion) {
        throw new KmsError('REJECTED', 'min_decryption_version is out of range.');
      }
      entry.minDecryptionVersion = version;
    });
  }

  public health(): Promise<void> {
    return this.run(() => undefined);
  }

  public close(): Promise<void> {
    return Promise.resolve();
  }

  private run<Result>(operation: () => Result): Promise<Result> {
    if (!this.available) {
      return Promise.reject(new KmsError('UNAVAILABLE', 'Key vault is unavailable.'));
    }
    try {
      return Promise.resolve(operation());
    } catch (error) {
      return Promise.reject(error instanceof Error ? error : new Error(String(error)));
    }
  }

  private assertManagement(): void {
    if (!this.allowKeyManagement) {
      throw new KmsError('PERMISSION_DENIED', 'Policy does not allow key management.');
    }
  }

  private key(name: string): KeyEntry {
    const entry = this.keys.get(name);
    if (entry === undefined) {
      throw new KmsError('NOT_FOUND', 'No such key.');
    }
    return entry;
  }

  private symmetricKey(name: string): KeyEntry {
    const entry = this.key(name);
    if (entry.type !== 'aes256-gcm96') {
      throw new KmsError('REJECTED', 'Key does not support encryption.');
    }
    return entry;
  }

  private addVersion(entry: KeyEntry): void {
    const version = entry.latestVersion + 1;
    if (entry.type === 'ed25519') {
      const pair = generateKeyPairSync('ed25519');
      const jwk = pair.publicKey.export({ format: 'jwk' });
      entry.versions.set(version, {
        version,
        createdAt: this.now(),
        secret: null,
        privateKey: pair.privateKey,
        publicKey: Buffer.from(jwk.x ?? '', 'base64url'),
      });
    } else {
      entry.versions.set(version, {
        version,
        createdAt: this.now(),
        secret: randomBytes(32),
        privateKey: null,
        publicKey: null,
      });
    }
    entry.latestVersion = version;
  }

  private describe(entry: KeyEntry): KeyInfo {
    const versions: KeyVersionInfo[] = [...entry.versions.values()]
      .filter((version) => version.version >= entry.minDecryptionVersion)
      .map((version) => ({
        version: version.version,
        createdAt: version.createdAt,
        publicKey: version.publicKey,
      }));
    return {
      name: entry.name,
      type: entry.type,
      derived: entry.derived,
      exportable: false,
      deletionAllowed: false,
      latestVersion: entry.latestVersion,
      minDecryptionVersion: entry.minDecryptionVersion,
      versions,
    };
  }

  private operationKey(entry: KeyEntry, version: number, context: string | undefined): Buffer {
    const secret = entry.versions.get(version)?.secret ?? null;
    if (secret === null) {
      throw new KmsError('REJECTED', 'Key version is not available.');
    }
    if (!entry.derived) {
      return Buffer.from(secret);
    }
    if (context === undefined || context.length === 0) {
      throw new KmsError('REJECTED', 'A derived key needs a context.');
    }
    return Buffer.from(
      hkdfSync('sha256', secret, Buffer.alloc(0), Buffer.from(context, 'utf8'), 32),
    );
  }

  private seal(
    entry: KeyEntry,
    version: number,
    plaintext: Uint8Array,
    context: string | undefined,
  ): string {
    const key = this.operationKey(entry, version, context);
    try {
      const nonce = randomBytes(NONCE_BYTES);
      const cipher = createCipheriv('aes-256-gcm', key, nonce, { authTagLength: TAG_BYTES });
      const body = Buffer.concat([
        nonce,
        cipher.update(plaintext),
        cipher.final(),
        cipher.getAuthTag(),
      ]);
      return `vault:v${version}:${body.toString('base64')}`;
    } finally {
      key.fill(0);
    }
  }

  private unseal(entry: KeyEntry, ciphertext: string, context: string | undefined): Buffer {
    const version = ciphertextKeyVersion(ciphertext);
    if (version < entry.minDecryptionVersion) {
      throw new KmsError('REJECTED', 'Ciphertext version is disallowed by policy (too old).');
    }
    const body = Buffer.from(ciphertext.slice(ciphertext.lastIndexOf(':') + 1), 'base64');
    if (body.length < NONCE_BYTES + TAG_BYTES) {
      throw new KmsError('REJECTED', 'Ciphertext is malformed.');
    }
    const key = this.operationKey(entry, version, context);
    try {
      // Fixed tag length, as in @scanova/crypto: GCM must never accept a shortened tag.
      const decipher = createDecipheriv('aes-256-gcm', key, body.subarray(0, NONCE_BYTES), {
        authTagLength: TAG_BYTES,
      });
      decipher.setAuthTag(body.subarray(body.length - TAG_BYTES));
      const plaintext = decipher.update(body.subarray(NONCE_BYTES, body.length - TAG_BYTES));
      try {
        decipher.final();
      } catch {
        plaintext.fill(0);
        throw new KmsError('REJECTED', 'Decryption failed.');
      }
      return plaintext;
    } finally {
      key.fill(0);
    }
  }
}
