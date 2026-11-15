import { SetMetadata, createParamDecorator, type ExecutionContext } from '@nestjs/common';
import type { FastifyRequest } from 'fastify';
import type { z } from 'zod';
import { AppError } from './app-error.js';
import type { AuthContext } from './auth-context.js';
import { ZodPipe } from './zod.pipe.js';

export const IS_PUBLIC = 'scanova:public';
export const IS_INTERNAL = 'scanova:internal';
export const IS_ADMIN_ONLY = 'scanova:admin-only';
export const ALLOWS_PENDING_PASSWORD_CHANGE = 'scanova:allows-pending-password-change';

/** No access token needed (health, server info, sign-in, refresh, sign-out). */
export const Public = (): MethodDecorator & ClassDecorator => SetMetadata(IS_PUBLIC, true);

/** `/internal/*`: every request must carry the web service token (§7.4). */
export const Internal = (): ClassDecorator => SetMetadata(IS_INTERNAL, true);

/** Requires an access token with `typ = ADMIN` and `cli = web` (§5.5). */
export const AdminOnly = (): MethodDecorator & ClassDecorator => SetMetadata(IS_ADMIN_ONLY, true);

/**
 * Routes a user with a temporary password may call: `GET /me` and
 * `POST /me/password`. (Sign-in, refresh and sign-out are public routes and
 * keep working too.) Everything else answers `PASSWORD_CHANGE_REQUIRED`.
 */
export const AllowsPendingPasswordChange = (): MethodDecorator =>
  SetMetadata(ALLOWS_PENDING_PASSWORD_CHANGE, true);

/** The authenticated caller. Only usable on routes behind the auth guard. */
export const CurrentAuth = createParamDecorator(
  (_data: unknown, context: ExecutionContext): AuthContext => {
    const request = context.switchToHttp().getRequest<FastifyRequest>();
    if (request.auth === undefined) {
      throw new AppError('UNAUTHENTICATED');
    }
    return request.auth;
  },
);

/** Request headers validated against a contract schema (header names are lower case). */
export function ValidatedHeaders<Schema extends z.ZodType>(schema: Schema): ParameterDecorator {
  return createParamDecorator((_data: unknown, context: ExecutionContext): z.infer<Schema> => {
    const request = context.switchToHttp().getRequest<FastifyRequest>();
    return new ZodPipe(schema, 'headers').transform(request.headers);
  })();
}
