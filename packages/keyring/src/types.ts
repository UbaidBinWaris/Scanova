import type { KeyNamesConfig, RotationConfig } from '@scanova/config/sections';

export type UserKind = 'ADMIN' | 'MEMBER';
export type SystemKeyPurpose = 'EMAIL_INDEX' | 'AUDIT';
export type RotationScope = 'USER_TYPE_MASTER' | 'USER_KEY' | 'JWT_SIGNING';
export type RotationState = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
export type RotationTrigger = 'SCHEDULED' | 'MANUAL' | 'PASSWORD_RESET' | 'USER_TYPE_CHANGE';

export interface KeyringSettings {
  readonly keys: KeyNamesConfig;
  readonly rotation: RotationConfig;
  /** Lifetime of an access token: the old signing-key version must verify at least this long. */
  readonly accessTokenTtlSeconds: number;
  /** How long unwrapped keys may stay in memory. Capped at five minutes. */
  readonly cacheTtlMs?: number;
  /**
   * Master-key rotation waits this long after the new version exists before it
   * stops the old one from decrypting, so a user key wrapped a moment before the
   * rotation has certainly been written and re-wrapped. Default 60 s.
   */
  readonly masterKeySettleSeconds?: number;
  /**
   * Extra time on top of the access-token lifetime before an old signing-key
   * version is retired: covers API processes that still sign with their cached
   * key version, plus clock skew. Default 120 s.
   */
  readonly signingKeyRetireMarginSeconds?: number;
  /** Clock, injectable for tests. */
  readonly now?: () => Date;
}

/** One rotation, as shown to operators. `id` is the public id. */
export interface RotationRunRecord {
  readonly id: string;
  readonly scope: RotationScope;
  /** Master key name, or the user's public id for `USER_KEY`. */
  readonly target: string;
  readonly state: RotationState;
  readonly trigger: RotationTrigger;
  readonly fromVersion: number | null;
  readonly toVersion: number | null;
  readonly totalItems: number;
  readonly processedItems: number;
  readonly errorCode: string | null;
  readonly requestedBy: string | null;
  readonly createdAt: Date;
  readonly startedAt: Date | null;
  readonly finishedAt: Date | null;
}

export type MasterKeyPurpose = 'USER_TYPE_ADMIN' | 'USER_TYPE_MEMBER' | 'SYSTEM' | 'JWT_SIGNING';

export interface MasterKeyStatus {
  readonly name: string;
  readonly purpose: MasterKeyPurpose;
  readonly latestVersion: number;
  readonly minDecryptionVersion: number;
  readonly latestVersionCreatedAt: Date;
  readonly ageDays: number;
  readonly rotationDue: boolean;
  /** Wrapped keys still under an older version of this master key. */
  readonly pendingRewrap: number;
}

export interface KeyStatusReport {
  readonly masterKeys: readonly MasterKeyStatus[];
  readonly userKeys: {
    readonly active: number;
    readonly retired: number;
    readonly destroyed: number;
    readonly rotationDue: number;
    /** Data keys still wrapped under a retired user-key version. */
    readonly pendingRewrap: number;
  };
  readonly dataKeys: number;
}

/** Outcome of working on a rotation run. */
export type RotationProgress =
  | { readonly state: 'COMPLETED'; readonly run: RotationRunRecord }
  /** Nothing more to do right now; call again after the delay. */
  | { readonly state: 'WAITING'; readonly run: RotationRunRecord; readonly retryAfterMs: number };
