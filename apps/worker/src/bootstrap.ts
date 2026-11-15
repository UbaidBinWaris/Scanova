import 'reflect-metadata';
import type { INestApplicationContext } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { AppLogger } from '@scanova/config/logger';
import type { WorkerConfig } from '@scanova/config/sections';
import type { PlatformOverrides } from './platform/platform.module.js';
import { WorkerModule } from './worker.module.js';

/** Builds the worker from a typed config object. Used by `main.ts` and by the integration tests. */
export async function createWorker(
  config: WorkerConfig,
  overrides: PlatformOverrides = {},
): Promise<INestApplicationContext> {
  const logger = overrides.logger ?? new AppLogger(config.runtime.logLevel, 'worker');
  const app = await NestFactory.createApplicationContext(
    WorkerModule.register(config, { ...overrides, logger }),
    {
      logger,
    },
  );
  app.enableShutdownHooks();
  return app;
}
