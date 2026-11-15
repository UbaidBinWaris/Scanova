import { KmsError } from './errors.js';

export interface KeyVersionInfo {
  readonly version: number;
  readonly createdAt: Date;
  /** Raw public key for signing keys (32 bytes for Ed25519); null for symmetric keys. */
  readonly publicKey: Buffer | null;
}

export interface KeyInfo {
  readonly name: string;
  /** Transit key type, e.g. `aes256-gcm96` or `ed25519`. */
  readonly type: string;
  /** Derived keys require a `context` on every operation (§5.2). */
  readonly derived: boolean;
  readonly exportable: boolean;
  readonly deletionAllowed: boolean;
  readonly latestVersion: number;
  /** Ciphertexts and signatures below this version are refused. */
  readonly minDecryptionVersion: number;
  readonly versions: readonly KeyVersionInfo[];
}

export interface SignResult {
  /** Raw signature bytes (64 for Ed25519). */
  readonly signature: Buffer;
  readonly keyVersion: number;
}

export interface RewrapItem {
  readonly ciphertext: string;
  readonly context?: string;
}

/**
 * The key vault as the rest of the system sees it. Master keys never leave it:
 * callers hand over small secrets to wrap and get back opaque `vault:v<N>:…`
 * strings. `context` is the key-derivation context of a derived key — for
 * user-type keys, the user's public id.
 */
export interface Kms {
  /** Wraps with the newest key version. */
  encrypt(keyName: string, plaintext: Uint8Array, context?: string): Promise<string>;
  /** Unwraps. Rejects ciphertext below `minDecryptionVersion`, a wrong context or tampering. */
  decrypt(keyName: string, ciphertext: string, context?: string): Promise<Buffer>;
  /** Re-wraps under the newest version inside the vault; the plaintext never leaves it. */
  rewrap(keyName: string, items: readonly RewrapItem[]): Promise<string[]>;
  /** Signs with the newest version, or with `keyVersion` when given. */
  sign(keyName: string, data: Uint8Array, keyVersion?: number): Promise<SignResult>;
  getKeyInfo(keyName: string): Promise<KeyInfo>;
  /** Creates a new key version. Worker policy only. */
  rotateKey(keyName: string): Promise<KeyInfo>;
  /** Stops older versions from decrypting or verifying. Worker policy only. */
  setMinDecryptionVersion(keyName: string, version: number): Promise<void>;
  /** Resolves when the vault is initialised, unsealed and reachable. */
  health(): Promise<void>;
  close(): Promise<void>;
}

const CIPHERTEXT_PATTERN = /^vault:v(\d+):[A-Za-z0-9+/]+=*$/u;

/** Key version encoded in a `vault:v<N>:<base64>` string. */
export function ciphertextKeyVersion(ciphertext: string): number {
  const match = CIPHERTEXT_PATTERN.exec(ciphertext);
  if (match === null) {
    throw new KmsError('REJECTED', 'Not a vault ciphertext.');
  }
  return Number(match[1]);
}
