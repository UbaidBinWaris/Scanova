import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildPostgresConfig, toPgConnectionOptions } from '@scanova/config/postgres';
import type { PostgresConfig } from '@scanova/config/sections';
import { readIntegrationState } from '@scanova/tooling/integration/state';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createDatabaseClient, type DatabaseClient } from '../../src/client.js';
import { migrateDatabase } from '../../src/migrate.js';

const state = readIntegrationState();
const tls = { caCertPath: state.pki.caCertPath };
const { host, port, main, keys } = state.postgres;

function role(database: string, credentials: { user: string; password: string }): PostgresConfig {
  return buildPostgresConfig({ host, port, database, ...credentials, tls });
}

const migrator = role(main.database, main.migrator);
const app = role(main.database, main.app);

async function query(config: PostgresConfig, sql: string): Promise<pg.QueryResult> {
  const client = new pg.Client(toPgConnectionOptions(config));
  await client.connect();
  try {
    return await client.query(sql);
  } finally {
    await client.end();
  }
}

async function sqlState(action: Promise<unknown>): Promise<string> {
  try {
    await action;
  } catch (error) {
    return (error as { code?: string }).code ?? 'UNKNOWN';
  }
  return 'OK';
}

const INSUFFICIENT_PRIVILEGE = '42501';

describe('main database: migrations and grants', () => {
  let database: DatabaseClient;

  beforeAll(async () => {
    await migrateDatabase({ migrator, runtimeRole: main.app.user });
    database = createDatabaseClient(app);
  });

  afterAll(async () => {
    await database.$disconnect();
  });

  it('applies migrations with the migrator role over TLS and is idempotent', async () => {
    const report = await migrateDatabase({ migrator, runtimeRole: main.app.user });
    expect(report.grantedTables).toEqual([
      'audit_events',
      'document_texts',
      'documents',
      'file_objects',
      'search_tokens',
      'sessions',
      'spent_refresh_tokens',
      'users',
    ]);
    const applied = await query(
      migrator,
      'SELECT migration_name FROM _prisma_migrations WHERE finished_at IS NOT NULL',
    );
    expect(applied.rows).toEqual([
      { migration_name: '20261115000000_init' },
      { migration_name: '20261115200000_storage_accounting' },
    ]);
  });

  it('refuses negative storage usage at the database level', async () => {
    // A CHECK constraint, so no code path can drive usage below zero.
    const constraint = await query(
      migrator,
      `SELECT conname FROM pg_constraint WHERE conname = 'users_storage_used_bytes_nonnegative'`,
    );
    expect(constraint.rows).toHaveLength(1);
  });

  it('holds no key material: no key tables and no key columns', async () => {
    const tables = await query(
      migrator,
      `SELECT tablename FROM pg_tables WHERE schemaname = 'public'`,
    );
    const names = tables.rows.map((row: { tablename: string }) => row.tablename);
    for (const forbidden of ['user_keys', 'data_keys', 'system_keys', 'key_rotation_runs']) {
      expect(names).not.toContain(forbidden);
    }
    const columns = await query(
      migrator,
      `SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'
       AND (column_name LIKE '%wrapped%' OR column_name LIKE '%key_version%' OR column_name LIKE '%dek%')`,
    );
    expect(columns.rows).toEqual([]);
  });

  it('connects the runtime client over verified TLS', async () => {
    const rows = await database.$queryRaw<{ ssl: boolean; current_user: string }[]>`
      SELECT s.ssl, current_user FROM pg_stat_ssl s WHERE s.pid = pg_backend_pid()`;
    expect(rows).toEqual([{ ssl: true, current_user: main.app.user }]);
  });

  it('refuses a server certificate from another CA', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'scanova-ca-'));
    try {
      execFileSync(
        'openssl',
        [
          'req',
          '-x509',
          '-newkey',
          'ec',
          '-pkeyopt',
          'ec_paramgen_curve:P-256',
          '-nodes',
          '-days',
          '1',
          '-subj',
          '/CN=Other CA',
          '-keyout',
          join(dir, 'ca.key'),
          '-out',
          join(dir, 'ca.crt'),
        ],
        { stdio: 'pipe' },
      );
      const wrongCa = buildPostgresConfig({
        host,
        port,
        database: main.database,
        ...main.app,
        tls: { caCertPath: join(dir, 'ca.crt') },
      });
      await expect(query(wrongCa, 'SELECT 1')).rejects.toThrow();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it('lets the runtime role read and write rows but change no schema', async () => {
    expect(await sqlState(query(app, 'SELECT count(*) FROM users'))).toBe('OK');
    expect(await sqlState(query(app, 'DELETE FROM audit_events WHERE id = -1'))).toBe('OK');
    expect(await sqlState(query(app, 'CREATE TABLE intruder (id int)'))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );
    expect(await sqlState(query(app, 'ALTER TABLE users ADD COLUMN intruder int'))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );
    expect(await sqlState(query(app, 'DROP TABLE users'))).toBe(INSUFFICIENT_PRIVILEGE);
    expect(await sqlState(query(app, 'TRUNCATE audit_events'))).toBe(INSUFFICIENT_PRIVILEGE);
    expect(await sqlState(query(app, 'SELECT * FROM _prisma_migrations'))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );
  });

  it('refuses both main-database roles on the key database', async () => {
    expect(await sqlState(query(role(keys.database, main.app), 'SELECT 1'))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );
    expect(await sqlState(query(role(keys.database, main.migrator), 'SELECT 1'))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );
  });
});
