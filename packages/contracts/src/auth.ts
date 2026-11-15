import { z } from 'zod';
import { IsoDateTimeSchema, UuidSchema } from './common.js';
import { ClientTypeSchema, UserStatusSchema, UserTypeSchema } from './enums.js';

export const MIN_PASSWORD_LENGTH = 12;
export const MAX_PASSWORD_LENGTH = 256;

export const EmailSchema = z.string().trim().max(254).pipe(z.email());

/** A password being checked. No policy here: old passwords must stay usable. */
export const PasswordInputSchema = z.string().min(1).max(1024);

/** A password being set. */
export const NewPasswordSchema = z.string().min(MIN_PASSWORD_LENGTH).max(MAX_PASSWORD_LENGTH);

export const UserSchema = z.object({
  id: UuidSchema,
  email: z.string(),
  name: z.string(),
  userType: UserTypeSchema,
  status: UserStatusSchema,
  mustChangePassword: z.boolean(),
  storageUsedBytes: z.number().int().nonnegative(),
  storageQuotaBytes: z.number().int().nonnegative().nullable(),
  createdAt: IsoDateTimeSchema,
});
export type User = z.infer<typeof UserSchema>;

export const TokensSchema = z.object({
  accessToken: z.string(),
  accessTokenExpiresAt: IsoDateTimeSchema,
  refreshToken: z.string(),
  refreshTokenExpiresAt: IsoDateTimeSchema,
  user: UserSchema,
});
export type Tokens = z.infer<typeof TokensSchema>;

export const MobileDeviceSchema = z.object({
  name: z.string().trim().min(1).max(100),
  platform: z.literal('android'),
  model: z.string().trim().min(1).max(100),
  appVersion: z.string().trim().min(1).max(32),
});
export type MobileDevice = z.infer<typeof MobileDeviceSchema>;

/** `POST /api/v1/auth/login` — the phone. Only `client: "mobile"` exists on this route. */
export const LoginRequestSchema = z.object({
  email: EmailSchema,
  password: PasswordInputSchema,
  client: z.literal('mobile'),
  device: MobileDeviceSchema,
});
export type LoginRequest = z.infer<typeof LoginRequestSchema>;

/** `POST /internal/web/v1/auth/login` — the web app, behind the service token. */
export const WebLoginRequestSchema = z.object({
  email: EmailSchema,
  password: PasswordInputSchema,
  device: z
    .object({
      name: z.string().trim().min(1).max(100),
    })
    .optional(),
});
export type WebLoginRequest = z.infer<typeof WebLoginRequestSchema>;

export const RefreshTokenInputSchema = z.string().min(20).max(512);

export const RefreshRequestSchema = z.object({ refreshToken: RefreshTokenInputSchema });
export type RefreshRequest = z.infer<typeof RefreshRequestSchema>;

export const LogoutRequestSchema = z.object({ refreshToken: RefreshTokenInputSchema });
export type LogoutRequest = z.infer<typeof LogoutRequestSchema>;

/** Claims of an access token (§5.5). */
export const AccessTokenClaimsSchema = z.object({
  sub: UuidSchema,
  typ: UserTypeSchema,
  cli: ClientTypeSchema,
  sid: UuidSchema,
  iss: z.string(),
  iat: z.number().int(),
  exp: z.number().int(),
});
export type AccessTokenClaims = z.infer<typeof AccessTokenClaimsSchema>;
