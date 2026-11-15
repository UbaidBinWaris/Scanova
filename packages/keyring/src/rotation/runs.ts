import type { KeyRotationRun, KeystoreClient, KeystoreTransaction } from '@scanova/keystore/client';
import { KeyringError } from '../errors.js';
import type { RotationRunRecord, RotationScope } from '../types.js';

export function toRunRecord(row: KeyRotationRun): RotationRunRecord {
  return {
    id: row.publicId,
    scope: row.scope,
    target: row.target,
    state: row.state,
    trigger: row.trigger,
    fromVersion: row.fromVersion,
    toVersion: row.toVersion,
    totalItems: row.totalItems,
    processedItems: row.processedItems,
    errorCode: row.errorCode,
    requestedBy: row.requestedBy,
    createdAt: row.createdAt,
    startedAt: row.startedAt,
    finishedAt: row.finishedAt,
  };
}

export async function loadRun(keystore: KeystoreClient, runId: string): Promise<KeyRotationRun> {
  const row = await keystore.keyRotationRun.findUnique({ where: { publicId: runId } });
  if (row === null) {
    throw new KeyringError('RUN_NOT_FOUND', 'No such rotation run.');
  }
  return row;
}

/** The run for a target that is not finished yet, if any. At most one is ever wanted. */
export function findUnfinishedRun(
  client: KeystoreClient | KeystoreTransaction,
  scope: RotationScope,
  target: string,
): Promise<KeyRotationRun | null> {
  return client.keyRotationRun.findFirst({
    where: { scope, target, state: { in: ['PENDING', 'RUNNING'] } },
    orderBy: { id: 'asc' },
  });
}

/**
 * Serialises "is there an unfinished run? if not, create one" per target, so two
 * requests arriving together cannot both start a rotation. Released at commit.
 */
export async function lockTarget(
  tx: KeystoreTransaction,
  scope: RotationScope,
  target: string,
): Promise<void> {
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${`scanova:rotation:${scope}:${target}`}))`;
}

export interface RunPage {
  readonly runs: RotationRunRecord[];
  readonly nextCursor: string | null;
}

interface RunCursor {
  readonly c: string;
  readonly i: string;
}

function decodeCursor(cursor: string): RunCursor | null {
  try {
    const parsed = JSON.parse(
      Buffer.from(cursor, 'base64url').toString('utf8'),
    ) as Partial<RunCursor>;
    if (
      typeof parsed.c === 'string' &&
      typeof parsed.i === 'string' &&
      !Number.isNaN(Date.parse(parsed.c))
    ) {
      return { c: parsed.c, i: parsed.i };
    }
  } catch {
    // fall through: an unreadable cursor is treated as invalid
  }
  return null;
}

/** Newest first. Throws `INVALID_STATE` for a cursor that was not issued by this function. */
export async function listRuns(
  keystore: KeystoreClient,
  options: { limit: number; cursor?: string | undefined },
): Promise<RunPage> {
  let after: RunCursor | null = null;
  if (options.cursor !== undefined) {
    after = decodeCursor(options.cursor);
    if (after === null) {
      throw new KeyringError('INVALID_STATE', 'Invalid cursor.');
    }
  }
  const rows = await keystore.keyRotationRun.findMany({
    where:
      after === null
        ? {}
        : {
            OR: [
              { createdAt: { lt: new Date(after.c) } },
              { createdAt: new Date(after.c), publicId: { lt: after.i } },
            ],
          },
    orderBy: [{ createdAt: 'desc' }, { publicId: 'desc' }],
    take: options.limit + 1,
  });
  const page = rows.slice(0, options.limit);
  const last = page.at(-1);
  const nextCursor =
    rows.length > options.limit && last !== undefined
      ? Buffer.from(JSON.stringify({ c: last.createdAt.toISOString(), i: last.publicId })).toString(
          'base64url',
        )
      : null;
  return { runs: page.map(toRunRecord), nextCursor };
}
