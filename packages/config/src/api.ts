import { parseAiConfig, parseAiImageConfig } from './ai.js';
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
import { envBoolean, envInt, envPort, envSecret, envString, parseEnvWith } from './parse.js';
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
  SIGNUP_ENABLED: envBoolean().default(false),
  SIGNUP_QUOTA_BYTES: envInt(1).default(2_000_000_000),
  SIGNUP_MAX_PER_IP_PER_HOUR: envInt(1, 1000).default(3),
  SIGNUP_MAX_PER_HOUR: envInt(1, 100_000).default(20),
  BAO_API_ROLE_ID: envString,
  BAO_API_SECRET_ID: envString,
  // Memory the server keeps free for its other services when an owner loads the AI (§11.8).
  AI_KEEP_FREE_MB: envInt(0, 1_048_576).default(2048),
};

/** Validates everything the API process needs, and nothing it does not. */
export function parseApiConfig(source: EnvSource): ApiConfig {
  const env = parseEnvWith('api', apiShape, source);
  // The API loads and unloads the models for an owner (§11.8), so it needs the runtime too.
  const ai = parseAiConfig('api', source);
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
    features: { serverOcr: true, ai: ai.provider !== 'none', signup: env.SIGNUP_ENABLED },
    signup: {
      enabled: env.SIGNUP_ENABLED,
      quotaBytes: env.SIGNUP_QUOTA_BYTES,
      maxPerIpPerHour: env.SIGNUP_MAX_PER_IP_PER_HOUR,
      maxPerHour: env.SIGNUP_MAX_PER_HOUR,
    },
    database: parseDatabaseConfig(source),
    keystore: parseKeystoreConfig(source),
    storage: toStorage(env),
    kms: toKms(source, env, { roleId: env.BAO_API_ROLE_ID, secretId: env.BAO_API_SECRET_ID }),
    keys: toKeyNames(env),
    rotation: toRotation(env),
    ai,
    aiImage: parseAiImageConfig('api', source),
    aiControl: { keepFreeBytes: env.AI_KEEP_FREE_MB * 1024 * 1024 },
  };
}

export function loadApiConfig(options?: EnvSourceOptions): ApiConfig {
  return parseApiConfig(readEnvSource(options));
}
