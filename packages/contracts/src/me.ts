import { z } from 'zod';
import { NewPasswordSchema, PasswordInputSchema } from './auth.js';
import { IsoDateTimeSchema, UuidSchema } from './common.js';

export const DisplayNameSchema = z.string().trim().min(1).max(100);

export const UpdateMeRequestSchema = z.object({ name: DisplayNameSchema });
export type UpdateMeRequest = z.infer<typeof UpdateMeRequestSchema>;

export const ChangePasswordRequestSchema = z.object({
  currentPassword: PasswordInputSchema,
  newPassword: NewPasswordSchema,
});
export type ChangePasswordRequest = z.infer<typeof ChangePasswordRequestSchema>;

export const SessionSchema = z.object({
  id: UuidSchema,
  deviceName: z.string(),
  platform: z.string(),
  model: z.string(),
  createdAt: IsoDateTimeSchema,
  lastUsedAt: IsoDateTimeSchema,
  current: z.boolean(),
});
export type Session = z.infer<typeof SessionSchema>;

export const SessionListSchema = z.array(SessionSchema);
