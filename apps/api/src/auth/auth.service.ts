import { Inject, Injectable } from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import type { Tokens } from '@scanova/contracts/auth';
import type { ClientType } from '@scanova/contracts/enums';
import type { DatabaseClient, Session, User } from '@scanova/database/client';
import { AuditService } from '../audit/audit.service.js';
import { AppError } from '../common/app-error.js';
import { API_CONFIG, DATABASE } from '../platform/tokens.js';
import { UsersService } from '../users/users.service.js';
import { LoginRateLimiter } from './login-rate-limiter.js';
import { PasswordService } from './password.service.js';
import { SessionService, type DeviceInfo, type IssuedRefreshToken } from './session.service.js';
import { TokenService } from './token.service.js';

export interface LoginInput {
  readonly email: string;
  readonly password: string;
  readonly client: ClientType;
  readonly device: DeviceInfo;
  readonly ip: string;
}

/** Open sign-up from the phone (SignupConfig). */
export interface RegisterInput {
  readonly email: string;
  readonly name: string;
  readonly password: string;
  readonly device: DeviceInfo;
  readonly ip: string;
}

/** Sign-in, refresh and sign-out (§5.5, §7.3). */
@Injectable()
export class AuthService {
  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    private readonly users: UsersService,
    private readonly passwords: PasswordService,
    private readonly sessions: SessionService,
    private readonly tokens: TokenService,
    private readonly rateLimiter: LoginRateLimiter,
    private readonly audit: AuditService,
    @Inject(API_CONFIG) private readonly config: ApiConfig,
  ) {}

  /**
   * Creates a Member account with the sign-up quota and signs it in on this
   * phone. Rate-limited per IP and in total before any work is done. An e-mail
   * that is already registered answers ID_CONFLICT.
   */
  public async register(input: RegisterInput): Promise<Tokens> {
    if (!this.config.signup.enabled) {
      throw new AppError('FORBIDDEN', { message: 'Sign-up is closed on this server.' });
    }
    this.rateLimiter.hitSignup(input.ip);
    const user = await this.users.create({
      email: input.email,
      name: input.name,
      userType: 'MEMBER',
      password: input.password,
      mustChangePassword: false,
      quotaBytes: this.config.signup.quotaBytes,
    });
    const { session, refresh } = await this.sessions.create(user, 'MOBILE', input.device);
    await this.audit.record('SIGNUP', user.id, { ip: input.ip, client: 'mobile' });
    return this.issue(user, session, refresh);
  }

  public async login(input: LoginInput): Promise<Tokens> {
    this.rateLimiter.hit(input.ip, input.email);

    const user = await this.users.findByEmail(input.email);
    if (user === null) {
      await this.passwords.verifyDecoy(input.password);
      await this.audit.record('LOGIN_FAILED', null, { ip: input.ip, client: input.client });
      throw new AppError('INVALID_CREDENTIALS');
    }
    if (user.userType === 'ADMIN' && input.client === 'mobile') {
      // Admins are web-only (§5.5). Their password is never checked on the public
      // route, so it cannot be used to test admin passwords; the answer and timing
      // match an unknown account.
      await this.passwords.verifyDecoy(input.password);
      await this.audit.record('LOGIN_REFUSED_ADMIN_MOBILE', user.id, { ip: input.ip });
      throw new AppError('INVALID_CREDENTIALS');
    }
    if (!(await this.passwords.verify(user.passwordHash, input.password))) {
      await this.audit.record('LOGIN_FAILED', user.id, { ip: input.ip, client: input.client });
      throw new AppError('INVALID_CREDENTIALS');
    }
    // From here on the caller has proven the password, so account state may be revealed.
    if (user.status !== 'ACTIVE') {
      await this.audit.record('LOGIN_REFUSED_DISABLED', user.id, {
        ip: input.ip,
        client: input.client,
      });
      throw new AppError('ACCOUNT_DISABLED');
    }
    if (this.passwords.needsRehash(user.passwordHash)) {
      // The Argon2 parameters changed since this hash was made: upgrade it now that we hold the password.
      await this.database.user.update({
        where: { id: user.id },
        data: { passwordHash: await this.passwords.hash(input.password) },
      });
    }
    const { session, refresh } = await this.sessions.create(
      user,
      input.client === 'web' ? 'WEB' : 'MOBILE',
      input.device,
    );
    this.rateLimiter.reset(input.ip, input.email);
    await this.audit.record('LOGIN_SUCCEEDED', user.id, {
      ip: input.ip,
      client: input.client,
      sessionId: session.publicId,
    });
    return this.issue(user, session, refresh);
  }

  public async refresh(refreshToken: string, ip: string): Promise<Tokens> {
    const outcome = await this.sessions.rotate(refreshToken);
    switch (outcome.kind) {
      case 'UNKNOWN':
        throw new AppError('INVALID_REFRESH_TOKEN');
      case 'REVOKED':
        throw new AppError('SESSION_REVOKED');
      case 'REUSED':
        await this.audit.record('REFRESH_TOKEN_REUSED', outcome.session.userId, {
          ip,
          sessionId: outcome.session.publicId,
        });
        throw new AppError('SESSION_REVOKED');
      case 'ROTATED': {
        const { user } = outcome.session;
        if (user.deletedAt !== null || user.status !== 'ACTIVE') {
          await this.sessions.revoke(outcome.session.id);
          throw new AppError('ACCOUNT_DISABLED');
        }
        return this.issue(user, outcome.session, outcome.refresh);
      }
    }
  }

  /**
   * Idempotent: an unknown token signs nobody out and is not an error. A token
   * that was already exchanged still ends its session, so a sign-out that races
   * the phone's own token refresh cannot leave the session alive.
   */
  public async logout(refreshToken: string, ip: string): Promise<void> {
    const session = await this.sessions.findByAnyRefreshToken(refreshToken);
    if (session === null || session.revokedAt !== null) {
      return;
    }
    await this.sessions.revoke(session.id);
    await this.audit.record('LOGOUT', session.userId, { ip, sessionId: session.publicId });
  }

  /** Builds the `Tokens` answer for a session. */
  public async issue(user: User, session: Session, refresh: IssuedRefreshToken): Promise<Tokens> {
    const access = await this.tokens.issue({
      sub: user.publicId,
      typ: user.userType,
      cli: session.client === 'WEB' ? 'web' : 'mobile',
      sid: session.publicId,
    });
    return {
      accessToken: access.token,
      accessTokenExpiresAt: access.expiresAt.toISOString(),
      refreshToken: refresh.token,
      refreshTokenExpiresAt: refresh.expiresAt.toISOString(),
      user: await this.users.toDto(user),
    };
  }
}
