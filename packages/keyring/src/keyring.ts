import type { PostgresConfig } from '@scanova/config/sections';
import { zeroize } from '@scanova/crypto/zeroize';
import { createKeystoreClient, type KeystoreClient } from '@scanova/keystore/client';
import { ciphertextKeyVersion, type Kms } from '@scanova/kms/kms';
import { DataKeys } from './data-keys.js';
import { KeyringError } from './errors.js';
import { RotationEngine } from './rotation/engine.js';
import { isRotationDue, keyAgeDays } from './rotation-policy.js';
import { SecretCache } from './secret-cache.js';
import { SystemKeys } from './system-keys.js';
import type {
  KeyStatusReport,
  KeyringSettings,
  MasterKeyPurpose,
  MasterKeyStatus,
  RotationRunRecord,
  SystemKeyPurpose,
  UserKind,
} from './types.js';
import { UserKeys, userCachePrefix } from './user-keys.js';

const DEFAULT_MASTER_KEY_SETTLE_SECONDS = 60;
const DEFAULT_SIGNING_KEY_RETIRE_MARGIN_SECONDS = 120;
/** Attempts to move every user-key version to the new type's master key before giving up. */
const USER_TYPE_CHANGE_PASSES = 3;

/** A data key that may be an orphan, for the garbage collector. `cursor` is opaque. */
export interface DocumentKeyRef {
  readonly cursor: number;
  readonly userId: string;
  readonly documentId: string;
}

export interface KeyringDependencies {
  readonly kms: Kms;
  /** Connection to the key database. The keyring owns it and closes it. */
  readonly keystore: KeystoreClient;
  readonly settings: KeyringSettings;
}

/**
 * The key hierarchy (§5.2) and the single door to the key database.
 *
 * Callers never receive a key to keep: they pass a function that gets the key
 * for the duration of the call, and the buffer is overwritten afterwards.
 *
 * Ordering across the two databases is the caller's duty and this API is shaped
 * for it: create the key (`provisionUser`, `createDocumentKey`) before the row
 * it protects; destroy the key (`destroyUser`, `destroyDocumentKeys`) before
 * the data.
 */
export class Keyring {
  public readonly rotation: RotationEngine;

  private readonly kms: Kms;
  private readonly keystore: KeystoreClient;
  private readonly settings: KeyringSettings;
  private readonly cache: SecretCache;
  private readonly userKeys: UserKeys;
  private readonly dataKeys: DataKeys;
  private readonly systemKeys: SystemKeys;
  private readonly now: () => Date;

  public constructor(dependencies: KeyringDependencies) {
    this.kms = dependencies.kms;
    this.keystore = dependencies.keystore;
    this.settings = dependencies.settings;
    this.now = dependencies.settings.now ?? ((): Date => new Date());
    this.cache = new SecretCache(
      dependencies.settings.cacheTtlMs === undefined
        ? {}
        : { ttlMs: dependencies.settings.cacheTtlMs },
    );
    this.userKeys = new UserKeys(this.keystore, this.kms, this.cache, this.settings.keys);
    this.dataKeys = new DataKeys(this.keystore, this.userKeys, this.cache);
    this.systemKeys = new SystemKeys(
      this.keystore,
      this.kms,
      this.cache,
      this.settings.keys.system,
    );
    this.rotation = new RotationEngine(this.keystore, this.kms, this.userKeys, {
      keys: this.settings.keys,
      rotation: this.settings.rotation,
      accessTokenTtlSeconds: this.settings.accessTokenTtlSeconds,
      masterKeySettleSeconds:
        this.settings.masterKeySettleSeconds ?? DEFAULT_MASTER_KEY_SETTLE_SECONDS,
      signingKeyRetireMarginSeconds:
        this.settings.signingKeyRetireMarginSeconds ?? DEFAULT_SIGNING_KEY_RETIRE_MARGIN_SECONDS,
      now: this.now,
    });
  }

  // ── users ───────────────────────────────────────────────────────────────

  /** Creates a new user's key with their PROFILE and INDEX data keys. Do this before inserting the user. */
  public provisionUser(userId: string, userType: UserKind): Promise<void> {
    return this.userKeys.provision(userId, userType);
  }

  /**
   * Crypto-shredding: destroys every version of a user's key and all their data
   * keys. Everything of theirs becomes unrecoverable at once. Idempotent.
   */
  public async destroyUser(userId: string): Promise<void> {
    const now = this.now();
    await this.keystore.$transaction([
      // An UPDATE, not a DELETE: the runtime role cannot delete user keys.
      this.keystore.userKey.updateMany({
        where: { userId, state: { not: 'DESTROYED' } },
        data: { wrappedKey: null, state: 'DESTROYED', destroyedAt: now },
      }),
      this.keystore.dataKey.deleteMany({ where: { userId } }),
      this.keystore.keyRotationRun.updateMany({
        where: { scope: 'USER_KEY', target: userId, state: { in: ['PENDING', 'RUNNING'] } },
        data: { state: 'FAILED', errorCode: 'USER_KEYS_DESTROYED', finishedAt: now },
      }),
    ]);
    this.cache.deleteByPrefix(userCachePrefix(userId));
  }

  /**
   * Moves a user to the other user-type master key: every remaining version of
   * their user key is unwrapped under the old master key and wrapped under the
   * new one, then the user key itself is rotated (§5.4).
   */
  public async changeUserType(
    userId: string,
    newType: UserKind,
    requestedBy?: string,
  ): Promise<RotationRunRecord> {
    const newMasterKey = this.userKeys.masterKeyFor(newType);
    // A concurrent master-key re-wrap can change a row between our read and our
    // conditional update; such a row is read again and moved on the next pass.
    for (let pass = 0; pass < USER_TYPE_CHANGE_PASSES; pass += 1) {
      const pending = await this.keystore.userKey.findMany({
        where: {
          userId,
          state: { not: 'DESTROYED' },
          wrappedKey: { not: null },
          masterKeyName: { not: newMasterKey },
        },
        orderBy: { version: 'asc' },
      });
      if (pending.length === 0) {
        return this.rotation.beginUserKeyRotation(userId, 'USER_TYPE_CHANGE', requestedBy);
      }
      for (const row of pending) {
        if (row.wrappedKey === null) {
          continue;
        }
        const userKey = await this.kms.decrypt(row.masterKeyName, row.wrappedKey, userId);
        try {
          const wrappedKey = await this.kms.encrypt(newMasterKey, userKey, userId);
          await this.keystore.userKey.updateMany({
            where: { id: row.id, wrappedKey: row.wrappedKey },
            data: {
              wrappedKey,
              masterKeyName: newMasterKey,
              masterKeyVersion: ciphertextKeyVersion(wrappedKey),
            },
          });
        } finally {
          zeroize(userKey);
        }
      }
    }
    // Never leave a user's keys under the other type's master key silently.
    throw new KeyringError(
      'INVALID_STATE',
      'The user key could not be moved to the new user type; try again.',
    );
  }

  // ── data keys ───────────────────────────────────────────────────────────

  /** PROFILE key: encrypts the user's e-mail, name and device names. */
  public useProfileKey<Result>(
    userId: string,
    use: (key: Buffer) => Result | Promise<Result>,
  ): Promise<Result> {
    return this.dataKeys.usePerUserKey('PROFILE', userId, use);
  }

  /** INDEX key: the HMAC key of the user's blind search index. */
  public useIndexKey<Result>(
    userId: string,
    use: (key: Buffer) => Result | Promise<Result>,
  ): Promise<Result> {
    return this.dataKeys.usePerUserKey('INDEX', userId, use);
  }

  /** Makes sure a document has its DOCUMENT key. Idempotent. Do this before inserting the document. */
  public createDocumentKey(userId: string, documentId: string): Promise<void> {
    return this.dataKeys.ensureDocumentKey(userId, documentId);
  }

  /**
   * DOCUMENT keys of several documents at once. Derive the field key and the
   * per-object stream keys from them with `@scanova/crypto/hierarchy`.
   */
  public useDocumentKeys<Result>(
    userId: string,
    documentIds: readonly string[],
    use: (keys: ReadonlyMap<string, Buffer>) => Result | Promise<Result>,
  ): Promise<Result> {
    return this.dataKeys.useDocumentKeys(userId, documentIds, use);
  }

  /** One document's DOCUMENT key. Throws `KEY_NOT_FOUND` when it does not exist (any more). */
  public useDocumentKey<Result>(
    userId: string,
    documentId: string,
    use: (key: Buffer) => Result | Promise<Result>,
  ): Promise<Result> {
    return this.dataKeys.useDocumentKeys(userId, [documentId], async (keys) => {
      const key = keys.get(documentId);
      if (key === undefined) {
        throw new KeyringError('KEY_NOT_FOUND', 'This document has no key.');
      }
      return use(key);
    });
  }

  /** Destroys DOCUMENT keys, making those documents unrecoverable. Do this before deleting their data. */
  public destroyDocumentKeys(userId: string, documentIds: readonly string[]): Promise<number> {
    return this.dataKeys.destroyDocumentKeys(userId, documentIds);
  }

  // ── system keys ─────────────────────────────────────────────────────────

  public useSystemKey<Result>(
    purpose: SystemKeyPurpose,
    use: (key: Buffer) => Result | Promise<Result>,
  ): Promise<Result> {
    return this.systemKeys.use(purpose, use);
  }

  // ── garbage collection of keys without data ─────────────────────────────

  /**
   * DOCUMENT keys that nothing has claimed since `claimedBefore`, a page at a
   * time. The caller checks which of them have no document and removes those
   * with {@link deleteUnclaimedDocumentKeys}.
   */
  public async listUnclaimedDocumentKeys(options: {
    claimedBefore: Date;
    afterCursor?: number | undefined;
    limit: number;
  }): Promise<DocumentKeyRef[]> {
    const rows = await this.keystore.dataKey.findMany({
      where: {
        scope: 'DOCUMENT',
        claimedAt: { lt: options.claimedBefore },
        ...(options.afterCursor === undefined ? {} : { id: { gt: options.afterCursor } }),
      },
      orderBy: { id: 'asc' },
      take: options.limit,
      select: { id: true, userId: true, refId: true },
    });
    return rows.map((row) => ({ cursor: row.id, userId: row.userId, documentId: row.refId }));
  }

  /**
   * Removes orphan DOCUMENT keys. The `claimedBefore` condition is re-checked by
   * the DELETE itself, so a key that a writer claimed in the meantime survives.
   */
  public async deleteUnclaimedDocumentKeys(
    refs: readonly DocumentKeyRef[],
    claimedBefore: Date,
  ): Promise<number> {
    if (refs.length === 0) {
      return 0;
    }
    const result = await this.keystore.dataKey.deleteMany({
      where: {
        scope: 'DOCUMENT',
        id: { in: refs.map((ref) => ref.cursor) },
        claimedAt: { lt: claimedBefore },
      },
    });
    return result.count;
  }

  /** Ids of users that still have key material created before `createdBefore`, a page at a time. */
  public async listKeyOwners(options: {
    createdBefore: Date;
    afterUserId?: string | undefined;
    limit: number;
  }): Promise<string[]> {
    const rows = await this.keystore.userKey.findMany({
      where: {
        state: { not: 'DESTROYED' },
        createdAt: { lt: options.createdBefore },
        ...(options.afterUserId === undefined ? {} : { userId: { gt: options.afterUserId } }),
      },
      distinct: ['userId'],
      orderBy: { userId: 'asc' },
      take: options.limit,
      select: { userId: true },
    });
    return rows.map((row) => row.userId);
  }

  // ── status ──────────────────────────────────────────────────────────────

  /** Versions, ages and rows pending re-wrap. Never key material. */
  public async status(): Promise<KeyStatusReport> {
    const now = this.now();
    const { keys, rotation } = this.settings;
    const describe = async (
      name: string,
      purpose: MasterKeyPurpose,
      intervalDays: number,
      pending: (latestVersion: number) => Promise<number>,
    ): Promise<MasterKeyStatus> => {
      const info = await this.kms.getKeyInfo(name);
      const createdAt =
        info.versions.find((version) => version.version === info.latestVersion)?.createdAt ?? now;
      return {
        name,
        purpose,
        latestVersion: info.latestVersion,
        minDecryptionVersion: info.minDecryptionVersion,
        latestVersionCreatedAt: createdAt,
        ageDays: keyAgeDays(createdAt, now),
        rotationDue: isRotationDue({ lastRotatedAt: createdAt, now, intervalDays }),
        pendingRewrap: await pending(info.latestVersion),
      };
    };
    const pendingUserKeys =
      (name: string) =>
      (latestVersion: number): Promise<number> =>
        this.keystore.userKey.count({
          where: {
            masterKeyName: name,
            masterKeyVersion: { lt: latestVersion },
            state: { not: 'DESTROYED' },
          },
        });
    const masterKeys = [
      await describe(
        keys.userTypeAdmin,
        'USER_TYPE_ADMIN',
        rotation.userTypeKeyDays,
        pendingUserKeys(keys.userTypeAdmin),
      ),
      await describe(
        keys.userTypeMember,
        'USER_TYPE_MEMBER',
        rotation.userTypeKeyDays,
        pendingUserKeys(keys.userTypeMember),
      ),
      // The system key has no scheduled rotation in this engine (§5.4 lists none).
      await describe(keys.system, 'SYSTEM', 0, (latestVersion) =>
        this.keystore.systemKey.count({ where: { masterKeyVersion: { lt: latestVersion } } }),
      ),
      await describe(keys.jwt, 'JWT_SIGNING', rotation.jwtKeyDays, () => Promise.resolve(0)),
    ];

    const byState = await this.keystore.userKey.groupBy({ by: ['state'], _count: { _all: true } });
    const count = (state: 'ACTIVE' | 'RETIRED' | 'DESTROYED'): number =>
      byState.find((group) => group.state === state)?._count._all ?? 0;
    const active = await this.keystore.userKey.findMany({
      where: { state: 'ACTIVE' },
      select: { createdAt: true },
    });
    const pending = await this.keystore.$queryRaw<{ pending: bigint }[]>`
      SELECT count(*) AS pending FROM data_keys d
      JOIN user_keys u ON u.user_id = d.user_id AND u.version = d.user_key_version
      WHERE u.state <> 'ACTIVE'::user_key_state`;

    return {
      masterKeys,
      userKeys: {
        active: count('ACTIVE'),
        retired: count('RETIRED'),
        destroyed: count('DESTROYED'),
        rotationDue: active.filter((key) =>
          isRotationDue({ lastRotatedAt: key.createdAt, now, intervalDays: rotation.userKeyDays }),
        ).length,
        pendingRewrap: Number(pending[0]?.pending ?? 0n),
      },
      dataKeys: await this.keystore.dataKey.count(),
    };
  }

  // ── lifecycle ───────────────────────────────────────────────────────────

  /** Resolves when the key database answers. */
  public async health(): Promise<void> {
    await this.keystore.$queryRaw`SELECT 1`;
  }

  /** Wipes every cached key and closes the connection to the key database. */
  public async close(): Promise<void> {
    this.cache.close();
    await this.keystore.$disconnect();
  }
}

export interface CreateKeyringOptions {
  readonly kms: Kms;
  /** Connection settings of the key database (runtime role). */
  readonly keystore: PostgresConfig;
  readonly settings: KeyringSettings;
}

/** Builds a keyring with its own connection to the key database. */
export function createKeyring(options: CreateKeyringOptions): Keyring {
  return new Keyring({
    kms: options.kms,
    keystore: createKeystoreClient(options.keystore),
    settings: options.settings,
  });
}
