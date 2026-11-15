import { Module, type DynamicModule } from '@nestjs/common';
import type { WorkerConfig } from '@scanova/config/sections';
import { JobRunner } from './job-runner.js';
import { OcrService } from './ocr/ocr.service.js';
import { PlatformModule, type PlatformOverrides } from './platform/platform.module.js';
import { PurgeService } from './purge/purge.service.js';
import { RotationService } from './rotation/rotation.service.js';

/** The worker: a NestJS standalone application (no HTTP server). */
@Module({})
export class WorkerModule {
  public static register(config: WorkerConfig, overrides: PlatformOverrides = {}): DynamicModule {
    return {
      module: WorkerModule,
      imports: [PlatformModule.forRoot(config, overrides)],
      providers: [RotationService, OcrService, PurgeService, JobRunner],
      exports: [RotationService, OcrService, PurgeService, JobRunner],
    };
  }
}
