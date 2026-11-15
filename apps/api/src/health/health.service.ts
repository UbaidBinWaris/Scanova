import { Inject, Injectable } from '@nestjs/common';
import type { DependencyState, ReadinessChecks } from '@scanova/contracts/system';
import type { DatabaseClient } from '@scanova/database/client';
import type { Keyring } from '@scanova/keyring/keyring';
import type { Kms } from '@scanova/kms/kms';
import type { ObjectStore } from '@scanova/storage/object-store';
import { AppLogger } from '@scanova/config/logger';
import { DATABASE, KEYRING, KMS, OBJECT_STORE } from '../platform/tokens.js';

const CHECK_TIMEOUT_MS = 3000;

@Injectable()
export class HealthService {
  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    @Inject(KEYRING) private readonly keyring: Keyring,
    @Inject(KMS) private readonly kms: Kms,
    @Inject(OBJECT_STORE) private readonly objectStore: ObjectStore,
    private readonly logger: AppLogger,
  ) {}

  /** Probes every dependency in parallel, each with its own deadline. */
  public async checks(): Promise<ReadinessChecks> {
    const [database, storage, kms] = await Promise.all([
      // "database" covers both databases: the main one and the key database.
      this.probe('database', async () => {
        await this.database.$queryRaw`SELECT 1`;
        await this.keyring.health();
      }),
      this.probe('storage', () => this.objectStore.health()),
      this.probe('kms', () => this.kms.health()),
    ]);
    return { database, storage, kms };
  }

  private async probe(name: string, check: () => Promise<unknown>): Promise<DependencyState> {
    let timer: NodeJS.Timeout | undefined;
    try {
      await Promise.race([
        check(),
        new Promise<never>((_resolve, reject) => {
          timer = setTimeout(() => {
            reject(new Error('timeout'));
          }, CHECK_TIMEOUT_MS);
        }),
      ]);
      return 'ok';
    } catch (error) {
      this.logger.failure('health.dependency_unavailable', error, { dependency: name });
      return 'unavailable';
    } finally {
      clearTimeout(timer);
    }
  }
}
