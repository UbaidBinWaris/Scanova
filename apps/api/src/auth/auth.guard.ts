import { Inject, Injectable, type CanActivate, type ExecutionContext } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import type { ApiConfig } from '@scanova/config/sections';
import { constantTimeEqual } from '@scanova/crypto/constant-time';
import type { DatabaseClient } from '@scanova/database/client';
import type { FastifyRequest } from 'fastify';
import { AppError } from '../common/app-error.js';
import {
  ALLOWS_PENDING_PASSWORD_CHANGE,
  IS_ADMIN_ONLY,
  IS_INTERNAL,
  IS_PUBLIC,
} from '../common/decorators.js';
import { API_CONFIG, DATABASE } from '../platform/tokens.js';
import { TokenService } from './token.service.js';

export const SERVICE_TOKEN_HEADER = 'x-scanova-service-token';
const BEARER_PATTERN = /^Bearer ([A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+)$/u;

/**
 * The single authentication and authorisation gate, applied to every route.
 *
 * 1. `/internal/*` routes first prove the caller is the web app: the service
 *    token is compared in constant time. This runs before anything else, so an
 *    outsider learns nothing about those routes.
 * 2. Unless a route is public, the access token is verified and the session it
 *    names is loaded: a revoked session or a disabled account stops working at
 *    once, not when the token expires.
 * 3. A user with a temporary password may only read their account and change the password.
 * 4. Admin routes need `typ = ADMIN` and `cli = web`.
 */
@Injectable()
export class AuthGuard implements CanActivate {
  public constructor(
    private readonly reflector: Reflector,
    private readonly tokens: TokenService,
    @Inject(DATABASE) private readonly database: DatabaseClient,
    @Inject(API_CONFIG) private readonly config: ApiConfig,
  ) {}

  public async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<FastifyRequest>();
    const targets = [context.getHandler(), context.getClass()];
    const flag = (key: string): boolean =>
      this.reflector.getAllAndOverride<boolean | undefined>(key, targets) === true;

    if (flag(IS_INTERNAL)) {
      this.assertServiceToken(request);
    }
    if (flag(IS_PUBLIC)) {
      return true;
    }

    const claims = await this.tokens.verify(this.bearerToken(request));
    const session = await this.database.session.findUnique({
      where: { publicId: claims.sid },
      include: { user: true },
    });
    if (session === null || session.user.publicId !== claims.sub) {
      throw new AppError('UNAUTHENTICATED');
    }
    if (session.revokedAt !== null) {
      throw new AppError('SESSION_REVOKED');
    }
    const { user } = session;
    if (user.deletedAt !== null || user.status !== 'ACTIVE') {
      throw new AppError('ACCOUNT_DISABLED');
    }
    const client = session.client === 'WEB' ? 'web' : 'mobile';
    if (claims.typ !== user.userType || claims.cli !== client) {
      // The account changed type since the token was issued, or the token lies.
      throw new AppError('UNAUTHENTICATED');
    }
    if (user.mustChangePassword && !flag(ALLOWS_PENDING_PASSWORD_CHANGE)) {
      throw new AppError('PASSWORD_CHANGE_REQUIRED');
    }
    if (flag(IS_ADMIN_ONLY) && !(user.userType === 'ADMIN' && client === 'web')) {
      throw new AppError('FORBIDDEN');
    }

    request.auth = {
      userId: user.id,
      userPublicId: user.publicId,
      userType: user.userType,
      client,
      sessionId: session.id,
      sessionPublicId: session.publicId,
    };
    return true;
  }

  private assertServiceToken(request: FastifyRequest): void {
    const presented = request.headers[SERVICE_TOKEN_HEADER];
    // Compared even when the header is missing, so both failures take the same time.
    const candidate = typeof presented === 'string' ? presented : '';
    const matches = constantTimeEqual(candidate, this.config.auth.webServiceToken);
    if (!matches || candidate.length === 0) {
      throw new AppError('UNAUTHENTICATED');
    }
  }

  private bearerToken(request: FastifyRequest): string {
    const match = BEARER_PATTERN.exec(request.headers.authorization ?? '');
    if (match?.[1] === undefined) {
      throw new AppError('UNAUTHENTICATED');
    }
    return match[1];
  }
}
