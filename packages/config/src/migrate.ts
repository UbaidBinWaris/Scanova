import { parseDatabaseMigratorConfig } from './database.js';
import { readEnvSource, type EnvSource, type EnvSourceOptions } from './env-source.js';
import { keyAppRoleShape, mainAppRoleShape, runtimeShape, toRuntime } from './fragments.js';
import { parseKeystoreMigratorConfig } from './keystore.js';
import { parseEnvWith } from './parse.js';
import type { MigrateConfig } from './sections.js';

/**
 * Configuration for the migrate step: both migrator connections plus the names
 * of the runtime roles that receive table-level grants. The runtime passwords
 * are not required here.
 */
export function parseMigrateConfig(source: EnvSource): MigrateConfig {
  const env = parseEnvWith(
    'migrate',
    {
      ...runtimeShape,
      POSTGRES_APP_USER: mainAppRoleShape.POSTGRES_APP_USER,
      KEYS_POSTGRES_APP_USER: keyAppRoleShape.KEYS_POSTGRES_APP_USER,
    },
    source,
  );
  return {
    runtime: toRuntime(env),
    database: {
      migrator: parseDatabaseMigratorConfig(source),
      runtimeRole: env.POSTGRES_APP_USER,
    },
    keystore: {
      migrator: parseKeystoreMigratorConfig(source),
      runtimeRole: env.KEYS_POSTGRES_APP_USER,
    },
  };
}

export function loadMigrateConfig(options?: EnvSourceOptions): MigrateConfig {
  return parseMigrateConfig(readEnvSource(options));
}
