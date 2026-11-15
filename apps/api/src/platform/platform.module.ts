import {
  Global,
  Inject,
  Injectable,
  Module,
  type DynamicModule,
  type OnApplicationShutdown,
} from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import { createDatabaseClient, type DatabaseClient } from '@scanova/database/client';
import { JobQueue } from '@scanova/jobs/queue';
import { createKeyring, type Keyring } from '@scanova/keyring/keyring';
import type { Kms } from '@scanova/kms/kms';
import { OpenBaoKms } from '@scanova/kms/openbao';
import { EncryptedObjectStore } from '@scanova/storage/encrypted';
import type { ObjectStore } from '@scanova/storage/object-store';
import { S3ObjectStore } from '@scanova/storage/s3';
import { AppLogger } from '@scanova/config/logger';
import {
  API_CONFIG,
  CLOCK,
  DATABASE,
  ENCRYPTED_STORE,
  JOBS,
  KEYRING,
  KMS,
  OBJECT_STORE,
  type Clock,
} from './tokens.js';

/** Replacements for tests. Production passes none. */
export interface PlatformOverrides {
  readonly kms?: Kms;
  readonly objectStore?: ObjectStore;
  readonly clock?: Clock;
  readonly logger?: AppLogger;
}

/** Closes every connection the platform opened, in dependency order. */
@Injectable()
class PlatformLifecycle implements OnApplicationShutdown {
  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    @Inject(KMS) private readonly kms: Kms,
    @Inject(KEYRING) private readonly keyring: Keyring,
    @Inject(OBJECT_STORE) private readonly objectStore: ObjectStore,
    @Inject(JOBS) private readonly jobs: JobQueue,
  ) {}

  public async onApplicationShutdown(): Promise<void> {
    await this.jobs.stop();
    // Wipes every cached key before the process goes away.
    await this.keyring.close();
    await this.kms.close();
    this.objectStore.close();
    await this.database.$disconnect();
  }
}

/**
 * The infrastructure every feature needs: configuration, both databases (the
 * key database only through the keyring), the vault, object storage and the job
 * queue. Built from a typed config object, never from the environment.
 */
@Global()
@Module({})
export class PlatformModule {
  public static forRoot(config: ApiConfig, overrides: PlatformOverrides = {}): DynamicModule {
    const logger = overrides.logger ?? new AppLogger(config.runtime.logLevel, 'api');
    return {
      module: PlatformModule,
      providers: [
        { provide: API_CONFIG, useValue: config },
        { provide: AppLogger, useValue: logger },
        { provide: CLOCK, useValue: overrides.clock ?? ((): Date => new Date()) },
        {
          provide: DATABASE,
          useFactory: (): DatabaseClient => createDatabaseClient(config.database),
        },
        { provide: KMS, useFactory: (): Kms => overrides.kms ?? new OpenBaoKms(config.kms) },
        {
          provide: KEYRING,
          inject: [KMS, CLOCK],
          useFactory: (kms: Kms, clock: Clock): Keyring =>
            createKeyring({
              kms,
              keystore: config.keystore,
              settings: {
                keys: config.keys,
                rotation: config.rotation,
                accessTokenTtlSeconds: config.auth.accessTokenTtlSeconds,
                now: clock,
              },
            }),
        },
        {
          provide: OBJECT_STORE,
          useFactory: (): ObjectStore => overrides.objectStore ?? new S3ObjectStore(config.storage),
        },
        {
          provide: ENCRYPTED_STORE,
          inject: [OBJECT_STORE],
          useFactory: (store: ObjectStore): EncryptedObjectStore => new EncryptedObjectStore(store),
        },
        {
          provide: JOBS,
          useFactory: async (): Promise<JobQueue> => {
            const queue = new JobQueue(config.database, {
              role: 'producer',
              onError: (error) => {
                logger.failure('jobs.error', error);
              },
            });
            await queue.start();
            return queue;
          },
        },
        PlatformLifecycle,
      ],
      exports: [
        API_CONFIG,
        AppLogger,
        CLOCK,
        DATABASE,
        KMS,
        KEYRING,
        OBJECT_STORE,
        ENCRYPTED_STORE,
        JOBS,
      ],
    };
  }
}
