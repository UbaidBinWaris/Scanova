import { Module, type DynamicModule } from '@nestjs/common';
import { APP_FILTER, APP_INTERCEPTOR } from '@nestjs/core';
import type { ApiConfig } from '@scanova/config/sections';
import { AiRuntimeModule } from './ai-runtime/ai-runtime.module.js';
import { AuditModule } from './audit/audit.module.js';
import { AuthModule } from './auth/auth.module.js';
import { EnvelopeInterceptor } from './common/envelope.interceptor.js';
import { ErrorFilter } from './common/error.filter.js';
import { DocumentsModule } from './documents/documents.module.js';
import { HealthModule } from './health/health.module.js';
import { AdminModule } from './internal/admin/admin.module.js';
import { WebModule } from './internal/web/web.module.js';
import { MeModule } from './me/me.module.js';
import { AppLogger } from '@scanova/config/logger';
import { PlatformModule, type PlatformOverrides } from './platform/platform.module.js';
import { ServerInfoModule } from './server-info/server-info.module.js';

/** Feature modules, one per area of the API contract (§7). */
@Module({})
export class AppModule {
  public static register(config: ApiConfig, overrides: PlatformOverrides = {}): DynamicModule {
    return {
      module: AppModule,
      imports: [
        PlatformModule.forRoot(config, overrides),
        AuditModule,
        AuthModule,
        MeModule,
        DocumentsModule,
        HealthModule,
        ServerInfoModule,
        AiRuntimeModule,
        WebModule,
        AdminModule,
      ],
      providers: [
        // One envelope and one error mapping for every route.
        { provide: APP_INTERCEPTOR, useClass: EnvelopeInterceptor },
        {
          provide: APP_FILTER,
          inject: [AppLogger],
          useFactory: (logger: AppLogger): ErrorFilter => new ErrorFilter(logger),
        },
      ],
    };
  }
}
