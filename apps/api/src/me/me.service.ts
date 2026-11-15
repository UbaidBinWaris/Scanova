import { Inject, Injectable } from '@nestjs/common';
import type { Tokens, User as UserDto } from '@scanova/contracts/auth';
import type { ChangePasswordRequest, Session as SessionDto } from '@scanova/contracts/me';
import type { DatabaseClient } from '@scanova/database/client';
import { AuditService } from '../audit/audit.service.js';
import { AuthService } from '../auth/auth.service.js';
import { LoginRateLimiter } from '../auth/login-rate-limiter.js';
import { PasswordService } from '../auth/password.service.js';
import { SessionService } from '../auth/session.service.js';
import { AppError } from '../common/app-error.js';
import type { AuthContext } from '../common/auth-context.js';
import { DATABASE } from '../platform/tokens.js';
import { UsersService } from '../users/users.service.js';

/** The signed-in user's own account. */
@Injectable()
export class MeService {
  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    private readonly users: UsersService,
    private readonly passwords: PasswordService,
    private readonly sessions: SessionService,
    private readonly auth: AuthService,
    private readonly audit: AuditService,
    private readonly rateLimiter: LoginRateLimiter,
  ) {}

  public async get(auth: AuthContext): Promise<UserDto> {
    return this.users.toDto(await this.users.getByPublicId(auth.userPublicId));
  }

  public async updateName(auth: AuthContext, name: string): Promise<UserDto> {
    const user = await this.users.getByPublicId(auth.userPublicId);
    return this.users.toDto(await this.users.updateName(user, name));
  }

  /**
   * Changes the password, lifts a pending "must change" flag, signs every other
   * device out and gives this one fresh tokens.
   */
  public async changePassword(
    auth: AuthContext,
    request: ChangePasswordRequest,
    ip: string,
  ): Promise<Tokens> {
    // Limits guessing with a stolen access token, and the Argon2 work it can cause.
    this.rateLimiter.hitPasswordChange(auth.userId);
    const user = await this.users.getByPublicId(auth.userPublicId);
    if (!(await this.passwords.verify(user.passwordHash, request.currentPassword))) {
      throw new AppError('INVALID_CREDENTIALS', { message: 'The current password is incorrect.' });
    }
    if (request.newPassword === request.currentPassword) {
      throw new AppError('VALIDATION_FAILED', {
        details: [
          { field: 'newPassword', message: 'Choose a password different from the current one.' },
        ],
      });
    }
    const updated = await this.database.user.update({
      where: { id: user.id },
      data: {
        passwordHash: await this.passwords.hash(request.newPassword),
        mustChangePassword: false,
      },
    });
    const revoked = await this.sessions.revokeAll(user.id, auth.sessionId);
    const refresh = await this.sessions.reissue(auth.sessionId);
    const session = await this.database.session.findUniqueOrThrow({
      where: { id: auth.sessionId },
    });
    await this.audit.record('PASSWORD_CHANGED', user.id, { ip, otherSessionsRevoked: revoked });
    return this.auth.issue(updated, session, refresh);
  }

  public listSessions(auth: AuthContext): Promise<SessionDto[]> {
    return this.sessions.list({ id: auth.userId, publicId: auth.userPublicId }, auth.sessionId);
  }

  public async revokeSession(
    auth: AuthContext,
    sessionPublicId: string,
    ip: string,
  ): Promise<void> {
    await this.sessions.revokeOwned(auth.userId, sessionPublicId);
    await this.audit.record('SESSION_REVOKED', auth.userId, { ip, sessionId: sessionPublicId });
  }
}
