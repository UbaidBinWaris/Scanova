import { ConfigError } from '@scanova/config/errors';
import { loadMigrateConfig } from '@scanova/config/migrate';
import { migrateDatabase } from '@scanova/database/migrate';
import { installJobQueue } from '@scanova/jobs/install';
import { migrateKeystore } from '@scanova/keystore/migrate';

/**
 * Migrate step: `node dist/migrate.js` (the default command of the `migrate`
 * image target). Idempotent.
 *
 * 1. Main database: migrations with the main migrator role, then row-level
 *    grants for the main runtime role, then the job-queue schema.
 * 2. Key database: migrations with its own migrator role, then grants for the
 *    key runtime role (select/insert/update; delete on `data_keys` only).
 *
 * Role names come from the configuration, so the grants are versioned with the schema.
 */
async function main(): Promise<void> {
  const config = loadMigrateConfig();
  const log = (event: string, fields: Record<string, unknown> = {}): void => {
    process.stdout.write(
      `${JSON.stringify({ time: new Date().toISOString(), level: 'info', service: 'migrate', event, ...fields })}\n`,
    );
  };

  const main = await migrateDatabase(config.database);
  log('migrate.database_done', {
    database: config.database.migrator.database,
    runtimeRole: config.database.runtimeRole,
    tables: main.grantedTables.length,
  });
  await installJobQueue(config.database);
  log('migrate.job_queue_done', { database: config.database.migrator.database });
  const keys = await migrateKeystore(config.keystore);
  log('migrate.keystore_done', {
    database: config.keystore.migrator.database,
    runtimeRole: config.keystore.runtimeRole,
    tables: keys.grantedTables.length,
    deletableTables: keys.deletableTables.join(','),
  });
}

main().catch((error: unknown) => {
  const issues = error instanceof ConfigError ? error.issues : undefined;
  // Migration errors come from the Prisma CLI and PostgreSQL: SQL states and migration names, no secrets.
  const message = error instanceof Error ? error.message : 'unknown error';
  process.stderr.write(
    `${JSON.stringify({ level: 'fatal', service: 'migrate', event: 'migrate.failed', message, issues })}\n`,
  );
  process.exit(1);
});
