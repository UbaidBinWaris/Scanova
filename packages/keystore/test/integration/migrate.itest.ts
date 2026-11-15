import { randomUUID } from 'node:crypto';
import { buildPostgresConfig, toPgConnectionOptions } from '@scanova/config/postgres';
import type { PostgresConfig } from '@scanova/config/sections';
import { readIntegrationState } from '@scanova/tooling/integration/state';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createKeystoreClient, type KeystoreClient } from '../../src/client.js';
import { migrateKeystore } from '../../src/migrate.js';

const state = readIntegrationState();
const tls = { caCertPath: state.pki.caCertPath };
const { host, port, main, keys } = state.postgres;

function role(database: string, credentials: { user: string; password: string }): PostgresConfig {
  return buildPostgresConfig({ host, port, database, ...credentials, tls });
}

const migrator = role(keys.database, keys.migrator);
const app = role(keys.database, keys.app);

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

describe('key database: migrations and grants', () => {
  let keystore: KeystoreClient;

  beforeAll(async () => {
    await migrateKeystore({ migrator, runtimeRole: keys.app.user });
    keystore = createKeystoreClient(app);
  });

  afterAll(async () => {
    await keystore.$disconnect();
  });

  it('applies migrations with its own migrator role and is idempotent', async () => {
    const report = await migrateKeystore({ migrator, runtimeRole: keys.app.user });
    expect(report.grantedTables).toEqual([
      'data_keys',
      'key_rotation_runs',
      'system_keys',
      'user_keys',
    ]);
    expect(report.deletableTables).toEqual(['data_keys']);
  });

  it('contains only key tables', async () => {
    const tables = await query(
      migrator,
      `SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY 1`,
    );
    expect(tables.rows.map((row: { tablename: string }) => row.tablename)).toEqual([
      '_prisma_migrations',
      'data_keys',
      'key_rotation_runs',
      'system_keys',
      'user_keys',
    ]);
  });

  it('lets the runtime role select, insert and update, and delete from data_keys only', async () => {
    const userId = randomUUID();
    await keystore.userKey.create({
      data: {
        userId,
        version: 1,
        wrappedKey: 'vault:v1:AAAA',
        masterKeyName: 'scanova-utk-member',
        masterKeyVersion: 1,
      },
    });
    const dataKey = await keystore.dataKey.create({
      data: {
        scope: 'PROFILE',
        userId,
        refId: userId,
        wrappedKey: Buffer.alloc(61, 1),
        userKeyVersion: 1,
      },
    });

    // Destroying a user key is an UPDATE: the row stays, the key material goes.
    const destroyed = await keystore.userKey.update({
      where: { userId_version: { userId, version: 1 } },
      data: { wrappedKey: null, state: 'DESTROYED', destroyedAt: new Date() },
    });
    expect(destroyed.wrappedKey).toBeNull();

    expect(await sqlState(query(app, `DELETE FROM user_keys WHERE user_id = '${userId}'`))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );
    expect(await sqlState(query(app, 'DELETE FROM system_keys WHERE id = -1'))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );
    expect(await sqlState(query(app, 'DELETE FROM key_rotation_runs WHERE id = -1'))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );
    expect(await sqlState(query(app, 'TRUNCATE data_keys'))).toBe(INSUFFICIENT_PRIVILEGE);
    expect(await sqlState(query(app, 'CREATE TABLE intruder (id int)'))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );
    expect(await sqlState(query(app, 'SELECT * FROM _prisma_migrations'))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );

    await keystore.dataKey.delete({ where: { id: dataKey.id } });
    expect(await keystore.dataKey.count({ where: { userId } })).toBe(0);
    expect(await keystore.userKey.count({ where: { userId } })).toBe(1);
  });

  it('refuses both key-database roles on the main database', async () => {
    expect(await sqlState(query(role(main.database, keys.app), 'SELECT 1'))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );
    expect(await sqlState(query(role(main.database, keys.migrator), 'SELECT 1'))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );
  });

  it('refuses the main runtime role on the key database', async () => {
    expect(await sqlState(query(role(keys.database, main.app), 'SELECT 1'))).toBe(
      INSUFFICIENT_PRIVILEGE,
    );
  });
});
