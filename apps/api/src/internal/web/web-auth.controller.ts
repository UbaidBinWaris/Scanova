import { Body, Controller, HttpCode, Post, Req } from '@nestjs/common';
import { WebLoginRequestSchema, type Tokens, type WebLoginRequest } from '@scanova/contracts/auth';
import type { FastifyRequest } from 'fastify';
import { AuthService } from '../../auth/auth.service.js';
import { Internal, Public } from '../../common/decorators.js';
import { body } from '../../common/zod.pipe.js';

/**
 * Web sign-in for members and admins (§7.4). This is the only place that issues
 * `cli: "web"` tokens, and it is only reachable with the web service token: the
 * edge never proxies `/internal/*`.
 */
@Controller('internal/web/v1/auth')
@Internal()
@Public()
export class WebAuthController {
  public constructor(private readonly auth: AuthService) {}

  @Post('login')
  @HttpCode(200)
  public login(
    @Body(body(WebLoginRequestSchema)) request: WebLoginRequest,
    @Req() http: FastifyRequest,
  ): Promise<Tokens> {
    return this.auth.login({
      email: request.email,
      password: request.password,
      client: 'web',
      device: {
        name: request.device?.name ?? 'Web browser',
        model: 'Browser',
        platform: 'web',
        appVersion: null,
      },
      // The web app forwards the browser's address; behind it, this is the browser's IP.
      ip: http.ip,
    });
  }
}
