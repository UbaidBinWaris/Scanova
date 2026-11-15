import { Body, Controller, Delete, Get, HttpCode, Param, Patch, Post, Req } from '@nestjs/common';
import type { Tokens, User } from '@scanova/contracts/auth';
import { IdParamSchema, type IdParam, type Success } from '@scanova/contracts/common';
import {
  ChangePasswordRequestSchema,
  UpdateMeRequestSchema,
  type ChangePasswordRequest,
  type Session,
  type UpdateMeRequest,
} from '@scanova/contracts/me';
import type { FastifyRequest } from 'fastify';
import type { AuthContext } from '../common/auth-context.js';
import { AllowsPendingPasswordChange, CurrentAuth } from '../common/decorators.js';
import { body, params } from '../common/zod.pipe.js';
import { MeService } from './me.service.js';

@Controller('api/v1/me')
export class MeController {
  public constructor(private readonly me: MeService) {}

  /** Also allowed with a temporary password, so the app can show whose password must be changed. */
  @Get()
  @AllowsPendingPasswordChange()
  public get(@CurrentAuth() auth: AuthContext): Promise<User> {
    return this.me.get(auth);
  }

  @Patch()
  public update(
    @CurrentAuth() auth: AuthContext,
    @Body(body(UpdateMeRequestSchema)) request: UpdateMeRequest,
  ): Promise<User> {
    return this.me.updateName(auth, request.name);
  }

  /** With a temporary password this is the only thing a user can do, besides reading `GET /me`. */
  @Post('password')
  @HttpCode(200)
  @AllowsPendingPasswordChange()
  public changePassword(
    @CurrentAuth() auth: AuthContext,
    @Body(body(ChangePasswordRequestSchema)) request: ChangePasswordRequest,
    @Req() http: FastifyRequest,
  ): Promise<Tokens> {
    return this.me.changePassword(auth, request, http.ip);
  }

  @Get('sessions')
  public sessions(@CurrentAuth() auth: AuthContext): Promise<Session[]> {
    return this.me.listSessions(auth);
  }

  @Delete('sessions/:id')
  public async revokeSession(
    @CurrentAuth() auth: AuthContext,
    @Param(params(IdParamSchema)) path: IdParam,
    @Req() http: FastifyRequest,
  ): Promise<Success> {
    await this.me.revokeSession(auth, path.id, http.ip);
    return { success: true };
  }
}
