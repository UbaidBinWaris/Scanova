import { randomKey } from '@scanova/crypto/random';
import { zeroize } from '@scanova/crypto/zeroize';
import { Prisma, type KeystoreClient } from '@scanova/keystore/client';
import { ciphertextKeyVersion, type Kms } from '@scanova/kms/kms';
import { KeyringError } from './errors.js';
import type { SecretCache } from './secret-cache.js';
import type { SystemKeyPurpose } from './types.js';

const cacheId = (purpose: SystemKeyPurpose): string => `system:${purpose}`;

/**
 * System data keys: not owned by any user, wrapped by the system master key.
 * EMAIL_INDEX is the HMAC key of the e-mail lookup index; AUDIT encrypts audit
 * payloads, which must outlive the users they mention.
 */
export class SystemKeys {
  public constructor(
    private readonly keystore: KeystoreClient,
    private readonly kms: Kms,
    private readonly cache: SecretCache,
    private readonly systemKeyName: string,
  ) {}

  /** Runs `use` with the active key for a purpose, creating it on first use. Wiped afterwards. */
  public async use<Result>(
    purpose: SystemKeyPurpose,
    use: (key: Buffer) => Result | Promise<Result>,
  ): Promise<Result> {
    let key = this.cache.get(cacheId(purpose));
    if (key === null) {
      key = await this.load(purpose);
      this.cache.set(cacheId(purpose), key);
    }
    try {
      return await use(key);
    } finally {
      zeroize(key);
    }
  }

  private async load(purpose: SystemKeyPurpose): Promise<Buffer> {
    const existing = await this.find(purpose);
    if (existing !== null) {
      return this.kms.decrypt(this.systemKeyName, existing);
    }
    const key = randomKey();
    try {
      const wrappedKey = await this.kms.encrypt(this.systemKeyName, key);
      await this.keystore.systemKey.create({
        data: {
          purpose,
          version: 1,
          wrappedKey,
          masterKeyName: this.systemKeyName,
          masterKeyVersion: ciphertextKeyVersion(wrappedKey),
        },
      });
      return Buffer.from(key);
    } catch (error) {
      // Another process created it first: use that one, not ours.
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        const winner = await this.find(purpose);
        if (winner !== null) {
          return await this.kms.decrypt(this.systemKeyName, winner);
        }
      }
      throw error;
    } finally {
      zeroize(key);
    }
  }

  private async find(purpose: SystemKeyPurpose): Promise<string | null> {
    const row = await this.keystore.systemKey.findFirst({
      where: { purpose, state: 'ACTIVE' },
      orderBy: { version: 'desc' },
      select: { wrappedKey: true },
    });
    if (row !== null && row.wrappedKey.length === 0) {
      throw new KeyringError('INVALID_STATE', 'System key has no key material.');
    }
    return row?.wrappedKey ?? null;
  }
}
