import {
  Global,
  Inject,
  Injectable,
  Module,
  type DynamicModule,
  type OnApplicationShutdown,
} from '@nestjs/common';
import { AppLogger } from '@scanova/config/logger';
import type { WorkerConfig } from '@scanova/config/sections';
import { createDatabaseClient, type DatabaseClient } from '@scanova/database/client';
import { JobQueue } from '@scanova/jobs/queue';
import { createKeyring, type Keyring, type KeyringDependencies } from '@scanova/keyring/keyring';
import type { Kms } from '@scanova/kms/kms';
import { OpenBaoKms } from '@scanova/kms/openbao';
import { EncryptedObjectStore } from '@scanova/storage/encrypted';
import type { ObjectStore } from '@scanova/storage/object-store';
import { S3ObjectStore } from '@scanova/storage/s3';
import { OcrmypdfEngine, type OcrEngine } from '../ocr/ocr-engine.js';
import {
  CLOCK,
  DATABASE,
  ENCRYPTED_STORE,
  JOBS,
  KEYRING,
  KMS,
  OBJECT_STORE,
  OCR_ENGINE,
  WORKER_CONFIG,
  type Clock,
} from './tokens.js';

/** Replacements for tests. Production passes none. */
export interface PlatformOverrides {
  readonly kms?: Kms;
  readonly objectStore?: ObjectStore;
  readonly clock?: Clock;
  readonly logger?: AppLogger;
  readonly ocrEngine?: OcrEngine;
  /** Extra keyring settings (tests shorten the waiting periods). */
  readonly keyring?: Partial<KeyringDependencies['settings']>;
}

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
    // Lets running jobs finish first, then wipes cached keys and closes connections.
    await this.jobs.stop();
    await this.keyring.close();
    await this.kms.close();
    this.objectStore.close();
    await this.database.$disconnect();
  }
}

/**
 * The worker's infrastructure, built from a typed config object. It signs in to
 * the vault with the worker's AppRole, which may rotate keys and raise
 * `min_decryption_version` — the API's may not.
 */
@Global()
@Module({})
export class PlatformModule {
  public static forRoot(config: WorkerConfig, overrides: PlatformOverrides = {}): DynamicModule {
    const logger = overrides.logger ?? new AppLogger(config.runtime.logLevel, 'worker');
    return {
      module: PlatformModule,
      providers: [
        { provide: WORKER_CONFIG, useValue: config },
        { provide: AppLogger, useValue: logger },
        { provide: CLOCK, useValue: overrides.clock ?? ((): Date => new Date()) },
        {
          provide: DATABASE,
          useFactory: (): DatabaseClient =>
            createDatabaseClient(config.database, { maxConnections: 5 }),
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
                ...overrides.keyring,
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
          provide: OCR_ENGINE,
          useFactory: (): OcrEngine => overrides.ocrEngine ?? new OcrmypdfEngine(),
        },
        {
          provide: JOBS,
          useFactory: async (): Promise<JobQueue> => {
            const queue = new JobQueue(config.database, {
              role: 'worker',
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
        WORKER_CONFIG,
        AppLogger,
        CLOCK,
        DATABASE,
        KMS,
        KEYRING,
        OBJECT_STORE,
        ENCRYPTED_STORE,
        JOBS,
        OCR_ENGINE,
      ],
    };
  }
}
