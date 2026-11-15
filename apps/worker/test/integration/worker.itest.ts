import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Readable } from 'node:stream';
import type { INestApplicationContext } from '@nestjs/common';
import { AppLogger } from '@scanova/config/logger';
import { buildPostgresConfig } from '@scanova/config/postgres';
import type { WorkerConfig } from '@scanova/config/sections';
import { JOB_NAMES } from '@scanova/contracts/jobs';
import { FIELDS } from '@scanova/crypto/bindings';
import { blindIndexToken } from '@scanova/crypto/blind-index';
import {
  documentSearchTokens,
  openDocumentText,
  sealDocumentMetadata,
  sealDocumentText,
} from '@scanova/crypto/document';
import { encryptField } from '@scanova/crypto/field';
import { deriveObjectKey } from '@scanova/crypto/hierarchy';
import { createDatabaseClient, type DatabaseClient } from '@scanova/database/client';
import { JobQueue } from '@scanova/jobs/queue';
import { Keyring } from '@scanova/keyring/keyring';
import { createKeystoreClient, type KeystoreClient } from '@scanova/keystore/client';
import { OpenBaoKms } from '@scanova/kms/openbao';
import { EncryptedObjectStore } from '@scanova/storage/encrypted';
import { objectKeyFor } from '@scanova/storage/object-keys';
import { S3ObjectStore } from '@scanova/storage/s3';
import { readIntegrationState } from '@scanova/tooling/integration/state';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createWorker } from '../../src/bootstrap.js';
import { JobRunner } from '../../src/job-runner.js';
import { OcrError, type OcrEngine, type OcrRequest } from '../../src/ocr/ocr-engine.js';
import { OcrService } from '../../src/ocr/ocr.service.js';
import { PurgeService } from '../../src/purge/purge.service.js';
import { RotationService } from '../../src/rotation/rotation.service.js';

const state = readIntegrationState();
const DAY_MS = 86_400_000;
const tls = { caCertPath: state.pki.caCertPath };
const pg = (target: 'main' | 'keys'): ReturnType<typeof buildPostgresConfig> =>
  buildPostgresConfig({
    host: state.postgres.host,
    port: state.postgres.port,
    database: state.postgres[target].database,
    ...state.postgres[target].app,
    tls,
  });

const scratch = mkdtempSync(join(tmpdir(), 'scanova-worker-it-'));
const config: WorkerConfig = {
  runtime: { nodeEnv: 'test', logLevel: 'fatal' },
  limits: { trashRetentionDays: 30 },
  auth: { accessTokenTtlSeconds: 600 },
  ocr: { concurrency: 1, languages: 'eng+urd', tmpDir: join(scratch, 'ocr') },
  database: pg('main'),
  keystore: pg('keys'),
  storage: {
    endpoint: state.s3.endpoint,
    region: state.s3.region,
    bucket: state.s3.bucket,
    forcePathStyle: true,
    accessKeyId: state.s3.accessKeyId,
    secretAccessKey: state.s3.secretAccessKey,
  },
  kms: {
    address: state.openbao.address,
    transitMount: state.openbao.transitMount,
    caCertPath: null,
    roleId: state.openbao.worker.roleId,
    secretId: state.openbao.worker.secretId,
  },
  keys: state.openbao.keys,
  rotation: { userTypeKeyDays: 90, userKeyDays: 180, jwtKeyDays: 30, rewrapBatchSize: 2 },
};

/** Stands in for OCRmyPDF: records what it was given and writes plausible outputs. */
class FakeOcrEngine implements OcrEngine {
  public inputs: Buffer[] = [];
  public text = 'Recognised words from the server\fsecond page رسید';
  public fail = false;
  public beforeFinish: (() => Promise<void>) | null = null;

  public async run(request: OcrRequest): Promise<void> {
    const input = await readFile(request.inputPath);
    this.inputs.push(input);
    // Like the real engine, it leaves temporary files of its own in the work directory.
    await writeFile(join(request.workDir, 'engine-temp.bin'), input);
    if (this.fail) {
      throw new OcrError('EXIT_2');
    }
    await writeFile(request.outputPath, Buffer.concat([input, Buffer.from('\n%searchable-layer')]));
    await writeFile(request.sidecarPath, this.text);
    await this.beforeFinish?.();
  }
}

const engine = new FakeOcrEngine();
const logs: string[] = [];
let worker: INestApplicationContext;
let database: DatabaseClient;
let keystore: KeystoreClient;
let bucket: S3ObjectStore;
let objects: EncryptedObjectStore;
/** The API's view: a keyring with the API vault role, used to seed data like the API would. */
let apiKeyring: Keyring;
let apiVault: OpenBaoKms;
let producer: JobQueue;

beforeAll(async () => {
  database = createDatabaseClient(pg('main'));
  keystore = createKeystoreClient(pg('keys'));
  bucket = new S3ObjectStore(config.storage);
  objects = new EncryptedObjectStore(bucket);
  apiVault = new OpenBaoKms({
    ...config.kms,
    roleId: state.openbao.api.roleId,
    secretId: state.openbao.api.secretId,
  });
  apiKeyring = new Keyring({
    kms: apiVault,
    keystore: createKeystoreClient(pg('keys')),
    settings: { keys: config.keys, rotation: config.rotation, accessTokenTtlSeconds: 600 },
  });
  // Jobs left queued by other suites would be picked up by this worker; start from an empty queue.
  await database.$executeRawUnsafe(`DELETE FROM pgboss.job WHERE state IN ('created', 'retry')`);
  producer = new JobQueue(pg('main'), { role: 'producer', onError: () => undefined });
  await producer.start();
  worker = await createWorker(config, {
    ocrEngine: engine,
    logger: new AppLogger('debug', 'worker', (line) => logs.push(line)),
    keyring: { masterKeySettleSeconds: 0 },
  });
});

afterAll(async () => {
  await worker.close();
  await producer.stop();
  await apiKeyring.close();
  await apiVault.close();
  bucket.close();
  await database.$disconnect();
  await keystore.$disconnect();
  rmSync(scratch, { recursive: true, force: true });
});

interface SeededUser {
  readonly dbId: number;
  readonly id: string;
}

async function seedUser(): Promise<SeededUser> {
  const id = randomUUID();
  await apiKeyring.provisionUser(id, 'MEMBER');
  const encrypted = await apiKeyring.useProfileKey(id, (key) => ({
    emailEnc: encryptField(key, FIELDS.userEmail(id), `${id}@example.test`),
    nameEnc: encryptField(key, FIELDS.userName(id), 'Worker Test'),
  }));
  const user = await database.user.create({
    data: {
      publicId: id,
      emailIndex: randomBytes(32),
      ...encrypted,
      userType: 'MEMBER',
      passwordHash: 'x',
    },
  });
  return { dbId: user.id, id };
}

interface SeededDocument {
  readonly dbId: number;
  readonly id: string;
  readonly file: Buffer;
  readonly objectKey: string;
  readonly objectId: string;
}

async function seedDocument(
  user: SeededUser,
  options: { title?: string; clientText?: string; deletedAt?: Date } = {},
): Promise<SeededDocument> {
  const id = randomUUID();
  const title = options.title ?? 'Scanned letter';
  const file = Buffer.concat([Buffer.from('%PDF-1.7\n'), randomBytes(150_000)]);
  await apiKeyring.createDocumentKey(user.id, id);
  const objectId = randomUUID();
  const objectKey = objectKeyFor({ userId: user.id, documentId: id, objectId });
  const sealed = await apiKeyring.useDocumentKey(user.id, id, async (key) => {
    const streamKey = deriveObjectKey(key, objectId);
    await objects.put({ objectKey, objectId, streamKey }, Readable.from([file]));
    return {
      ...sealDocumentMetadata(key, id, { title, tags: [] }),
      textEnc:
        options.clientText === undefined ? null : sealDocumentText(key, id, options.clientText),
    };
  });
  const tokens = await apiKeyring.useIndexKey(user.id, (key) =>
    documentSearchTokens(key, { title, tags: [], text: options.clientText ?? null }),
  );
  const document = await database.document.create({
    data: {
      publicId: id,
      userId: user.dbId,
      titleEnc: sealed.titleEnc,
      tagsEnc: sealed.tagsEnc,
      pageCount: 1,
      capturedAt: new Date(),
      status: 'READY',
      ocrStatus: 'PENDING',
      deletedAt: options.deletedAt ?? null,
      files: {
        create: {
          publicId: objectId,
          variant: 'ORIGINAL',
          objectKey,
          sizeBytes: BigInt(file.length),
          sha256: createHash('sha256').update(file).digest(),
        },
      },
      ...(sealed.textEnc === null
        ? {}
        : { text: { create: { textEnc: sealed.textEnc, source: 'CLIENT' } } }),
      searchTokens: { create: tokens.map((token) => ({ token })) },
    },
  });
  await database.user.update({
    where: { id: user.dbId },
    data: { storageUsedBytes: { increment: BigInt(file.length) } },
  });
  return { dbId: document.id, id, file, objectKey, objectId };
}

/** Adds a page with an encrypted original photo to a seeded document, as the API's upload does. */
async function seedPageOriginal(
  user: SeededUser,
  document: SeededDocument,
): Promise<{ objectKey: string; size: number }> {
  const photo = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), randomBytes(40_000)]);
  const objectId = randomUUID();
  const objectKey = objectKeyFor({ userId: user.id, documentId: document.id, objectId });
  await apiKeyring.useDocumentKey(user.id, document.id, async (key) => {
    await objects.put(
      { objectKey, objectId, streamKey: deriveObjectKey(key, objectId) },
      Readable.from([photo]),
    );
  });
  await database.documentPage.create({
    data: {
      publicId: randomUUID(),
      documentId: document.dbId,
      position: 0,
      width: 1000,
      height: 1400,
      originalObjectId: objectId,
      originalObjectKey: objectKey,
      originalSizeBytes: BigInt(photo.length),
      originalSha256: createHash('sha256').update(photo).digest(),
    },
  });
  await database.user.update({
    where: { id: user.dbId },
    data: { storageUsedBytes: { increment: BigInt(photo.length) } },
  });
  return { objectKey, size: photo.length };
}

async function readEncrypted(
  user: SeededUser,
  documentId: string,
  objectKey: string,
  objectId: string,
): Promise<Buffer> {
  return apiKeyring.useDocumentKey(user.id, documentId, async (key) => {
    const chunks: Buffer[] = [];
    for await (const chunk of await objects.get({
      objectKey,
      objectId,
      streamKey: deriveObjectKey(key, objectId),
    })) {
      chunks.push(chunk as Buffer);
    }
    return Buffer.concat(chunks);
  });
}

async function waitFor(check: () => Promise<boolean>, timeoutMs = 30_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!(await check())) {
    if (Date.now() > deadline) {
      throw new Error('Timed out waiting for the worker.');
    }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
}

const scratchEntries = (): string[] => {
  try {
    return readdirSync(config.ocr.tmpDir);
  } catch {
    return [];
  }
};
const attempt = { isFinalAttempt: false, signal: new AbortController().signal };
const lastAttempt = { isFinalAttempt: true, signal: new AbortController().signal };

describe('OCR job', () => {
  it('decrypts to the scratch directory, stores the searchable PDF and text encrypted, rebuilds the index and cleans up', async () => {
    const user = await seedUser();
    const document = await seedDocument(user, {
      title: 'Water bill',
      clientText: 'phoneonly words',
    });
    engine.inputs = [];

    expect(await worker.get(OcrService).process(document.id, attempt)).toBe('DONE');

    // The engine was given exactly the plaintext of the original.
    expect(engine.inputs).toHaveLength(1);
    expect(engine.inputs[0]!.equals(document.file)).toBe(true);
    // Nothing is left on the scratch directory: neither our files nor the engine's.
    expect(scratchEntries()).toEqual([]);

    const row = await database.document.findUniqueOrThrow({
      where: { id: document.dbId },
      include: { files: true, text: true, searchTokens: true },
    });
    expect(row.ocrStatus).toBe('DONE');
    const searchable = row.files.find((file) => file.variant === 'SEARCHABLE')!;
    const expected = Buffer.concat([document.file, Buffer.from('\n%searchable-layer')]);
    expect(Number(searchable.sizeBytes)).toBe(expected.length);
    expect(Buffer.from(searchable.sha256).toString('hex')).toBe(
      createHash('sha256').update(expected).digest('hex'),
    );
    expect(searchable.objectKey).toBe(`u/${user.id}/${document.id}/${searchable.publicId}`);
    expect(searchable.publicId).not.toBe(document.objectId);

    // At rest: SCNV1 ciphertext under its own stream key; it decrypts to the engine's output.
    const chunks: Buffer[] = [];
    for await (const chunk of await bucket.get(searchable.objectKey)) {
      chunks.push(chunk as Buffer);
    }
    const atRest = Buffer.concat(chunks);
    expect(atRest.subarray(0, 4).toString('ascii')).toBe('SCNV');
    expect(atRest.includes(Buffer.from('%PDF-'))).toBe(false);
    expect(
      (await readEncrypted(user, document.id, searchable.objectKey, searchable.publicId)).equals(
        expected,
      ),
    ).toBe(true);

    // Server text replaced the phone's, encrypted, with form feeds normalised.
    expect(row.text!.source).toBe('SERVER');
    expect(Buffer.from(row.text!.textEnc).includes(Buffer.from('Recognised'))).toBe(false);
    const text = await apiKeyring.useDocumentKey(user.id, document.id, (key) =>
      openDocumentText(key, document.id, row.text!.textEnc),
    );
    expect(text).toBe('Recognised words from the server\nsecond page رسید');

    // The blind index now covers title + server text, and no longer the phone-only word.
    const tokens = new Set(
      row.searchTokens.map((entry) => Buffer.from(entry.token).toString('hex')),
    );
    const tokenOf = (word: string): Promise<string> =>
      apiKeyring.useIndexKey(user.id, (key) => blindIndexToken(key, word).toString('hex'));
    expect(tokens.has(await tokenOf('recognised'))).toBe(true);
    expect(tokens.has(await tokenOf('رسید'))).toBe(true);
    expect(tokens.has(await tokenOf('water'))).toBe(true);
    expect(tokens.has(await tokenOf('phoneonly'))).toBe(false);

    // Delivered twice: nothing happens.
    expect(await worker.get(OcrService).process(document.id, attempt)).toBe('SKIPPED');
    expect(engine.inputs).toHaveLength(1);
    // Logs name the document id and sizes, never the text.
    expect(logs.join('')).toContain(document.id);
    expect(logs.join('')).not.toContain('Recognised');
  });

  it('cleans the scratch directory when the engine fails, and marks the document FAILED only on the last attempt', async () => {
    const user = await seedUser();
    const document = await seedDocument(user);
    engine.fail = true;
    try {
      await expect(worker.get(OcrService).process(document.id, attempt)).rejects.toBeInstanceOf(
        OcrError,
      );
      expect(scratchEntries()).toEqual([]);
      expect(
        (await database.document.findUniqueOrThrow({ where: { id: document.dbId } })).ocrStatus,
      ).toBe('PENDING');

      await expect(worker.get(OcrService).process(document.id, lastAttempt)).rejects.toBeInstanceOf(
        OcrError,
      );
      expect(scratchEntries()).toEqual([]);
      const row = await database.document.findUniqueOrThrow({
        where: { id: document.dbId },
        include: { files: true },
      });
      expect(row.ocrStatus).toBe('FAILED');
      expect(row.files.map((file) => file.variant)).toEqual(['ORIGINAL']);
    } finally {
      engine.fail = false;
    }
  });

  it('discards its result when the file was replaced while it was working', async () => {
    const user = await seedUser();
    const document = await seedDocument(user);
    engine.beforeFinish = async (): Promise<void> => {
      // The user uploads a new version in the meantime.
      await database.fileObject.update({
        where: { publicId: document.objectId },
        data: { publicId: randomUUID() },
      });
    };
    try {
      expect(await worker.get(OcrService).process(document.id, attempt)).toBe('SUPERSEDED');
    } finally {
      engine.beforeFinish = null;
    }
    const row = await database.document.findUniqueOrThrow({
      where: { id: document.dbId },
      include: { files: true },
    });
    expect(row.files.map((file) => file.variant)).toEqual(['ORIGINAL']);
    expect(row.ocrStatus).toBe('PENDING');
    const keys: string[] = [];
    for await (const key of bucket.list(`u/${user.id}/${document.id}/`)) {
      keys.push(key);
    }
    expect(keys).toEqual([document.objectKey]);
    expect(scratchEntries()).toEqual([]);
  });

  it('skips documents that are gone', async () => {
    const user = await seedUser();
    const deleted = await seedDocument(user, { deletedAt: new Date() });
    expect(await worker.get(OcrService).process(deleted.id, attempt)).toBe('SKIPPED');
    expect(await worker.get(OcrService).process(randomUUID(), attempt)).toBe('SKIPPED');
    // Its key was destroyed (a purge got there first): skipped, not an error.
    const purged = await seedDocument(user);
    await apiKeyring.destroyDocumentKeys(user.id, [purged.id]);
    expect(await worker.get(OcrService).process(purged.id, lastAttempt)).toBe('SKIPPED');
    expect(scratchEntries()).toEqual([]);
  });
});

describe('permanent deletion', () => {
  it('purges the trash after the retention: key first, then objects, then rows', async () => {
    const user = await seedUser();
    const expired = await seedDocument(user, { deletedAt: new Date(Date.now() - 31 * DAY_MS) });
    const expiredOriginal = await seedPageOriginal(user, expired);
    const recent = await seedDocument(user, { deletedAt: new Date(Date.now() - 29 * DAY_MS) });
    const recentOriginal = await seedPageOriginal(user, recent);
    const live = await seedDocument(user);
    const usedBefore = (await database.user.findUniqueOrThrow({ where: { id: user.dbId } }))
      .storageUsedBytes;

    expect(await worker.get(PurgeService).purgeTrash()).toBeGreaterThanOrEqual(1);

    expect(await database.document.findUnique({ where: { publicId: expired.id } })).toBeNull();
    expect(await database.fileObject.count({ where: { documentId: expired.dbId } })).toBe(0);
    expect(await database.documentPage.count({ where: { documentId: expired.dbId } })).toBe(0);
    expect(await database.searchToken.count({ where: { documentId: expired.dbId } })).toBe(0);
    expect(await bucket.exists(expired.objectKey)).toBe(false);
    // Page originals go with their document, and their space is given back too.
    expect(await bucket.exists(expiredOriginal.objectKey)).toBe(false);
    expect(await bucket.exists(recentOriginal.objectKey)).toBe(true);
    expect(await keystore.dataKey.count({ where: { scope: 'DOCUMENT', refId: expired.id } })).toBe(
      0,
    );
    await expect(
      apiKeyring.useDocumentKey(user.id, expired.id, () => 'opened'),
    ).rejects.toMatchObject({ code: 'KEY_NOT_FOUND' });
    const usedAfter = (await database.user.findUniqueOrThrow({ where: { id: user.dbId } }))
      .storageUsedBytes;
    expect(usedBefore - usedAfter).toBe(BigInt(expired.file.length + expiredOriginal.size));

    for (const kept of [recent, live]) {
      expect(await database.document.findUnique({ where: { publicId: kept.id } })).not.toBeNull();
      expect(
        (await readEncrypted(user, kept.id, kept.objectKey, kept.objectId)).equals(kept.file),
      ).toBe(true);
    }
  });

  it('purges a crypto-shredded user completely and never a live one', async () => {
    const victim = await seedUser();
    const bystander = await seedUser();
    const documents = [await seedDocument(victim), await seedDocument(victim)];
    await seedPageOriginal(victim, documents[0]!);
    const other = await seedDocument(bystander);

    // A live account is refused.
    expect(await worker.get(PurgeService).purgeUser(victim.id)).toBe(false);
    expect(await database.user.findUnique({ where: { publicId: victim.id } })).not.toBeNull();

    // What the API's delete does: destroy the keys, mark the row.
    await apiKeyring.destroyUser(victim.id);
    await database.user.update({
      where: { id: victim.dbId },
      data: { deletedAt: new Date(), status: 'DISABLED' },
    });
    expect(await worker.get(PurgeService).purgeUser(victim.id)).toBe(true);

    expect(await database.user.findUnique({ where: { publicId: victim.id } })).toBeNull();
    expect(
      await database.document.count({
        where: { publicId: { in: documents.map((document) => document.id) } },
      }),
    ).toBe(0);
    const left: string[] = [];
    for await (const key of bucket.list(`u/${victim.id}/`)) {
      left.push(key);
    }
    expect(left).toEqual([]);
    const keys = await keystore.userKey.findMany({ where: { userId: victim.id } });
    expect(keys.every((key) => key.state === 'DESTROYED' && key.wrappedKey === null)).toBe(true);
    expect(await keystore.dataKey.count({ where: { userId: victim.id } })).toBe(0);
    // Idempotent, and the other user is untouched.
    expect(await worker.get(PurgeService).purgeUser(victim.id)).toBe(false);
    expect(
      (await readEncrypted(bystander, other.id, other.objectKey, other.objectId)).equals(
        other.file,
      ),
    ).toBe(true);
  });

  it('garbage-collects keys that protect nothing and keeps every key that has data', async () => {
    const user = await seedUser();
    const document = await seedDocument(user);
    const longAgo = new Date(Date.now() - 3 * DAY_MS);
    // A DOCUMENT key whose document was never created (the request failed after the key was made).
    const orphanDocument = randomUUID();
    await apiKeyring.createDocumentKey(user.id, orphanDocument);
    await keystore.dataKey.updateMany({
      where: { userId: user.id, scope: 'DOCUMENT' },
      data: { claimedAt: longAgo },
    });
    // Keys of a user whose row never came to exist.
    const orphanUser = randomUUID();
    await apiKeyring.provisionUser(orphanUser, 'MEMBER');
    await keystore.userKey.updateMany({
      where: { userId: { in: [orphanUser, user.id] } },
      data: { createdAt: longAgo },
    });

    const collected = await worker.get(PurgeService).collectOrphanKeys();
    expect(collected.documentKeys).toBeGreaterThanOrEqual(1);
    expect(collected.users).toBeGreaterThanOrEqual(1);

    expect(await keystore.dataKey.count({ where: { refId: orphanDocument } })).toBe(0);
    expect((await keystore.userKey.findFirstOrThrow({ where: { userId: orphanUser } })).state).toBe(
      'DESTROYED',
    );
    expect(await keystore.dataKey.count({ where: { userId: orphanUser } })).toBe(0);
    // Old keys that still protect data are kept, however old they are.
    expect(await keystore.dataKey.count({ where: { refId: document.id } })).toBe(1);
    expect((await keystore.userKey.findFirstOrThrow({ where: { userId: user.id } })).state).toBe(
      'ACTIVE',
    );
    expect(
      (await readEncrypted(user, document.id, document.objectKey, document.objectId)).equals(
        document.file,
      ),
    ).toBe(true);
  });
});

describe('jobs through the queue', () => {
  it('runs OCR, a requested key rotation and a user purge when their jobs arrive', async () => {
    const user = await seedUser();
    const document = await seedDocument(user);
    const doomed = await seedUser();
    await seedDocument(doomed);
    await worker.get(JobRunner).start();

    // What the API does after an upload, a rotation request and a user deletion.
    await producer.enqueue(JOB_NAMES.documentOcr, { documentId: document.id });
    const run = await apiKeyring.rotation.request({
      scope: 'USER_KEY',
      userId: user.id,
      trigger: 'MANUAL',
    });
    await producer.enqueue(JOB_NAMES.rotationRun, { runId: run.id });
    await apiKeyring.destroyUser(doomed.id);
    await database.user.update({ where: { id: doomed.dbId }, data: { deletedAt: new Date() } });
    await producer.enqueue(JOB_NAMES.userPurge, { userId: doomed.id });

    await waitFor(
      async () =>
        (await database.document.findUniqueOrThrow({ where: { id: document.dbId } })).ocrStatus ===
        'DONE',
    );
    await waitFor(async () => (await apiKeyring.rotation.get(run.id)).state === 'COMPLETED');
    await waitFor(
      async () => (await database.user.findUnique({ where: { publicId: doomed.id } })) === null,
    );

    // After the rotation the document is still readable and the old user-key version is gone.
    const row = await database.document.findUniqueOrThrow({
      where: { id: document.dbId },
      include: { files: true },
    });
    const searchable = row.files.find((file) => file.variant === 'SEARCHABLE')!;
    expect(
      (await readEncrypted(user, document.id, document.objectKey, document.objectId)).equals(
        document.file,
      ),
    ).toBe(true);
    expect(
      (await readEncrypted(user, document.id, searchable.objectKey, searchable.publicId)).length,
    ).toBeGreaterThan(document.file.length);
    const versions = await keystore.userKey.findMany({
      where: { userId: user.id },
      orderBy: { version: 'asc' },
    });
    expect(versions.map((key) => [key.version, key.state, key.wrappedKey === null])).toEqual([
      [1, 'DESTROYED', true],
      [2, 'ACTIVE', false],
    ]);
    // The recurring jobs are scheduled.
    const schedules = await database.$queryRawUnsafe<{ name: string }[]>(
      'SELECT name FROM pgboss.schedule ORDER BY name',
    );
    expect(schedules.map((schedule) => schedule.name)).toEqual([
      'keys.gc',
      'rotation.sweep',
      'trash.purge',
    ]);
  });

  it('queues a follow-up for a rotation that has to wait, and records a failure on the last attempt', async () => {
    const rotation = worker.get(RotationService);
    // On a stack that is reused, an earlier run may have left a signing-key rotation
    // waiting. Finish it first (with a clock that is hours ahead), so this test
    // starts a rotation of its own.
    const leftovers = await keystore.keyRotationRun.findMany({
      where: {
        scope: 'JWT_SIGNING',
        target: config.keys.jwt,
        state: { in: ['PENDING', 'RUNNING'] },
      },
    });
    for (const leftover of leftovers) {
      for (const hours of [1, 2]) {
        const vault = new OpenBaoKms(config.kms);
        const ahead = new Keyring({
          kms: vault,
          keystore: createKeystoreClient(pg('keys')),
          settings: {
            keys: config.keys,
            rotation: config.rotation,
            accessTokenTtlSeconds: 600,
            now: () => new Date(Date.now() + hours * 3_600_000),
          },
        });
        await ahead.rotation.run(leftover.publicId);
        await ahead.close();
        await vault.close();
      }
    }
    // A signing-key rotation waits for the access-token lifetime before it retires the old version.
    let run = await apiKeyring.rotation.request({ scope: 'JWT_SIGNING', trigger: 'MANUAL' });
    let progress = await rotation.run(run.id, { isFinalAttempt: false });
    if (progress?.state === 'COMPLETED') {
      // On a stack that is reused, an older run may have been waiting long enough to finish now.
      run = await apiKeyring.rotation.request({ scope: 'JWT_SIGNING', trigger: 'MANUAL' });
      progress = await rotation.run(run.id, { isFinalAttempt: false });
    }
    expect(progress?.state).toBe('WAITING');
    const queued = await database.$queryRaw<{ start_after: Date }[]>`
      SELECT start_after FROM pgboss.job WHERE name = 'rotation.run' AND data->>'runId' = ${run.id} AND state = 'created'`;
    expect(queued).toHaveLength(1);
    // The follow-up is scheduled for when the old version may be retired: the
    // access-token lifetime (600 s) plus the margin (120 s) from now.
    const delayMs = queued[0]!.start_after.getTime() - Date.now();
    expect(delayMs).toBeGreaterThan(600_000);
    expect(delayMs).toBeLessThanOrEqual(721_000);
    // The sweep reports it as unfinished and does not start a second one.
    const unfinished = await rotation.sweep();
    expect(unfinished).toContain(run.id);
    expect(
      (await apiKeyring.rotation.request({ scope: 'JWT_SIGNING', trigger: 'MANUAL' })).id,
    ).toBe(run.id);

    expect(await rotation.run(randomUUID(), { isFinalAttempt: true })).toBeNull();
    // A run whose target key does not exist fails; the last attempt records it.
    const broken = await keystore.keyRotationRun.create({
      data: { scope: 'USER_TYPE_MASTER', target: 'scanova-no-such-key', trigger: 'MANUAL' },
    });
    await expect(rotation.run(broken.publicId, { isFinalAttempt: false })).rejects.toThrow();
    expect((await apiKeyring.rotation.get(broken.publicId)).state).toBe('PENDING');
    await expect(rotation.run(broken.publicId, { isFinalAttempt: true })).rejects.toThrow();
    expect(await apiKeyring.rotation.get(broken.publicId)).toMatchObject({
      state: 'FAILED',
      errorCode: expect.any(String) as string,
    });
  });
});
