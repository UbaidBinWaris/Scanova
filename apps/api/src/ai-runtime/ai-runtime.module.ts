import { Module } from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import { API_CONFIG } from '../platform/tokens.js';
import { UsersModule } from '../users/users.module.js';
import { AiRuntimeController } from './ai-runtime.controller.js';
import {
  AI_MODEL_RUNTIMES,
  AiRuntimeService,
  SERVER_MEMORY,
  type ControlledModel,
} from './ai-runtime.service.js';
import { LlamaRouterRuntime } from './model-runtime.js';
import { ProcMeminfo } from './server-memory.js';

/** Builds the two controlled models from the configuration; none while AI is off. */
export function controlledModels(config: ApiConfig): ControlledModel[] {
  if (config.ai.provider === 'none') {
    return [];
  }
  return [
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
  ];
}

@Module({
  imports: [UsersModule],
  controllers: [AiRuntimeController],
  providers: [
    AiRuntimeService,
    { provide: AI_MODEL_RUNTIMES, inject: [API_CONFIG], useFactory: controlledModels },
    { provide: SERVER_MEMORY, useValue: new ProcMeminfo() },
  ],
  exports: [AiRuntimeService],
})
export class AiRuntimeModule {}
