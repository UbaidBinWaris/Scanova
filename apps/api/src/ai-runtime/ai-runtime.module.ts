import { Module } from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import { API_CONFIG } from '../platform/tokens.js';
import { UsersModule } from '../users/users.module.js';
import { AiRuntimeController } from './ai-runtime.controller.js';
import {
  AI_MODEL_RUNTIMES,
  AiRuntimeService,
  IMAGE_ENHANCER,
  SERVER_MEMORY,
  type ControlledModels,
} from './ai-runtime.service.js';
import { ImageEnhancer, LlamaRouterRuntime } from './model-runtime.js';
import { ProcMeminfo } from './server-memory.js';

/** Builds each switch's models from the configuration; none for a switch that is off there. */
export function controlledModels(config: ApiConfig): ControlledModels {
  return {
    text:
      config.ai.provider === 'none'
        ? []
        : [
            {
              role: 'chat',
              name: config.ai.chat.model,
              runtime: new LlamaRouterRuntime(config.ai.chat.baseUrl, config.ai.apiKey),
            },
            {
              role: 'embed',
              name: config.ai.embeddings.model,
              runtime: new LlamaRouterRuntime(config.ai.embeddings.baseUrl, config.ai.apiKey),
            },
          ],
    // The image runtime speaks the same router protocol (apps/ai-image/server.py).
    image:
      config.aiImage.provider === 'none'
        ? []
        : [
            {
              role: 'image',
              name: config.aiImage.model,
              runtime: new LlamaRouterRuntime(config.aiImage.baseUrl, config.aiImage.apiKey),
            },
          ],
  };
}

export function imageEnhancer(config: ApiConfig): ImageEnhancer | null {
  return config.aiImage.provider === 'none'
    ? null
    : new ImageEnhancer(config.aiImage.baseUrl, config.aiImage.apiKey);
}

@Module({
  imports: [UsersModule],
  controllers: [AiRuntimeController],
  providers: [
    AiRuntimeService,
    { provide: AI_MODEL_RUNTIMES, inject: [API_CONFIG], useFactory: controlledModels },
    { provide: IMAGE_ENHANCER, inject: [API_CONFIG], useFactory: imageEnhancer },
    { provide: SERVER_MEMORY, useValue: new ProcMeminfo() },
  ],
  exports: [AiRuntimeService],
})
export class AiRuntimeModule {}
