import { randomUUID } from 'node:crypto';
import { buildPostgresConfig, toPgConnectionOptions } from '@scanova/config/postgres';
import { JOB_NAMES } from '@scanova/contracts/jobs';
import { readIntegrationState } from '@scanova/tooling/integration/state';
import pg from 'pg';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { installJobQueue } from '../../src/install.js';
import { JobQueue } from '../../src/queue.js';

const state = readIntegrationState();
const tls = { caCertPath: state.pki.caCertPath };
const main = state.postgres.main;
const base = { host: state.postgres.host, port: state.postgres.port, database: main.database, tls };
const migrator = buildPostgresConfig({ ...base, ...main.migrator });
const app = buildPostgresConfig({ ...base, ...main.app });

const errors: Error[] = [];
let producer: JobQueue;
let worker: JobQueue;

beforeAll(async () => {
  await installJobQueue({ migrator, runtimeRole: main.app.user });
  // Both connect with the runtime role: no DDL rights.
  producer = new JobQueue(app, { role: 'producer', onError: (error) => errors.push(error) });
  worker = new JobQueue(app, { role: 'worker', onError: (error) => errors.push(error) });
  await producer.start();
  await worker.start();
});

afterAll(async () => {
  await worker.stop();
  await producer.stop();
});

async function waitFor(condition: () => boolean, timeoutMs = 20_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) {
      throw new Error('Timed out waiting for the job.');
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
}

describe('job queue on PostgreSQL', () => {
  it('installs idempotently and runs with the runtime role only', async () => {
    await installJobQueue({ migrator, runtimeRole: main.app.user });
    await expect(producer.health()).resolves.toBeUndefined();
    const client = new pg.Client(toPgConnectionOptions(app));
    await client.connect();
    try {
      await expect(client.query('CREATE TABLE pgboss.intruder (id int)')).rejects.toMatchObject({
        code: '42501',
      });
      // pg-boss keeps an internal queue of its own for cron scheduling.
      const queues = await client.query<{ name: string }>(
        "SELECT name FROM pgboss.queue WHERE left(name, 10) <> '__pgboss__' ORDER BY name",
      );
      expect(queues.rows.map((row) => row.name)).toEqual(Object.values(JOB_NAMES).sort());
    } finally {
      await client.end();
    }
  });

  it('delivers a validated payload to the handler', async () => {
    const userId = randomUUID();
    const seen: { userId: string; attempt: number; final: boolean }[] = [];
    await worker.work(
      JOB_NAMES.userPurge,
      (payload, context) => {
        seen.push({
          userId: payload.userId,
          attempt: context.attempt,
          final: context.isFinalAttempt,
        });
        return Promise.resolve();
      },
      { pollingIntervalSeconds: 0.5 },
    );
    const id = await producer.enqueue(JOB_NAMES.userPurge, { userId });
    expect(id).toEqual(expect.any(String));
    await waitFor(() => seen.some((entry) => entry.userId === userId));
    expect(seen.find((entry) => entry.userId === userId)).toEqual({
      userId,
      attempt: 0,
      final: false,
    });
  });

  it('refuses a payload that carries anything but ids', async () => {
    await expect(
      producer.enqueue(JOB_NAMES.documentOcr, { documentId: randomUUID(), title: 'leak' } as never),
    ).rejects.toThrow();
  });

  it('does not queue the same work twice', async () => {
    const documentId = randomUUID();
    const first = await producer.enqueue(
      JOB_NAMES.documentOcr,
      { documentId },
      { startAfterSeconds: 3600 },
    );
    const second = await producer.enqueue(
      JOB_NAMES.documentOcr,
      { documentId },
      { startAfterSeconds: 3600 },
    );
    const other = await producer.enqueue(
      JOB_NAMES.documentOcr,
      { documentId: randomUUID() },
      { startAfterSeconds: 3600 },
    );
    expect(first).toEqual(expect.any(String));
    expect(second).toBeNull();
    expect(other).toEqual(expect.any(String));
  });

  it('retries a failing job and tells the handler which attempt is the last', async () => {
    const runId = randomUUID();
    const attempts: { attempt: number; final: boolean }[] = [];
    await worker.work(
      JOB_NAMES.keyGarbageCollect,
      (_payload, context) => {
        attempts.push({ attempt: context.attempt, final: context.isFinalAttempt });
        return Promise.reject(new Error(`failing on purpose ${runId}`));
      },
      { pollingIntervalSeconds: 0.5 },
    );
    // keys.gc: retryLimit 1 → two attempts in total. The retry delay is shortened for the test.
    const client = new pg.Client(toPgConnectionOptions(migrator));
    await client.connect();
    try {
      await client.query(
        `UPDATE pgboss.queue SET retry_delay = 1, retry_backoff = false WHERE name = $1`,
        [JOB_NAMES.keyGarbageCollect],
      );
      await producer.enqueue(JOB_NAMES.keyGarbageCollect, {});
      await waitFor(() => attempts.length >= 2, 40_000);
      expect(attempts).toEqual([
        { attempt: 0, final: false },
        { attempt: 1, final: true },
      ]);
    } finally {
      await client.query(
        `UPDATE pgboss.queue SET retry_delay = 300, retry_backoff = true WHERE name = $1`,
        [JOB_NAMES.keyGarbageCollect],
      );
      await client.end();
    }
  });

  it('registers the recurring schedules with the runtime role', async () => {
    await worker.scheduleRecurring();
    await worker.scheduleRecurring();
    const client = new pg.Client(toPgConnectionOptions(app));
    await client.connect();
    try {
      const schedules = await client.query<{ name: string; cron: string }>(
        'SELECT name, cron FROM pgboss.schedule ORDER BY name',
      );
      expect(schedules.rows).toEqual([
        { name: JOB_NAMES.keyGarbageCollect, cron: '23 4 * * *' },
        { name: JOB_NAMES.rotationSweep, cron: '17 * * * *' },
        { name: JOB_NAMES.trashPurge, cron: '41 3 * * *' },
      ]);
    } finally {
      await client.end();
    }
    expect(errors).toEqual([]);
  });
});
