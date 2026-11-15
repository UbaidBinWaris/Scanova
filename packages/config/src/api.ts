import { z } from 'zod';
import { parseDatabaseConfig } from './database.js';
import { readEnvSource, type EnvSource, type EnvSourceOptions } from './env-source.js';
import {
  caShape,
  keyNamesShape,
  kmsShape,
  rotationShape,
  runtimeShape,
  storageShape,
  toKeyNames,
  toKms,
  toRotation,
  toRuntime,
  toStorage,
} from './fragments.js';
import { parseKeystoreConfig } from './keystore.js';
import { envInt, envPort, envSecret, envString, parseEnvWith } from './parse.js';
import type { ApiConfig } from './sections.js';

const apiShape = {
  ...runtimeShape,
  ...caShape,
  ...storageShape,
  ...kmsShape,
  ...keyNamesShape,
  ...rotationShape,
  API_PORT: envPort.default(3000),
  MAX_UPLOAD_BYTES: envInt(1).default(104_857_600),
  USER_DEFAULT_QUOTA_BYTES: envInt(1).default(10_737_418_240),
  TRASH_RETENTION_DAYS: envInt(0, 3650).default(30),
  WEB_SERVICE_TOKEN: envSecret(32),
  JWT_ISSUER: envString.default('scanova'),
  ACCESS_TOKEN_TTL_SECONDS: envInt(30, 86_400).default(600),
  REFRESH_TOKEN_TTL_DAYS: envInt(1, 3650).default(30),
  // Lower bounds follow the OWASP minimum for Argon2id (19 MiB, t=2, p=1).
  ARGON2_MEMORY_KIB: envInt(19_456, 4_194_304).default(65_536),
  ARGON2_TIME_COST: envInt(2, 64).default(3),
  ARGON2_PARALLELISM: envInt(1, 64).default(1),
  LOGIN_MAX_ATTEMPTS_PER_MINUTE: envInt(1, 1000).default(5),
  BAO_API_ROLE_ID: envString,
  BAO_API_SECRET_ID: envString,
  AI_PROVIDER: z.enum(['none', 'ollama']).default('none'),
};

/** Validates everything the API process needs, and nothing it does not. */
export function parseApiConfig(source: EnvSource): ApiConfig {
  const env = parseEnvWith('api', apiShape, source);
  return {
    runtime: toRuntime(env),
    http: { port: env.API_PORT, maxUploadBytes: env.MAX_UPLOAD_BYTES },
    auth: {
      jwtIssuer: env.JWT_ISSUER,
      accessTokenTtlSeconds: env.ACCESS_TOKEN_TTL_SECONDS,
      refreshTokenTtlDays: env.REFRESH_TOKEN_TTL_DAYS,
      argon2: {
        memoryKib: env.ARGON2_MEMORY_KIB,
        timeCost: env.ARGON2_TIME_COST,
        parallelism: env.ARGON2_PARALLELISM,
      },
      loginMaxAttemptsPerMinute: env.LOGIN_MAX_ATTEMPTS_PER_MINUTE,
      webServiceToken: env.WEB_SERVICE_TOKEN,
    },
    limits: {
      userDefaultQuotaBytes: env.USER_DEFAULT_QUOTA_BYTES,
      trashRetentionDays: env.TRASH_RETENTION_DAYS,
    },
    features: { serverOcr: true, ai: env.AI_PROVIDER !== 'none' },
    database: parseDatabaseConfig(source),
    keystore: parseKeystoreConfig(source),
    storage: toStorage(env),
    kms: toKms(source, env, { roleId: env.BAO_API_ROLE_ID, secretId: env.BAO_API_SECRET_ID }),
    keys: toKeyNames(env),
    rotation: toRotation(env),
  };
}

export function loadApiConfig(options?: EnvSourceOptions): ApiConfig {
  return parseApiConfig(readEnvSource(options));
}
