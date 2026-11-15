import type { KeyNamesConfig } from '@scanova/config/sections';
import { deriveKeyWrapKey } from '@scanova/crypto/hierarchy';
import { wrapDataKey } from '@scanova/crypto/key-wrap';
import { randomKey } from '@scanova/crypto/random';
import { zeroize } from '@scanova/crypto/zeroize';
import { Prisma, type KeystoreClient, type KeystoreTransaction } from '@scanova/keystore/client';
import { ciphertextKeyVersion, type Kms } from '@scanova/kms/kms';
import { KeyringError } from './errors.js';
import type { SecretCache } from './secret-cache.js';
import type { UserKind } from './types.js';

const wrapKeyCacheId = (userId: string, version: number): string =>
  `user:${userId}:wrap:${version}`;
export const userCachePrefix = (userId: string): string => `user:${userId}:`;

/**
 * User keys (§5.2): 32 random bytes per user, versioned, stored only wrapped by
 * the Transit master key of the user's type with the user id as context.
 *
 * The raw user key never leaves this file. What the rest of the keyring gets is
 * the key-wrap key derived from it, which is what actually wraps data keys.
 */
export class UserKeys {
  /** Unwraps in flight, so concurrent requests for one user make one vault call. */
  private readonly loading = new Map<string, Promise<void>>();

  public constructor(
    private readonly keystore: KeystoreClient,
    private readonly kms: Kms,
    private readonly cache: SecretCache,
    private readonly keys: KeyNamesConfig,
  ) {}

  public masterKeyFor(userType: UserKind): string {
    return userType === 'ADMIN' ? this.keys.userTypeAdmin : this.keys.userTypeMember;
  }

  /**
   * Creates version 1 of a user's key together with their PROFILE and INDEX data
   * keys. Called before the user row exists (create the key before the data).
   */
  public async provision(userId: string, userType: UserKind): Promise<void> {
    const masterKeyName = this.masterKeyFor(userType);
    const userKey = randomKey();
    const profileKey = randomKey();
    const indexKey = randomKey();
    const keyWrapKey = deriveKeyWrapKey(userKey);
    try {
      const wrappedUserKey = await this.kms.encrypt(masterKeyName, userKey, userId);
      const version = 1;
      await this.keystore.$transaction([
        this.keystore.userKey.create({
          data: {
            userId,
            version,
            wrappedKey: wrappedUserKey,
            masterKeyName,
            masterKeyVersion: ciphertextKeyVersion(wrappedUserKey),
          },
        }),
        this.keystore.dataKey.createMany({
          data: [
            {
              scope: 'PROFILE',
              userId,
              refId: userId,
              userKeyVersion: version,
              wrappedKey: wrapDataKey(keyWrapKey, profileKey, {
                scope: 'PROFILE',
                userId,
                refId: userId,
                userKeyVersion: version,
              }),
            },
            {
              scope: 'INDEX',
              userId,
              refId: userId,
              userKeyVersion: version,
              wrappedKey: wrapDataKey(keyWrapKey, indexKey, {
                scope: 'INDEX',
                userId,
                refId: userId,
                userKeyVersion: version,
              }),
            },
          ],
        }),
      ]);
      this.cache.set(wrapKeyCacheId(userId, version), keyWrapKey);
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        throw new KeyringError('KEY_ALREADY_EXISTS', 'This user already has keys.');
      }
      throw error;
    } finally {
      zeroize(userKey, profileKey, indexKey, keyWrapKey);
    }
  }

  /** Version that wraps new data keys. */
  public async activeVersion(
    userId: string,
    client: KeystoreClient | KeystoreTransaction = this.keystore,
  ): Promise<number> {
    const active = await client.userKey.findFirst({
      where: { userId, state: 'ACTIVE' },
      orderBy: { version: 'desc' },
      select: { version: true },
    });
    if (active !== null) {
      return active.version;
    }
    const any = await client.userKey.count({ where: { userId } });
    throw any === 0
      ? new KeyringError('KEY_NOT_FOUND', 'This user has no key.')
      : new KeyringError('KEY_DESTROYED', 'The keys of this user were destroyed.');
  }

  /**
   * The key-wrap key of one user-key version: `HKDF(UK, "scanova/v1/key-wrap")`.
   * Returns a copy the caller must wipe. The first use costs one vault call;
   * after that the derived key is served from memory for at most five minutes.
   */
  public async wrapKey(userId: string, version: number): Promise<Buffer> {
    const cacheId = wrapKeyCacheId(userId, version);
    const cached = this.cache.get(cacheId);
    if (cached !== null) {
      return cached;
    }
    let pending = this.loading.get(cacheId);
    if (pending === undefined) {
      pending = this.load(userId, version).finally(() => {
        this.loading.delete(cacheId);
      });
      this.loading.set(cacheId, pending);
    }
    await pending;
    const loaded = this.cache.get(cacheId);
    if (loaded === null) {
      // Only possible if the entry was evicted in between (user destroyed): fail closed.
      throw new KeyringError('KEY_DESTROYED', 'The key of this user is no longer available.');
    }
    return loaded;
  }

  public evictVersion(userId: string, version: number): void {
    this.cache.delete(wrapKeyCacheId(userId, version));
  }

  private async load(userId: string, version: number): Promise<void> {
    const row = await this.keystore.userKey.findUnique({
      where: { userId_version: { userId, version } },
      select: { wrappedKey: true, masterKeyName: true, state: true },
    });
    if (row === null) {
      throw new KeyringError('KEY_NOT_FOUND', 'No such user key version.');
    }
    if (row.state === 'DESTROYED' || row.wrappedKey === null) {
      throw new KeyringError('KEY_DESTROYED', 'This user key version was destroyed.');
    }
    const userKey = await this.kms.decrypt(row.masterKeyName, row.wrappedKey, userId);
    const keyWrapKey = deriveKeyWrapKey(userKey);
    try {
      this.cache.set(wrapKeyCacheId(userId, version), keyWrapKey);
    } finally {
      zeroize(userKey, keyWrapKey);
    }
  }
}
