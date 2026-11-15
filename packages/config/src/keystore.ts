import { readEnvSource, type EnvSource, type EnvSourceOptions } from './env-source.js';
import {
  caShape,
  keyAppRoleShape,
  keyDatabaseShape,
  keyMigratorRoleShape,
  toPostgres,
} from './fragments.js';
import { parseEnvWith } from './parse.js';
import type { PostgresConfig } from './sections.js';

/**
 * Key database, runtime role. Its host and port are independent of the main
 * database, so the keys can move to their own instance by editing `.env` only.
 */
export function parseKeystoreConfig(source: EnvSource): PostgresConfig {
  const env = parseEnvWith(
    'keystore',
    { ...keyDatabaseShape, ...keyAppRoleShape, ...caShape },
    source,
  );
  return toPostgres(source, {
    host: env.KEYS_POSTGRES_HOST,
    port: env.KEYS_POSTGRES_PORT,
    database: env.KEYS_POSTGRES_DB,
    user: env.KEYS_POSTGRES_APP_USER,
    password: env.KEYS_POSTGRES_APP_PASSWORD,
    caCertPath: env.SCANOVA_CA_CERT_PATH,
  });
}

/** Key database, migrator role (owns the schema; migrations only). */
export function parseKeystoreMigratorConfig(source: EnvSource): PostgresConfig {
  const env = parseEnvWith(
    'keystore migrator',
    { ...keyDatabaseShape, ...keyMigratorRoleShape, ...caShape },
    source,
  );
  return toPostgres(source, {
    host: env.KEYS_POSTGRES_HOST,
    port: env.KEYS_POSTGRES_PORT,
    database: env.KEYS_POSTGRES_DB,
    user: env.KEYS_POSTGRES_MIGRATOR_USER,
    password: env.KEYS_POSTGRES_MIGRATOR_PASSWORD,
    caCertPath: env.SCANOVA_CA_CERT_PATH,
  });
}

export function loadKeystoreConfig(options?: EnvSourceOptions): PostgresConfig {
  return parseKeystoreConfig(readEnvSource(options));
}

export function loadKeystoreMigratorConfig(options?: EnvSourceOptions): PostgresConfig {
  return parseKeystoreMigratorConfig(readEnvSource(options));
}
