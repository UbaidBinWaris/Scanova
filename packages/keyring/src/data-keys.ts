import { CryptoError } from '@scanova/crypto/errors';
import type { DataKeyScope } from '@scanova/crypto/hierarchy';
import { unwrapDataKey, wrapDataKey } from '@scanova/crypto/key-wrap';
import { randomKey } from '@scanova/crypto/random';
import { zeroize } from '@scanova/crypto/zeroize';
import { Prisma, type KeystoreClient } from '@scanova/keystore/client';
import { KeyringError } from './errors.js';
import type { SecretCache } from './secret-cache.js';
import type { UserKeys } from './user-keys.js';

const dataKeyCacheId = (scope: DataKeyScope, userId: string): string =>
  `user:${userId}:data:${scope}`;
const MAX_ATTEMPTS = 3;

interface DataKeyRow {
  readonly id: number;
  readonly refId: string;
  readonly wrappedKey: Uint8Array;
  readonly userKeyVersion: number;
}

/**
 * Data keys (§5.2): 32 random bytes each, stored only wrapped under the owner's
 * key-wrap key. One PROFILE and one INDEX key per user, one DOCUMENT key per
 * document.
 */
export class DataKeys {
  public constructor(
    private readonly keystore: KeystoreClient,
    private readonly userKeys: UserKeys,
    private readonly cache: SecretCache,
  ) {}

  /**
   * Makes sure a DOCUMENT key exists for `(userId, documentId)` and marks it as
   * claimed. Idempotent: a retried request gets the same key, so two attempts to
   * create the same document encrypt under one key. Call it before writing the
   * row the key protects.
   */
  public async ensureDocumentKey(userId: string, documentId: string): Promise<void> {
    for (let attempt = 1; ; attempt += 1) {
      // Unwrapping the user key is a vault call, so it happens before the transaction.
      const version = await this.userKeys.activeVersion(userId);
      const keyWrapKey = await this.userKeys.wrapKey(userId, version);
      const dataKey = randomKey();
      try {
        const done = await this.keystore.$transaction(async (tx) => {
          // Shared lock on the active user-key row: a rotation cannot retire (and
          // later destroy) this version until the new data key is committed, so a
          // data key can never end up wrapped under a destroyed version.
          const locked = await tx.$queryRaw<{ version: number }[]>`
            SELECT version FROM user_keys
            WHERE user_id = ${userId}::uuid AND state = 'ACTIVE'::user_key_state
            FOR SHARE`;
          if (locked.length !== 1 || locked[0]?.version !== version) {
            return false;
          }
          const claimed = await tx.dataKey.updateMany({
            where: { scope: 'DOCUMENT', userId, refId: documentId },
            data: { claimedAt: new Date() },
          });
          if (claimed.count === 0) {
            await tx.dataKey.create({
              data: {
                scope: 'DOCUMENT',
                userId,
                refId: documentId,
                userKeyVersion: version,
                wrappedKey: wrapDataKey(keyWrapKey, dataKey, {
                  scope: 'DOCUMENT',
                  userId,
                  refId: documentId,
                  userKeyVersion: version,
                }),
              },
            });
          }
          return true;
        });
        if (done) {
          return;
        }
      } catch (error) {
        // A concurrent request created the same key: the next attempt claims it.
        const duplicate =
          error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002';
        if (!duplicate || attempt >= MAX_ATTEMPTS) {
          throw error;
        }
      } finally {
        zeroize(dataKey, keyWrapKey);
      }
      if (attempt >= MAX_ATTEMPTS) {
        throw new KeyringError(
          'INVALID_STATE',
          'The user key changed repeatedly while creating a data key.',
        );
      }
    }
  }

  /** Runs `use` with a user's PROFILE or INDEX key. The key is wiped afterwards. */
  public async usePerUserKey<Result>(
    scope: 'PROFILE' | 'INDEX',
    userId: string,
    use: (key: Buffer) => Result | Promise<Result>,
  ): Promise<Result> {
    const cacheId = dataKeyCacheId(scope, userId);
    let key = this.cache.get(cacheId);
    if (key === null) {
      const keys = await this.unwrap(scope, userId, [userId]);
      key = keys.get(userId) ?? null;
      if (key === null) {
        throw new KeyringError('KEY_NOT_FOUND', `This user has no ${scope} key.`);
      }
      this.cache.set(cacheId, key);
    }
    try {
      return await use(key);
    } finally {
      zeroize(key);
    }
  }

  /**
   * Runs `use` with the DOCUMENT keys of several documents (one query, no vault
   * call when the user key is cached). Documents without a key are simply absent
   * from the map. Every key is wiped afterwards.
   */
  public async useDocumentKeys<Result>(
    userId: string,
    documentIds: readonly string[],
    use: (keys: ReadonlyMap<string, Buffer>) => Result | Promise<Result>,
  ): Promise<Result> {
    const keys = await this.unwrap('DOCUMENT', userId, documentIds);
    try {
      return await use(keys);
    } finally {
      zeroize(...keys.values());
    }
  }

  /** Destroys DOCUMENT keys. After this the documents are unrecoverable. Idempotent. */
  public async destroyDocumentKeys(
    userId: string,
    documentIds: readonly string[],
  ): Promise<number> {
    if (documentIds.length === 0) {
      return 0;
    }
    const result = await this.keystore.dataKey.deleteMany({
      where: { scope: 'DOCUMENT', userId, refId: { in: [...documentIds] } },
    });
    return result.count;
  }

  private async unwrap(
    scope: DataKeyScope,
    userId: string,
    refIds: readonly string[],
  ): Promise<Map<string, Buffer>> {
    if (refIds.length === 0) {
      return new Map();
    }
    for (let attempt = 1; ; attempt += 1) {
      const rows: DataKeyRow[] = await this.keystore.dataKey.findMany({
        where: { scope, userId, refId: { in: [...refIds] } },
        select: { id: true, refId: true, wrappedKey: true, userKeyVersion: true },
      });
      const unwrapped = new Map<string, Buffer>();
      const wrapKeys = new Map<number, Buffer>();
      try {
        for (const row of rows) {
          let keyWrapKey = wrapKeys.get(row.userKeyVersion);
          if (keyWrapKey === undefined) {
            keyWrapKey = await this.userKeys.wrapKey(userId, row.userKeyVersion);
            wrapKeys.set(row.userKeyVersion, keyWrapKey);
          }
          unwrapped.set(
            row.refId,
            unwrapDataKey(keyWrapKey, row.wrappedKey, {
              scope,
              userId,
              refId: row.refId,
              userKeyVersion: row.userKeyVersion,
            }),
          );
        }
        return unwrapped;
      } catch (error) {
        zeroize(...unwrapped.values());
        // A rotation may have re-wrapped a row between our read and the unwrap,
        // and destroyed the version we read. Reading again sees the new version.
        const raced =
          (error instanceof KeyringError && error.code === 'KEY_DESTROYED') ||
          (error instanceof CryptoError && error.code === 'AUTHENTICATION_FAILED');
        if (!raced || attempt >= MAX_ATTEMPTS) {
          throw error;
        }
      } finally {
        zeroize(...wrapKeys.values());
      }
    }
  }
}
