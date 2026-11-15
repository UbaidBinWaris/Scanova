import { z } from 'zod';

/** ADMIN manages accounts and keys (web only). MEMBER owns documents. */
export const UserTypeSchema = z.enum(['ADMIN', 'MEMBER']);
export type UserType = z.infer<typeof UserTypeSchema>;

export const UserStatusSchema = z.enum(['ACTIVE', 'DISABLED']);
export type UserStatus = z.infer<typeof UserStatusSchema>;

/** Which kind of client a session belongs to. `web` is only issued by the internal login. */
export const ClientTypeSchema = z.enum(['mobile', 'web']);
export type ClientType = z.infer<typeof ClientTypeSchema>;

export const DocumentStatusSchema = z.enum(['PENDING_UPLOAD', 'READY']);
export type DocumentStatus = z.infer<typeof DocumentStatusSchema>;

export const OcrStatusSchema = z.enum(['NONE', 'PENDING', 'DONE', 'FAILED']);
export type OcrStatus = z.infer<typeof OcrStatusSchema>;

export const TextSourceSchema = z.enum(['CLIENT', 'SERVER']);
export type TextSource = z.infer<typeof TextSourceSchema>;

export const FileVariantSchema = z.enum(['ORIGINAL', 'SEARCHABLE']);
export type FileVariant = z.infer<typeof FileVariantSchema>;

export const RotationScopeSchema = z.enum(['USER_TYPE_MASTER', 'USER_KEY', 'JWT_SIGNING']);
export type RotationScope = z.infer<typeof RotationScopeSchema>;

export const RotationStateSchema = z.enum(['PENDING', 'RUNNING', 'COMPLETED', 'FAILED']);
export type RotationState = z.infer<typeof RotationStateSchema>;

export const RotationTriggerSchema = z.enum([
  'SCHEDULED',
  'MANUAL',
  'PASSWORD_RESET',
  'USER_TYPE_CHANGE',
]);
export type RotationTrigger = z.infer<typeof RotationTriggerSchema>;
