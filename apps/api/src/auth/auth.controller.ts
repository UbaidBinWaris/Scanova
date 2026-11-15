import { Body, Controller, HttpCode, Post, Req } from '@nestjs/common';
import {
  LoginRequestSchema,
  LogoutRequestSchema,
  RefreshRequestSchema,
  RegisterRequestSchema,
  type LoginRequest,
  type LogoutRequest,
  type RefreshRequest,
  type RegisterRequest,
  type Tokens,
} from '@scanova/contracts/auth';
import type { Success } from '@scanova/contracts/common';
import type { FastifyRequest } from 'fastify';
import { Public } from '../common/decorators.js';
import { body } from '../common/zod.pipe.js';
import { AuthService } from './auth.service.js';

/** Client sign-in. Only `client: "mobile"` exists here; web tokens come from the internal login. */
@Controller('api/v1/auth')
@Public()
export class AuthController {
  public constructor(private readonly auth: AuthService) {}

  @Post('login')
  @HttpCode(200)
  public login(
    @Body(body(LoginRequestSchema)) request: LoginRequest,
    @Req() http: FastifyRequest,
  ): Promise<Tokens> {
    return this.auth.login({
      email: request.email,
      password: request.password,
      client: 'mobile',
      device: {
        name: request.device.name,
        model: request.device.model,
        platform: request.device.platform,
        appVersion: request.device.appVersion,
      },
      ip: http.ip,
    });
  }

  /** Open sign-up: a Member account with the sign-up quota, signed in on this phone. */
  @Post('register')
  @HttpCode(201)
  public register(
    @Body(body(RegisterRequestSchema)) request: RegisterRequest,
    @Req() http: FastifyRequest,
  ): Promise<Tokens> {
    return this.auth.register({
      email: request.email,
      name: request.name,
      password: request.password,
      device: {
        name: request.device.name,
        model: request.device.model,
        platform: request.device.platform,
        appVersion: request.device.appVersion,
      },
      ip: http.ip,
    });
  }

  @Post('refresh')
  @HttpCode(200)
  public refresh(
    @Body(body(RefreshRequestSchema)) request: RefreshRequest,
    @Req() http: FastifyRequest,
  ): Promise<Tokens> {
    return this.auth.refresh(request.refreshToken, http.ip);
  }

  @Post('logout')
  @HttpCode(200)
  public async logout(
    @Body(body(LogoutRequestSchema)) request: LogoutRequest,
    @Req() http: FastifyRequest,
  ): Promise<Success> {
    await this.auth.logout(request.refreshToken, http.ip);
    return { success: true };
  }
}
