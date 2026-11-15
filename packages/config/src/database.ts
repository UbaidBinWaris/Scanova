import { readEnvSource, type EnvSource, type EnvSourceOptions } from './env-source.js';
import {
  caShape,
  mainAppRoleShape,
  mainDatabaseShape,
  mainMigratorRoleShape,
  toPostgres,
} from './fragments.js';
import { parseEnvWith } from './parse.js';
import type { PostgresConfig } from './sections.js';

/** Main database, runtime role (row read/write only). */
export function parseDatabaseConfig(source: EnvSource): PostgresConfig {
  const env = parseEnvWith(
    'database',
    { ...mainDatabaseShape, ...mainAppRoleShape, ...caShape },
    source,
  );
  return toPostgres(source, {
    host: env.POSTGRES_HOST,
    port: env.POSTGRES_PORT,
    database: env.POSTGRES_DB,
    user: env.POSTGRES_APP_USER,
    password: env.POSTGRES_APP_PASSWORD,
    caCertPath: env.SCANOVA_CA_CERT_PATH,
  });
}

/** Main database, migrator role (owns the schema; migrations only). */
export function parseDatabaseMigratorConfig(source: EnvSource): PostgresConfig {
  const env = parseEnvWith(
    'database migrator',
    { ...mainDatabaseShape, ...mainMigratorRoleShape, ...caShape },
    source,
  );
  return toPostgres(source, {
    host: env.POSTGRES_HOST,
    port: env.POSTGRES_PORT,
    database: env.POSTGRES_DB,
    user: env.POSTGRES_MIGRATOR_USER,
    password: env.POSTGRES_MIGRATOR_PASSWORD,
    caCertPath: env.SCANOVA_CA_CERT_PATH,
  });
}

export function loadDatabaseConfig(options?: EnvSourceOptions): PostgresConfig {
  return parseDatabaseConfig(readEnvSource(options));
}

export function loadDatabaseMigratorConfig(options?: EnvSourceOptions): PostgresConfig {
  return parseDatabaseMigratorConfig(readEnvSource(options));
}
