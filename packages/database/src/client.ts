import { PrismaPg } from '@prisma/adapter-pg';
import { toPgConnectionOptions } from '@scanova/config/postgres';
import type { PostgresConfig } from '@scanova/config/sections';
import { PrismaClient } from './generated/prisma/client.js';

export { Prisma, PrismaClient } from './generated/prisma/client.js';
export {
  DocumentStatus,
  FileVariant,
  OcrStatus,
  SessionClient,
  TextSource,
  UserStatus,
  UserType,
} from './generated/prisma/enums.js';
export type {
  AuditEvent,
  Document,
  DocumentText,
  FileObject,
  SearchToken,
  Session,
  SpentRefreshToken,
  User,
} from './generated/prisma/client.js';

/** Client for the main database. Holds ciphertext and metadata; never a key. */
export type DatabaseClient = PrismaClient;

/** The client inside an interactive transaction. */
export type DatabaseTransaction = Parameters<Parameters<PrismaClient['$transaction']>[0]>[0];

export interface DatabaseClientOptions {
  /** Upper bound of pooled connections. */
  readonly maxConnections?: number;
}

/**
 * Creates a client for the main database from a typed config object (never from
 * the environment). The caller owns it and must `$disconnect()` it on shutdown.
 */
export function createDatabaseClient(
  config: PostgresConfig,
  options: DatabaseClientOptions = {},
): DatabaseClient {
  const adapter = new PrismaPg({
    ...toPgConnectionOptions(config),
    max: options.maxConnections ?? 10,
    application_name: 'scanova',
  });
  return new PrismaClient({ adapter });
}
