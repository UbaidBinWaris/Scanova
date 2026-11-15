import { z } from 'zod';
import { resolveFromBase, type EnvSource } from './env-source.js';
import { envBoolean, envInt, envPort, envSecret, envString } from './parse.js';
import { buildPostgresConfig } from './postgres.js';
import type {
  KeyNamesConfig,
  KmsConfig,
  PostgresConfig,
  RotationConfig,
  RuntimeConfig,
  StorageConfig,
} from './sections.js';

/**
 * Variable groups shared by several loaders. Each loader spreads only the groups
 * its process needs, so the worker never has to be given the API's secrets.
 * Non-secret tunables default to the values documented in `.env.example`;
 * addresses and credentials are always required.
 */

export const runtimeShape = {
  NODE_ENV: z.enum(['production', 'development', 'test']).default('production'),
  LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace']).default('info'),
};

export const caShape = {
  SCANOVA_CA_CERT_PATH: envString,
};

export const mainDatabaseShape = {
  POSTGRES_HOST: envString,
  POSTGRES_PORT: envPort,
  POSTGRES_DB: envString,
};

export const mainAppRoleShape = {
  POSTGRES_APP_USER: envString,
  POSTGRES_APP_PASSWORD: envSecret(8),
};

export const mainMigratorRoleShape = {
  POSTGRES_MIGRATOR_USER: envString,
  POSTGRES_MIGRATOR_PASSWORD: envSecret(8),
};

export const keyDatabaseShape = {
  KEYS_POSTGRES_HOST: envString,
  KEYS_POSTGRES_PORT: envPort,
  KEYS_POSTGRES_DB: envString,
};

export const keyAppRoleShape = {
  KEYS_POSTGRES_APP_USER: envString,
  KEYS_POSTGRES_APP_PASSWORD: envSecret(8),
};

export const keyMigratorRoleShape = {
  KEYS_POSTGRES_MIGRATOR_USER: envString,
  KEYS_POSTGRES_MIGRATOR_PASSWORD: envSecret(8),
};

export const storageShape = {
  S3_ENDPOINT: z.url(),
  S3_REGION: envString,
  S3_BUCKET: envString,
  S3_FORCE_PATH_STYLE: envBoolean().default(true),
  S3_ACCESS_KEY_ID: envString,
  S3_SECRET_ACCESS_KEY: envSecret(16),
};

export const kmsShape = {
  BAO_ADDR: z.url(),
  BAO_TRANSIT_MOUNT: envString.default('transit'),
};

export const keyNamesShape = {
  BAO_KEY_UTK_ADMIN: envString.default('scanova-utk-admin'),
  BAO_KEY_UTK_MEMBER: envString.default('scanova-utk-member'),
  BAO_KEY_SYSTEM: envString.default('scanova-sys'),
  BAO_KEY_JWT: envString.default('scanova-jwt'),
};

export const rotationShape = {
  KEY_ROTATION_UTK_DAYS: envInt(1, 3650).default(90),
  KEY_ROTATION_USER_KEY_DAYS: envInt(1, 3650).default(180),
  KEY_ROTATION_JWT_DAYS: envInt(1, 3650).default(30),
  KEY_REWRAP_BATCH_SIZE: envInt(1, 10_000).default(200),
};

export function toRuntime(env: {
  NODE_ENV: RuntimeConfig['nodeEnv'];
  LOG_LEVEL: RuntimeConfig['logLevel'];
}): RuntimeConfig {
  return { nodeEnv: env.NODE_ENV, logLevel: env.LOG_LEVEL };
}

interface PostgresEnv {
  readonly host: string;
  readonly port: number;
  readonly database: string;
  readonly user: string;
  readonly password: string;
  readonly caCertPath: string;
}

/** PostgreSQL always requires TLS outside tests (§2, trust boundary 3). */
export function toPostgres(source: EnvSource, env: PostgresEnv): PostgresConfig {
  return buildPostgresConfig({
    host: env.host,
    port: env.port,
    database: env.database,
    user: env.user,
    password: env.password,
    tls: { caCertPath: resolveFromBase(source, env.caCertPath) },
  });
}

export function toStorage(env: {
  S3_ENDPOINT: string;
  S3_REGION: string;
  S3_BUCKET: string;
  S3_FORCE_PATH_STYLE: boolean;
  S3_ACCESS_KEY_ID: string;
  S3_SECRET_ACCESS_KEY: string;
}): StorageConfig {
  return {
    endpoint: env.S3_ENDPOINT,
    region: env.S3_REGION,
    bucket: env.S3_BUCKET,
    forcePathStyle: env.S3_FORCE_PATH_STYLE,
    accessKeyId: env.S3_ACCESS_KEY_ID,
    secretAccessKey: env.S3_SECRET_ACCESS_KEY,
  };
}

export function toKms(
  source: EnvSource,
  env: { BAO_ADDR: string; BAO_TRANSIT_MOUNT: string; SCANOVA_CA_CERT_PATH: string },
  credentials: { roleId: string; secretId: string },
): KmsConfig {
  const isTls = new URL(env.BAO_ADDR).protocol === 'https:';
  return {
    address: env.BAO_ADDR.replace(/\/+$/u, ''),
    transitMount: env.BAO_TRANSIT_MOUNT,
    caCertPath: isTls ? resolveFromBase(source, env.SCANOVA_CA_CERT_PATH) : null,
    roleId: credentials.roleId,
    secretId: credentials.secretId,
  };
}

export function toKeyNames(env: {
  BAO_KEY_UTK_ADMIN: string;
  BAO_KEY_UTK_MEMBER: string;
  BAO_KEY_SYSTEM: string;
  BAO_KEY_JWT: string;
}): KeyNamesConfig {
  return {
    userTypeAdmin: env.BAO_KEY_UTK_ADMIN,
    userTypeMember: env.BAO_KEY_UTK_MEMBER,
    system: env.BAO_KEY_SYSTEM,
    jwt: env.BAO_KEY_JWT,
  };
}

export function toRotation(env: {
  KEY_ROTATION_UTK_DAYS: number;
  KEY_ROTATION_USER_KEY_DAYS: number;
  KEY_ROTATION_JWT_DAYS: number;
  KEY_REWRAP_BATCH_SIZE: number;
}): RotationConfig {
  return {
    userTypeKeyDays: env.KEY_ROTATION_UTK_DAYS,
    userKeyDays: env.KEY_ROTATION_USER_KEY_DAYS,
    jwtKeyDays: env.KEY_ROTATION_JWT_DAYS,
    rewrapBatchSize: env.KEY_REWRAP_BATCH_SIZE,
  };
}
