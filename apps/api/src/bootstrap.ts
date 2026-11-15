import 'reflect-metadata';
import { randomUUID } from 'node:crypto';
import { NestFactory } from '@nestjs/core';
import { FastifyAdapter, type NestFastifyApplication } from '@nestjs/platform-fastify';
import type { ApiConfig } from '@scanova/config/sections';
import { MAX_PAGE_LIST_BODY_BYTES } from '@scanova/contracts/documents';
import { AppModule } from './app.module.js';
import { AppLogger } from '@scanova/config/logger';
import type { PlatformOverrides } from './platform/platform.module.js';

/**
 * JSON bodies are small; the largest is a document with 1 MiB of client OCR text.
 * The one exception is the page list (`MAX_PAGE_LIST_BODY_BYTES`).
 */
const JSON_BODY_LIMIT_BYTES = 2 * 1024 * 1024;
const PAGE_LIST_ROUTE = /^\/api\/v1\/documents\/:id\/pages\/?$/u;

/**
 * Builds the HTTP application from a typed config object. Used by `main.ts` and,
 * with the same wiring, by the integration tests.
 *
 * Fastify was chosen for the streaming upload: a content-type parser for
 * `application/pdf` hands the request body to the route unread, as a stream,
 * instead of collecting it into memory first.
 */
export async function createApp(
  config: ApiConfig,
  overrides: PlatformOverrides = {},
): Promise<NestFastifyApplication> {
  const logger = overrides.logger ?? new AppLogger(config.runtime.logLevel, 'api');
  const adapter = new FastifyAdapter({
    // The API is only reachable through the edge proxy (and the web app), which set X-Forwarded-For.
    trustProxy: true,
    bodyLimit: JSON_BODY_LIMIT_BYTES,
    logger: false,
    genReqId: () => randomUUID(),
    // A slow or stalled client must not hold a connection forever.
    connectionTimeout: 120_000,
    requestTimeout: 30 * 60_000,
  });
  const fastify = adapter.getInstance();

  // PDF and page-original uploads: do not read the body. The upload service consumes
  // `request.raw` as a stream, so memory use does not depend on the file size.
  for (const streamed of ['application/pdf', 'image/jpeg']) {
    fastify.addContentTypeParser(streamed, (_request, payload, done) => {
      done(null, payload);
    });
  }

  // A page list carries one recipe per page, so it may be larger than other JSON bodies.
  fastify.addHook('onRoute', (route) => {
    const methods = Array.isArray(route.method) ? route.method : [route.method];
    if (methods.includes('PUT') && PAGE_LIST_ROUTE.test(route.url)) {
      route.bodyLimit = MAX_PAGE_LIST_BODY_BYTES;
    }
  });

  fastify.addHook('onResponse', (request, reply, done) => {
    // Route pattern, not the URL: ids and query strings stay out of the log.
    logger.info('request.completed', {
      requestId: request.id,
      method: request.method,
      route: request.routeOptions.url ?? 'unmatched',
      status: reply.statusCode,
      durationMs: Math.round(reply.elapsedTime),
      userId: request.auth?.userPublicId,
    });
    done();
  });

  const app = await NestFactory.create<NestFastifyApplication>(
    AppModule.register(config, { ...overrides, logger }),
    adapter,
    { logger },
  );
  app.enableShutdownHooks();
  await app.init();
  await fastify.ready();
  return app;
}
