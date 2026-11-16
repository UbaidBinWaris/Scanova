import { parseAiConfig } from './ai.js';
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
import { envInt, envString, parseEnvWith } from './parse.js';
import type { WorkerConfig } from './sections.js';

const workerShape = {
  ...runtimeShape,
  ...caShape,
  ...storageShape,
  ...kmsShape,
  ...keyNamesShape,
  ...rotationShape,
  TRASH_RETENTION_DAYS: envInt(0, 3650).default(30),
  ACCESS_TOKEN_TTL_SECONDS: envInt(30, 86_400).default(600),
  WORKER_OCR_CONCURRENCY: envInt(1, 16).default(1),
  // Passed to Tesseract as one argument; restricted to language-code characters.
  // The worker image installs English only, so `eng` is the one useful value.
  OCR_LANGUAGES: envString
    .regex(/^[A-Za-z0-9_]+(\+[A-Za-z0-9_]+)*$/u, 'must be Tesseract language codes, like "eng"')
    .default('eng'),
  SCANOVA_TMP_DIR: envString.refine((value) => value.startsWith('/'), 'must be an absolute path'),
  BAO_WORKER_ROLE_ID: envString,
  BAO_WORKER_SECRET_ID: envString,
};

/** Validates the worker's subset. It never needs the web service token or Argon2 settings. */
export function parseWorkerConfig(source: EnvSource): WorkerConfig {
  const env = parseEnvWith('worker', workerShape, source);
  return {
    runtime: toRuntime(env),
    limits: { trashRetentionDays: env.TRASH_RETENTION_DAYS },
    auth: { accessTokenTtlSeconds: env.ACCESS_TOKEN_TTL_SECONDS },
    ocr: {
      concurrency: env.WORKER_OCR_CONCURRENCY,
      languages: env.OCR_LANGUAGES,
      tmpDir: env.SCANOVA_TMP_DIR,
    },
    database: parseDatabaseConfig(source),
    keystore: parseKeystoreConfig(source),
    storage: toStorage(env),
    kms: toKms(source, env, {
      roleId: env.BAO_WORKER_ROLE_ID,
      secretId: env.BAO_WORKER_SECRET_ID,
    }),
    keys: toKeyNames(env),
    rotation: toRotation(env),
    ai: parseAiConfig('worker', source),
  };
}

export function loadWorkerConfig(options?: EnvSourceOptions): WorkerConfig {
  return parseWorkerConfig(readEnvSource(options));
}
