import { Body, Controller, Get, HttpCode, Post, Put } from '@nestjs/common';
import {
  SetAiRuntimeRequestSchema,
  type AiRuntimeStatus,
  type SetAiRuntimeRequest,
} from '@scanova/contracts/ai-runtime';
import type { AuthContext } from '../common/auth-context.js';
import { CurrentAuth } from '../common/decorators.js';
import { body } from '../common/zod.pipe.js';
import { AiRuntimeService } from './ai-runtime.service.js';

/** The server AI switch (§11.8). Owners only; everyone else gets `FORBIDDEN`. */
@Controller('api/v1/ai/runtime')
export class AiRuntimeController {
  public constructor(private readonly runtime: AiRuntimeService) {}

  @Get()
  public status(@CurrentAuth() auth: AuthContext): Promise<AiRuntimeStatus> {
    return this.runtime.status(auth);
  }

  /** Answers at once; while the models load the state is `LOADING`. */
  @Put()
  public set(
    @CurrentAuth() auth: AuthContext,
    @Body(body(SetAiRuntimeRequestSchema)) request: SetAiRuntimeRequest,
  ): Promise<AiRuntimeStatus> {
    return this.runtime.setOn(auth, request.on);
  }

  /** Sent by an open app every `heartbeatSeconds` while it is on. */
  @Post('heartbeat')
  @HttpCode(200)
  public heartbeat(@CurrentAuth() auth: AuthContext): Promise<AiRuntimeStatus> {
    return this.runtime.heartbeat(auth);
  }
}
