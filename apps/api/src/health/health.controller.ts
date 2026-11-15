import { Controller, Get } from '@nestjs/common';
import type { Health, Readiness } from '@scanova/contracts/system';
import { AppError } from '../common/app-error.js';
import { Public } from '../common/decorators.js';
import { HealthService } from './health.service.js';

@Controller('api/v1/health')
@Public()
export class HealthController {
  public constructor(private readonly health: HealthService) {}

  /** Liveness: the process is up. Used by the container healthcheck. */
  @Get()
  public live(): Health {
    return { status: 'ok' };
  }

  /** Readiness: database, object storage and vault all answer. Otherwise 503 with the failing checks. */
  @Get('ready')
  public async ready(): Promise<Readiness> {
    const checks = await this.health.checks();
    if (Object.values(checks).some((state) => state !== 'ok')) {
      throw new AppError('SERVICE_UNAVAILABLE', { details: { checks } });
    }
    return { status: 'ok', checks };
  }
}
