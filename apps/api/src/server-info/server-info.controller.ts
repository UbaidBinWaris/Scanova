import { Controller, Get, Inject } from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import { API_VERSION, SERVER_NAME, type ServerInfo } from '@scanova/contracts/system';
import { Public } from '../common/decorators.js';
import { API_CONFIG } from '../platform/tokens.js';
import { SERVER_VERSION } from '../version.js';

/** What a client needs to know before signing in (shown by the phone during pairing). */
@Controller('api/v1/server-info')
@Public()
export class ServerInfoController {
  public constructor(@Inject(API_CONFIG) private readonly config: ApiConfig) {}

  @Get()
  public get(): ServerInfo {
    return {
      name: SERVER_NAME,
      apiVersion: API_VERSION,
      serverVersion: SERVER_VERSION,
      features: { serverOcr: this.config.features.serverOcr, ai: this.config.features.ai },
    };
  }
}
