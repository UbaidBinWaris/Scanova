import { quoteIdentifier, toPgConnectionOptions } from '@scanova/config/postgres';
import type { MigrationTargetConfig } from '@scanova/config/sections';
import pg from 'pg';
import { PgBoss } from 'pg-boss';
import { JOB_DEFINITIONS, JOB_NAME_LIST, JOB_SCHEMA } from './definitions.js';

/**
 * Installs (or upgrades) the queue schema and creates every queue with the
 * migrator role, then grants the runtime role row access to it. Idempotent.
 *
 * It needs the migrator because it is DDL: a schema, tables and indexes. That
 * means the main migrator role must be allowed to create a schema in its
 * database (it is, as the database owner).
 */
export async function installJobQueue(target: MigrationTargetConfig): Promise<void> {
  const errors: Error[] = [];
  const boss = new PgBoss({
    ...toPgConnectionOptions(target.migrator),
    application_name: 'scanova-jobs-install',
    schema: JOB_SCHEMA,
    max: 2,
    migrate: true,
    createSchema: true,
    supervise: false,
    schedule: false,
    registerInstance: false,
  });
  boss.on('error', (error) => errors.push(error));
  await boss.start();
  try {
    for (const name of JOB_NAME_LIST) {
      const definition = JOB_DEFINITIONS[name];
      const options = {
        retryLimit: definition.retryLimit,
        retryDelay: definition.retryDelaySeconds,
        retryBackoff: true,
        expireInSeconds: definition.expireInSeconds,
      };
      if ((await boss.getQueue(name)) === null) {
        // `stately`: one queued and one running job per dedupe key.
        await boss.createQueue(name, {
          ...options,
          policy: definition.dedupeKey === null ? 'standard' : 'stately',
        });
      } else {
        await boss.updateQueue(name, options);
      }
    }
  } finally {
    await boss.stop({ graceful: false });
  }
  if (errors[0] !== undefined) {
    throw errors[0];
  }
  await grantJobQueue(target);
}

/** Row access to the queue schema for the runtime role; no DDL. */
async function grantJobQueue(target: MigrationTargetConfig): Promise<void> {
  const role = quoteIdentifier(target.runtimeRole);
  const schema = quoteIdentifier(JOB_SCHEMA);
  const client = new pg.Client(toPgConnectionOptions(target.migrator));
  await client.connect();
  try {
    await client.query('BEGIN');
    await client.query(`REVOKE ALL ON ALL TABLES IN SCHEMA ${schema} FROM ${role}`);
    await client.query(`GRANT USAGE ON SCHEMA ${schema} TO ${role}`);
    await client.query(
      `GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ${schema} TO ${role}`,
    );
    await client.query(
      `GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA ${schema} TO ${role}`,
    );
    await client.query(`GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA ${schema} TO ${role}`);
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK').catch(() => undefined);
    throw error;
  } finally {
    await client.end();
  }
}
