import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { quoteIdentifier, toPgConnectionOptions } from '@scanova/config/postgres';
import { prismaCliEnvironment } from '@scanova/config/prisma-cli';
import type { MigrationTargetConfig } from '@scanova/config/sections';
import pg from 'pg';

const PACKAGE_ROOT = resolve(import.meta.dirname, '..');
const MIGRATIONS_TABLE = '_prisma_migrations';
/** The only table the runtime role may delete from. */
const DELETABLE_TABLE = 'data_keys';

export interface KeystoreMigrationReport {
  /** Tables the runtime role may select, insert and update. */
  readonly grantedTables: readonly string[];
  /** Tables it may additionally delete from. */
  readonly deletableTables: readonly string[];
}

/**
 * Applies pending migrations to the key database with its migrator role, then
 * the table-level grants for its runtime role. Both steps are idempotent.
 */
export async function migrateKeystore(
  target: MigrationTargetConfig,
): Promise<KeystoreMigrationReport> {
  await runPrismaMigrateDeploy(target.migrator.prismaCliUrl);
  return applyKeystoreGrants(target);
}

/**
 * Key runtime role (§6): select, insert and update on the key tables, delete on
 * `data_keys` only. A user key can therefore never be deleted by the
 * application — destroying one is an UPDATE that nulls the wrapped key — and
 * rotation history cannot be erased. The grants are rebuilt from scratch in one
 * transaction so the result is exact.
 */
export async function applyKeystoreGrants(
  target: MigrationTargetConfig,
): Promise<KeystoreMigrationReport> {
  const role = quoteIdentifier(target.runtimeRole);
  const client = new pg.Client(toPgConnectionOptions(target.migrator));
  await client.connect();
  try {
    await client.query('BEGIN');
    const tables = await client.query<{ tablename: string }>(
      `SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> $1 ORDER BY tablename`,
      [MIGRATIONS_TABLE],
    );
    await client.query(`REVOKE ALL ON ALL TABLES IN SCHEMA public FROM ${role}`);
    await client.query(`REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM ${role}`);
    await client.query(`GRANT USAGE ON SCHEMA public TO ${role}`);
    const granted: string[] = [];
    const deletable: string[] = [];
    for (const row of tables.rows) {
      const table = quoteIdentifier(row.tablename);
      await client.query(`GRANT SELECT, INSERT, UPDATE ON ${table} TO ${role}`);
      granted.push(row.tablename);
      if (row.tablename === DELETABLE_TABLE) {
        await client.query(`GRANT DELETE ON ${table} TO ${role}`);
        deletable.push(row.tablename);
      }
    }
    await client.query(`GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ${role}`);
    await client.query('COMMIT');
    return { grantedTables: granted, deletableTables: deletable };
  } catch (error) {
    await client.query('ROLLBACK').catch(() => undefined);
    throw error;
  } finally {
    await client.end();
  }
}

/** `prisma migrate deploy` for this package's schema, as a child process. */
function runPrismaMigrateDeploy(prismaCliUrl: string): Promise<void> {
  const cli = createRequire(import.meta.url).resolve('prisma/build/index.js');
  return new Promise<void>((resolvePromise, reject) => {
    const child = spawn(
      process.execPath,
      [cli, 'migrate', 'deploy', '--config', 'prisma.config.ts'],
      {
        cwd: PACKAGE_ROOT,
        env: prismaCliEnvironment(prismaCliUrl),
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    );
    const output: Buffer[] = [];
    child.stdout.on('data', (chunk: Buffer) => output.push(chunk));
    child.stderr.on('data', (chunk: Buffer) => output.push(chunk));
    child.on('error', reject);
    child.on('close', (code) => {
      if (code === 0) {
        resolvePromise();
        return;
      }
      // The CLI's output names migrations and SQL errors; it does not echo the URL.
      reject(
        new Error(
          `prisma migrate deploy failed for the key database (exit ${code}):\n${Buffer.concat(output).toString('utf8').trim()}`,
        ),
      );
    });
  });
}
