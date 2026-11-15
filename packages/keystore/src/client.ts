import { PrismaPg } from '@prisma/adapter-pg';
import { toPgConnectionOptions } from '@scanova/config/postgres';
import type { PostgresConfig } from '@scanova/config/sections';
import { PrismaClient } from './generated/prisma/client.js';

export { Prisma, PrismaClient } from './generated/prisma/client.js';
export {
  DataKeyScope,
  RotationScope,
  RotationState,
  RotationTrigger,
  SystemKeyPurpose,
  SystemKeyState,
  UserKeyState,
} from './generated/prisma/enums.js';
export type { DataKey, KeyRotationRun, SystemKey, UserKey } from './generated/prisma/client.js';

/** Client for the key database. Only @scanova/keyring may hold one. */
export type KeystoreClient = PrismaClient;

/** The client inside an interactive transaction. */
export type KeystoreTransaction = Parameters<Parameters<PrismaClient['$transaction']>[0]>[0];

export interface KeystoreClientOptions {
  /** Upper bound of pooled connections. */
  readonly maxConnections?: number;
}

/**
 * Creates a client for the key database from a typed config object (never from
 * the environment). The caller owns it and must `$disconnect()` it on shutdown.
 */
export function createKeystoreClient(
  config: PostgresConfig,
  options: KeystoreClientOptions = {},
): KeystoreClient {
  const adapter = new PrismaPg({
    ...toPgConnectionOptions(config),
    max: options.maxConnections ?? 5,
    application_name: 'scanova-keys',
  });
  return new PrismaClient({ adapter });
}
