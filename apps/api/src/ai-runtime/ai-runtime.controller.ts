import type { IncomingMessage } from 'node:http';
import {
  Body,
  Controller,
  Get,
  HttpCode,
  Post,
  Put,
  Query,
  Req,
  Res,
  StreamableFile,
} from '@nestjs/common';
import {
  AI_ENHANCE_MAX_BYTES,
  AiRuntimeQuerySchema,
  SetAiRuntimeRequestSchema,
  type AiRuntimeQuery,
  type AiRuntimeStatus,
  type SetAiRuntimeRequest,
} from '@scanova/contracts/ai-runtime';
import type { FastifyReply, FastifyRequest } from 'fastify';
import { AppError } from '../common/app-error.js';
import type { AuthContext } from '../common/auth-context.js';
import { CurrentAuth } from '../common/decorators.js';
import { body, query } from '../common/zod.pipe.js';
import { AiRuntimeService } from './ai-runtime.service.js';

/**
 * The server AI switches (§11.8): `?group=text` (the default) and
 * `?group=image`, and the page clean-up itself. Owners only; everyone else
 * gets `FORBIDDEN`.
 */
@Controller('api/v1/ai')
export class AiRuntimeController {
  public constructor(private readonly runtime: AiRuntimeService) {}

  @Get('runtime')
  public status(
    @CurrentAuth() auth: AuthContext,
    @Query(query(AiRuntimeQuerySchema)) params: AiRuntimeQuery,
  ): Promise<AiRuntimeStatus> {
    return this.runtime.status(auth, params.group);
  }

  /** Answers at once; while the models load the state is `LOADING`. */
  @Put('runtime')
  public set(
    @CurrentAuth() auth: AuthContext,
    @Query(query(AiRuntimeQuerySchema)) params: AiRuntimeQuery,
    @Body(body(SetAiRuntimeRequestSchema)) request: SetAiRuntimeRequest,
  ): Promise<AiRuntimeStatus> {
    return this.runtime.setOn(auth, request.on, params.group);
  }

  /** Sent by an open app every `heartbeatSeconds` for each switch that is on. */
  @Post('runtime/heartbeat')
  @HttpCode(200)
  public heartbeat(
    @CurrentAuth() auth: AuthContext,
    @Query(query(AiRuntimeQuerySchema)) params: AiRuntimeQuery,
  ): Promise<AiRuntimeStatus> {
    return this.runtime.heartbeat(auth, params.group);
  }

  /**
   * Page clean-up: a raw `image/jpeg` body (the flattened page, at most
   * AI_ENHANCE_MAX_BYTES) in, the cleaned JPEG out, not enveloped. The page
   * passes through memory only.
   */
  @Post('images/enhance')
  @HttpCode(200)
  public async enhance(
    @CurrentAuth() auth: AuthContext,
    @Req() request: FastifyRequest,
    @Res({ passthrough: true }) reply: FastifyReply,
  ): Promise<StreamableFile> {
    if (request.headers['content-type']?.split(';')[0]?.trim() !== 'image/jpeg') {
      throw new AppError('UNSUPPORTED_MEDIA_TYPE', { message: 'Send the page as image/jpeg.' });
    }
    // Read only once the caller is an owner and clean-up is ready (checked first).
    const cleaned = await this.runtime.enhance(auth, () =>
      readLimited(request.raw, AI_ENHANCE_MAX_BYTES),
    );
    void reply.header('cache-control', 'no-store');
    return new StreamableFile(cleaned, { type: 'image/jpeg', length: cleaned.length });
  }
}

/** The whole body, refusing (FILE_TOO_LARGE) once it passes [limit] bytes, declared or not. */
async function readLimited(stream: IncomingMessage, limit: number): Promise<Buffer> {
  const declared = Number(stream.headers['content-length'] ?? NaN);
  if (Number.isFinite(declared) && declared > limit) {
    stream.resume();
    throw new AppError('FILE_TOO_LARGE');
  }
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of stream) {
    const buffer = chunk as Buffer;
    size += buffer.length;
    if (size > limit) {
      stream.destroy();
      throw new AppError('FILE_TOO_LARGE');
    }
    chunks.push(buffer);
  }
  if (size === 0) {
    throw new AppError('VALIDATION_FAILED', { message: 'The page image is empty.' });
  }
  return Buffer.concat(chunks, size);
}
