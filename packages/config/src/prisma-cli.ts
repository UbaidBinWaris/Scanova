import { ConfigError } from './errors.js';
import { loadDatabaseMigratorConfig } from './database.js';
import { loadKeystoreMigratorConfig } from './keystore.js';

/**
 * Hand-off between the migrate step and the Prisma CLI it spawns. The CLI reads
 * its datasource URL from `prisma.config.ts`, which cannot receive arguments, so
 * the parent passes the URL to that one child process through this variable.
 * It is not configuration and is deliberately absent from `.env.example`.
 */
export const PRISMA_CLI_URL_VARIABLE = 'SCANOVA_PRISMA_CLI_URL';

export type PrismaCliTarget = 'database' | 'keystore';

/**
 * Datasource URL for a `prisma.config.ts`.
 *
 * 1. The URL handed over by the migrate step, when present.
 * 2. Otherwise the migrator role from the root `.env` (a developer running the
 *    Prisma CLI by hand).
 * 3. Otherwise `undefined`, which is enough for `prisma generate` and
 *    `prisma migrate diff`, neither of which connects to a database.
 */
export function prismaCliDatasourceUrl(target: PrismaCliTarget): string | undefined {
  const handedOver = process.env[PRISMA_CLI_URL_VARIABLE];
  if (handedOver !== undefined && handedOver !== '') {
    return handedOver;
  }
  try {
    const config =
      target === 'database' ? loadDatabaseMigratorConfig() : loadKeystoreMigratorConfig();
    return config.prismaCliUrl;
  } catch (error) {
    if (error instanceof ConfigError) {
      return undefined;
    }
    throw error;
  }
}

/**
 * Environment for a spawned Prisma CLI: the minimum it needs to run, plus the
 * datasource URL. The parent's environment is not passed through, so the child
 * never sees unrelated secrets.
 */
export function prismaCliEnvironment(prismaCliUrl: string): Record<string, string> {
  const environment: Record<string, string> = {
    [PRISMA_CLI_URL_VARIABLE]: prismaCliUrl,
    // No update checks or telemetry from a migration container.
    CHECKPOINT_DISABLE: '1',
    PRISMA_HIDE_UPDATE_MESSAGE: '1',
    NO_COLOR: '1',
  };
  for (const name of ['PATH', 'HOME', 'TMPDIR']) {
    const value = process.env[name];
    if (value !== undefined && value !== '') {
      environment[name] = value;
    }
  }
  return environment;
}
