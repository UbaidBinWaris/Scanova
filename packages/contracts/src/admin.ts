import { z } from 'zod';
import { EmailSchema, UserSchema } from './auth.js';
import { IsoDateTimeSchema, PageQuerySchema, UuidSchema } from './common.js';
import {
  RotationScopeSchema,
  RotationStateSchema,
  RotationTriggerSchema,
  UserStatusSchema,
  UserTypeSchema,
} from './enums.js';
import { DisplayNameSchema } from './me.js';

export const UserListSchema = z.array(UserSchema);

export const AdminCreateUserRequestSchema = z.object({
  email: EmailSchema,
  name: DisplayNameSchema,
  userType: UserTypeSchema,
});
export type AdminCreateUserRequest = z.infer<typeof AdminCreateUserRequestSchema>;

/** The temporary password is shown exactly once and must be changed at first sign-in. */
export const AdminCreateUserResponseSchema = z.object({
  user: UserSchema,
  temporaryPassword: z.string(),
});
export type AdminCreateUserResponse = z.infer<typeof AdminCreateUserResponseSchema>;

/** A storage quota in bytes. `null` means no limit. */
export const StorageQuotaSchema = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);

export const AdminUpdateUserRequestSchema = z
  .object({
    name: DisplayNameSchema.optional(),
    status: UserStatusSchema.optional(),
    userType: UserTypeSchema.optional(),
    /** New storage quota in bytes; `null` removes the limit. Usage above it blocks new uploads only. */
    storageQuotaBytes: StorageQuotaSchema.nullable().optional(),
  })
  .refine(
    (value) =>
      value.name !== undefined ||
      value.status !== undefined ||
      value.userType !== undefined ||
      value.storageQuotaBytes !== undefined,
    { message: 'Provide at least one field to change.' },
  );
export type AdminUpdateUserRequest = z.infer<typeof AdminUpdateUserRequestSchema>;

export const AdminResetPasswordResponseSchema = z.object({ temporaryPassword: z.string() });
export type AdminResetPasswordResponse = z.infer<typeof AdminResetPasswordResponseSchema>;

export const MasterKeyPurposeSchema = z.enum([
  'USER_TYPE_ADMIN',
  'USER_TYPE_MEMBER',
  'SYSTEM',
  'JWT_SIGNING',
]);
export type MasterKeyPurpose = z.infer<typeof MasterKeyPurposeSchema>;

export const MasterKeyStatusSchema = z.object({
  name: z.string(),
  purpose: MasterKeyPurposeSchema,
  latestVersion: z.number().int(),
  minDecryptionVersion: z.number().int(),
  latestVersionCreatedAt: IsoDateTimeSchema,
  ageDays: z.number().int().nonnegative(),
  rotationDue: z.boolean(),
  /** Wrapped keys still under an older version of this master key. */
  pendingRewrap: z.number().int().nonnegative(),
});
export type MasterKeyStatus = z.infer<typeof MasterKeyStatusSchema>;

export const UserKeyStatusSchema = z.object({
  active: z.number().int().nonnegative(),
  retired: z.number().int().nonnegative(),
  destroyed: z.number().int().nonnegative(),
  rotationDue: z.number().int().nonnegative(),
  /** Data keys still wrapped under a retired user-key version. */
  pendingRewrap: z.number().int().nonnegative(),
});
export type UserKeyStatus = z.infer<typeof UserKeyStatusSchema>;

/** `GET /internal/admin/v1/keys` — versions, ages and rows pending re-wrap. Never key material. */
export const KeyStatusSchema = z.object({
  masterKeys: z.array(MasterKeyStatusSchema),
  userKeys: UserKeyStatusSchema,
  dataKeys: z.number().int().nonnegative(),
});
export type KeyStatus = z.infer<typeof KeyStatusSchema>;

export const CreateRotationRequestSchema = z
  .object({
    scope: RotationScopeSchema,
    userType: UserTypeSchema.optional(),
    userId: UuidSchema.optional(),
  })
  .superRefine((value, context) => {
    if (value.scope === 'USER_TYPE_MASTER' && value.userType === undefined) {
      context.addIssue({ code: 'custom', path: ['userType'], message: 'Required for this scope.' });
    }
    if (value.scope === 'USER_KEY' && value.userId === undefined) {
      context.addIssue({ code: 'custom', path: ['userId'], message: 'Required for this scope.' });
    }
  });
export type CreateRotationRequest = z.infer<typeof CreateRotationRequestSchema>;

export const RotationRunSchema = z.object({
  id: UuidSchema,
  scope: RotationScopeSchema,
  /** Master key name, or the user id for `USER_KEY`. */
  target: z.string(),
  state: RotationStateSchema,
  trigger: RotationTriggerSchema,
  fromVersion: z.number().int().nullable(),
  toVersion: z.number().int().nullable(),
  totalItems: z.number().int().nonnegative(),
  processedItems: z.number().int().nonnegative(),
  errorCode: z.string().nullable(),
  createdAt: IsoDateTimeSchema,
  startedAt: IsoDateTimeSchema.nullable(),
  finishedAt: IsoDateTimeSchema.nullable(),
});
export type RotationRun = z.infer<typeof RotationRunSchema>;

export const RotationRunListSchema = z.array(RotationRunSchema);

export const AuditEventSchema = z.object({
  id: UuidSchema,
  /** Null for events with no signed-in actor (failed sign-in) or once the actor is purged. */
  actorId: UuidSchema.nullable(),
  action: z.string(),
  payload: z.record(z.string(), z.unknown()),
  createdAt: IsoDateTimeSchema,
});
export type AuditEvent = z.infer<typeof AuditEventSchema>;

export const AuditEventListSchema = z.array(AuditEventSchema);

/** An audit action name: upper-case words joined by underscores, e.g. `LOGIN_FAILED`. */
export const AuditActionNameSchema = z.string().regex(/^[A-Z][A-Z0-9_]{0,63}$/u);

/** `GET /internal/admin/v1/audit-events`: newest first, optionally only one action and/or one actor. */
export const AuditEventQuerySchema = PageQuerySchema.extend({
  action: AuditActionNameSchema.optional(),
  actorId: UuidSchema.optional(),
});
export type AuditEventQuery = z.infer<typeof AuditEventQuerySchema>;
