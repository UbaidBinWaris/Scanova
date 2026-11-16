import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  Param,
  Patch,
  Post,
  Query,
  Req,
} from '@nestjs/common';
import {
  AdminCreateUserRequestSchema,
  AdminUpdateUserRequestSchema,
  AuditEventQuerySchema,
  CreateRotationRequestSchema,
  type AdminCreateUserRequest,
  type AdminCreateUserResponse,
  type AdminResetPasswordResponse,
  type AdminUpdateUserRequest,
  type AuditEvent,
  type AuditEventQuery,
  type CreateRotationRequest,
  type KeyStatus,
  type RotationRun,
} from '@scanova/contracts/admin';
import type { User } from '@scanova/contracts/auth';
import {
  IdParamSchema,
  PageQuerySchema,
  type IdParam,
  type PageQuery,
  type Success,
} from '@scanova/contracts/common';
import type { FastifyRequest } from 'fastify';
import { AuditService } from '../../audit/audit.service.js';
import type { AuthContext } from '../../common/auth-context.js';
import { AdminOnly, CurrentAuth, Internal } from '../../common/decorators.js';
import type { Page } from '../../common/page.js';
import { body, params, query } from '../../common/zod.pipe.js';
import { webActor } from './actor.js';
import { AdminKeysService } from './admin-keys.service.js';
import { AdminUsersService } from './admin-users.service.js';

/**
 * Admin console API (§7.4). Three locks, all enforced by the global guard:
 * the web service token, `typ = ADMIN`, and `cli = web`. Nothing here returns
 * another user's document content.
 */
@Controller('internal/admin/v1')
@Internal()
@AdminOnly()
export class AdminController {
  public constructor(
    private readonly users: AdminUsersService,
    private readonly keys: AdminKeysService,
    private readonly audit: AuditService,
  ) {}

  @Get('users')
  public listUsers(@Query(query(PageQuerySchema)) page: PageQuery): Promise<Page<User>> {
    return this.users.list(page);
  }

  @Post('users')
  public createUser(
    @CurrentAuth() auth: AuthContext,
    @Body(body(AdminCreateUserRequestSchema)) request: AdminCreateUserRequest,
    @Req() http: FastifyRequest,
  ): Promise<AdminCreateUserResponse> {
    return this.users.create(webActor(auth, http.ip), request);
  }

  @Patch('users/:id')
  public updateUser(
    @CurrentAuth() auth: AuthContext,
    @Param(params(IdParamSchema)) path: IdParam,
    @Body(body(AdminUpdateUserRequestSchema)) request: AdminUpdateUserRequest,
    @Req() http: FastifyRequest,
  ): Promise<User> {
    return this.users.update(webActor(auth, http.ip), path.id, request);
  }

  @Post('users/:id/reset-password')
  @HttpCode(200)
  public resetPassword(
    @CurrentAuth() auth: AuthContext,
    @Param(params(IdParamSchema)) path: IdParam,
    @Req() http: FastifyRequest,
  ): Promise<AdminResetPasswordResponse> {
    return this.users.resetPassword(webActor(auth, http.ip), path.id);
  }

  @Post('users/:id/revoke-sessions')
  @HttpCode(200)
  public async revokeSessions(
    @CurrentAuth() auth: AuthContext,
    @Param(params(IdParamSchema)) path: IdParam,
    @Req() http: FastifyRequest,
  ): Promise<Success> {
    await this.users.revokeSessions(webActor(auth, http.ip), path.id);
    return { success: true };
  }

  @Delete('users/:id')
  public async deleteUser(
    @CurrentAuth() auth: AuthContext,
    @Param(params(IdParamSchema)) path: IdParam,
    @Req() http: FastifyRequest,
  ): Promise<Success> {
    await this.users.delete(webActor(auth, http.ip), path.id);
    return { success: true };
  }

  @Get('keys')
  public keyStatus(): Promise<KeyStatus> {
    return this.keys.status();
  }

  @Post('keys/rotations')
  public requestRotation(
    @CurrentAuth() auth: AuthContext,
    @Body(body(CreateRotationRequestSchema)) request: CreateRotationRequest,
    @Req() http: FastifyRequest,
  ): Promise<RotationRun> {
    return this.keys.requestRotation(webActor(auth, http.ip), request);
  }

  @Get('keys/rotations')
  public listRotations(@Query(query(PageQuerySchema)) page: PageQuery): Promise<Page<RotationRun>> {
    return this.keys.listRotations(page);
  }

  @Get('audit-events')
  public auditEvents(
    @Query(query(AuditEventQuerySchema)) filter: AuditEventQuery,
  ): Promise<Page<AuditEvent>> {
    return this.audit.list(filter);
  }
}
