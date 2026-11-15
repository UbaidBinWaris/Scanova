import { createHash, randomBytes, randomUUID } from 'node:crypto';
import type { Document, DocumentText } from '@scanova/contracts/documents';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  bucket,
  closeAllApps,
  closeInspectors,
  createDocument,
  createMember,
  keysDb,
  mainDb,
  pdf,
  sha256,
  startApp,
  type ApiResponse,
  type TestApp,
  type TestUser,
} from './harness.js';

let api: TestApp;
let alice: TestUser;
let bob: TestUser;

beforeAll(async () => {
  api = await startApp();
  [alice, bob] = await Promise.all([createMember(api), createMember(api)]);
});

afterAll(async () => {
  await closeAllApps();
  await closeInspectors();
});

async function readObject(key: string): Promise<Buffer> {
  const chunks: Buffer[] = [];
  for await (const chunk of await bucket().get(key)) {
    chunks.push(chunk as Buffer);
  }
  return Buffer.concat(chunks);
}

describe('creating documents', () => {
  it('creates a document (201) and is idempotent for the same id (200)', async () => {
    const id = randomUUID();
    const body = {
      id,
      title: 'Electricity bill',
      pageCount: 2,
      capturedAt: '2026-11-15T09:30:00.000Z',
      tags: ['bills'],
    };
    const created = await alice.client.post<Document>('/api/v1/documents', body);
    expect(created.status).toBe(201);
    expect(created.body!.data).toMatchObject({
      id,
      title: 'Electricity bill',
      tags: ['bills'],
      pageCount: 2,
      sizeBytes: null,
      contentSha256: null,
      status: 'PENDING_UPLOAD',
      ocrStatus: 'NONE',
      hasSearchablePdf: false,
      capturedAt: '2026-11-15T09:30:00.000Z',
      deletedAt: null,
    });

    const retried = await alice.client.post<Document>('/api/v1/documents', {
      ...body,
      title: 'A retry with other data',
    });
    expect(retried.status).toBe(200);
    expect(retried.body!.data.title).toBe('Electricity bill');
    expect(await mainDb().document.count({ where: { publicId: id } })).toBe(1);
    // One DOCUMENT key, created before the row, in the key database only.
    expect(
      await keysDb().dataKey.count({ where: { scope: 'DOCUMENT', refId: id, userId: alice.id } }),
    ).toBe(1);
  });

  it('answers ID_CONFLICT for an id that belongs to another user', async () => {
    const { id } = await createDocument(alice, { file: null });
    const response = await bob.client.post('/api/v1/documents', {
      id,
      title: 'Mine now',
      pageCount: 1,
      capturedAt: new Date().toISOString(),
    });
    expect(response.status).toBe(409);
    expect(response.body?.error?.code).toBe('ID_CONFLICT');
    expect((await alice.client.get<Document>(`/api/v1/documents/${id}`)).body!.data.title).toBe(
      'Test document',
    );
  });

  it('stores title, tags and text as ciphertext and the search index as opaque tokens', async () => {
    const { id } = await createDocument(alice, {
      title: 'Passport renewal',
      tags: ['identity'],
      clientOcrText: 'machine readable zone',
      file: null,
    });
    const row = await mainDb().document.findUniqueOrThrow({
      where: { publicId: id },
      include: { text: true, searchTokens: true },
    });
    for (const [bytes, plaintext] of [
      [row.titleEnc, 'Passport'],
      [row.tagsEnc, 'identity'],
      [row.text!.textEnc, 'machine'],
    ] as const) {
      expect(bytes[0]).toBe(0x01);
      expect(Buffer.from(bytes).includes(Buffer.from(plaintext))).toBe(false);
    }
    // passport, renewal, identity, machine, readable, zone
    expect(row.searchTokens).toHaveLength(6);
    expect(row.searchTokens.every((entry) => entry.token.length === 16)).toBe(true);
  });
});

describe('upload and download', () => {
  it('round-trips a PDF with a matching checksum and stores only SCNV1 ciphertext', async () => {
    const file = pdf(300_000);
    const { id, document } = await createDocument(alice, { file });
    expect(document).toMatchObject({
      status: 'READY',
      ocrStatus: 'PENDING',
      sizeBytes: file.length,
      contentSha256: sha256(file),
    });

    const download = await alice.client.get(`/api/v1/documents/${id}/file`);
    expect(download.status).toBe(200);
    expect(download.headers.get('content-type')).toBe('application/pdf');
    expect(download.headers.get('content-length')).toBe(String(file.length));
    expect(download.headers.get('cache-control')).toBe('private, no-store');
    expect(download.raw.equals(file)).toBe(true);
    expect(sha256(download.raw)).toBe(document.contentSha256);
    expect(
      (await alice.client.get(`/api/v1/documents/${id}/file?variant=original`)).raw.equals(file),
    ).toBe(true);
    expect((await alice.client.get(`/api/v1/documents/${id}/file?variant=searchable`)).status).toBe(
      404,
    );
    expect((await alice.client.get(`/api/v1/documents/${id}/file?variant=thumbnail`)).status).toBe(
      400,
    );

    const stored = await mainDb().fileObject.findFirstOrThrow({
      where: { document: { publicId: id } },
    });
    expect(stored.objectKey).toBe(`u/${alice.id}/${id}/${stored.publicId}`);
    const atRest = await readObject(stored.objectKey);
    expect(atRest.subarray(0, 4).toString('ascii')).toBe('SCNV');
    expect(atRest.includes(Buffer.from('%PDF-'))).toBe(false);
    expect(atRest.length).toBe(16 + file.length + 16 * Math.ceil(file.length / 65_536));
    // An OCR job was queued, and its payload is the document id and nothing else.
    const jobs = await mainDb().$queryRaw<{ data: unknown }[]>`
      SELECT data FROM pgboss.job WHERE name = 'document.ocr' AND data->>'documentId' = ${id}`;
    expect(jobs).toEqual([{ data: { documentId: id } }]);
  });

  it('streams a file larger than any buffer the server keeps (24 MiB)', async () => {
    const { id } = await createDocument(alice, { file: null });
    const size = 24 * 1024 * 1024;
    const hash = createHash('sha256');
    const head = Buffer.from('%PDF-1.7\n');
    const parts: Buffer[] = [head];
    hash.update(head);
    for (let sent = head.length; sent < size;) {
      const chunk = randomBytes(Math.min(1024 * 1024, size - sent));
      hash.update(chunk);
      parts.push(chunk);
      sent += chunk.length;
    }
    const digest = hash.digest('hex');
    const before = process.memoryUsage().rss;
    const uploaded = await alice.client.uploadPdf(id, Buffer.concat(parts), { sha256: digest });
    expect(uploaded.status).toBe(200);
    expect(uploaded.body!.data).toMatchObject({
      sizeBytes: size,
      contentSha256: digest,
      status: 'READY',
    });
    const download = await alice.client.get(`/api/v1/documents/${id}/file`);
    expect(download.raw.length).toBe(size);
    expect(sha256(download.raw)).toBe(digest);
    // Not a precise measurement (the test client holds the file too), but a whole-body buffer on the server would show.
    expect(process.memoryUsage().rss - before).toBeLessThan(200 * 1024 * 1024);
  });

  it('rejects a body that does not match X-Content-SHA256 and stores nothing', async () => {
    const { id } = await createDocument(alice, { file: null });
    const response = await alice.client.uploadPdf(id, pdf(100_000), {
      sha256: sha256(Buffer.from('other')),
    });
    expect(response.status).toBe(422);
    expect(response.body?.error?.code).toBe('CHECKSUM_MISMATCH');
    expect((await alice.client.get<Document>(`/api/v1/documents/${id}`)).body!.data).toMatchObject({
      status: 'PENDING_UPLOAD',
      sizeBytes: null,
    });
    expect(await mainDb().fileObject.count({ where: { document: { publicId: id } } })).toBe(0);
    const leftovers: string[] = [];
    for await (const key of bucket().list(`u/${alice.id}/${id}/`)) {
      leftovers.push(key);
    }
    expect(leftovers).toEqual([]);
    // The connection survived the failed upload: the same client can go on.
    expect((await alice.client.uploadPdf(id, pdf(2000))).status).toBe(200);
  });

  it('rejects what is not a PDF, a missing checksum header and an oversized file', async () => {
    const { id } = await createDocument(alice, { file: null });
    const notPdf = Buffer.concat([Buffer.from('PK\u0003\u0004 zip archive'), randomBytes(5000)]);
    const wrongBytes = await alice.client.uploadPdf(id, notPdf);
    expect(wrongBytes.status).toBe(415);
    expect(wrongBytes.body?.error?.code).toBe('UNSUPPORTED_MEDIA_TYPE');
    const wrongType = await alice.client.uploadPdf(id, pdf(), { contentType: 'image/png' });
    expect(wrongType.status).toBe(415);
    expect(wrongType.body?.error?.code).toBe('UNSUPPORTED_MEDIA_TYPE');

    const noChecksum = await alice.client.request('PUT', `/api/v1/documents/${id}/file`, {
      body: pdf(),
      headers: { 'content-type': 'application/pdf' },
    });
    expect(noChecksum.status).toBe(400);
    expect(noChecksum.body?.error?.details).toEqual([
      { field: 'x-content-sha256', message: expect.any(String) as string },
    ]);

    const small = await startApp({ http: { port: 0, maxUploadBytes: 10_000 } });
    const tooLarge = await small.client.as(alice.tokens).uploadPdf(id, pdf(10_001));
    expect(tooLarge.status).toBe(413);
    expect(tooLarge.body?.error?.code).toBe('FILE_TOO_LARGE');
    expect((await small.client.as(alice.tokens).uploadPdf(id, pdf(10_000))).status).toBe(200);
    await small.close();
  });

  it('treats a repeated upload of the same file as a no-op and replaces a different one', async () => {
    const first = pdf(50_000);
    const { id } = await createDocument(alice, { file: first });
    const original = await mainDb().fileObject.findFirstOrThrow({
      where: { document: { publicId: id } },
    });

    const repeated = await alice.client.uploadPdf(id, first);
    expect(repeated.status).toBe(200);
    expect(
      (await mainDb().fileObject.findFirstOrThrow({ where: { document: { publicId: id } } }))
        .publicId,
    ).toBe(original.publicId);

    const second = pdf(70_000);
    const replaced = await alice.client.uploadPdf(id, second);
    expect(replaced.body!.data).toMatchObject({
      sizeBytes: second.length,
      contentSha256: sha256(second),
    });
    const current = await mainDb().fileObject.findMany({ where: { document: { publicId: id } } });
    expect(current).toHaveLength(1);
    expect(current[0]!.publicId).not.toBe(original.publicId);
    expect(await bucket().exists(original.objectKey)).toBe(false);
    expect((await alice.client.get(`/api/v1/documents/${id}/file`)).raw.equals(second)).toBe(true);
  });

  it('enforces the storage quota and accounts for replaced files', async () => {
    const carol = await createMember(api);
    await mainDb().user.update({
      where: { publicId: carol.id },
      data: { storageQuotaBytes: 100_000n },
    });
    const first = await createDocument(carol, { file: pdf(60_000) });
    expect(
      (
        await carol.client.get<{ storageUsedBytes: number; storageQuotaBytes: number }>(
          '/api/v1/me',
        )
      ).body!.data,
    ).toMatchObject({ storageUsedBytes: 60_000, storageQuotaBytes: 100_000 });

    const second = await createDocument(carol, { file: null });
    const over = await carol.client.uploadPdf(second.id, pdf(40_001));
    expect(over.status).toBe(413);
    expect(over.body?.error?.code).toBe('QUOTA_EXCEEDED');
    expect((await carol.client.uploadPdf(second.id, pdf(40_000))).status).toBe(200);
    // Replacing a file with a smaller one frees space.
    expect((await carol.client.uploadPdf(first.id, pdf(10_000))).status).toBe(200);
    expect(
      (await carol.client.get<{ storageUsedBytes: number }>('/api/v1/me')).body!.data
        .storageUsedBytes,
    ).toBe(50_000);
  });
});

describe('isolation between users', () => {
  it("answers 404 NOT_FOUND on every route for another user's document", async () => {
    const { id, file } = await createDocument(alice, {
      title: 'Alice only',
      clientOcrText: 'private words',
    });
    const attempts = [
      await bob.client.get(`/api/v1/documents/${id}`),
      await bob.client.patch(`/api/v1/documents/${id}`, { title: 'Hijacked' }),
      await bob.client.delete(`/api/v1/documents/${id}`),
      await bob.client.get(`/api/v1/documents/${id}/file`),
      await bob.client.get(`/api/v1/documents/${id}/text`),
      await bob.client.uploadPdf(id, pdf()),
    ];
    for (const response of attempts) {
      expect(response.status).toBe(404);
      expect(response.body).toEqual({
        data: null,
        error: { code: 'NOT_FOUND', message: 'Not found.' },
        meta: { nextCursor: null },
      });
    }
    const bobsList = await bob.client.get<Document[]>('/api/v1/documents?limit=200');
    expect(bobsList.body!.data.map((document) => document.id)).not.toContain(id);
    expect((await bob.client.get<Document[]>('/api/v1/documents?q=private')).body!.data).toEqual(
      [],
    );

    // Nothing changed for the owner.
    const mine = await alice.client.get<Document>(`/api/v1/documents/${id}`);
    expect(mine.body!.data).toMatchObject({ title: 'Alice only', deletedAt: null });
    expect((await alice.client.get(`/api/v1/documents/${id}/file`)).raw.equals(file!)).toBe(true);
  });
});

describe('metadata, text, search and sync', () => {
  it('updates title and tags and keeps the search index in step', async () => {
    const dave = await createMember(api);
    const { id } = await createDocument(dave, {
      title: 'Draft contract',
      tags: ['work'],
      clientOcrText: 'party of the first part',
      file: null,
    });
    const search = async (q: string): Promise<string[]> =>
      (
        await dave.client.get<Document[]>(`/api/v1/documents?q=${encodeURIComponent(q)}`)
      ).body!.data.map((document) => document.id);

    expect(await search('draft')).toEqual([id]);
    expect(await search('DRAFT contract first')).toEqual([id]);
    expect(await search('draft invoice')).toEqual([]);
    expect(await search('...')).toEqual([]);

    const updated = await dave.client.patch<Document>(`/api/v1/documents/${id}`, {
      title: 'Signed contract',
      tags: ['work', 'legal'],
    });
    expect(updated.status).toBe(200);
    expect(updated.body!.data).toMatchObject({ title: 'Signed contract', tags: ['work', 'legal'] });
    expect(await search('draft')).toEqual([]);
    expect(await search('signed legal party')).toEqual([id]);
    expect((await dave.client.patch(`/api/v1/documents/${id}`, {})).status).toBe(400);

    const text = await dave.client.get<DocumentText>(`/api/v1/documents/${id}/text`);
    expect(text.body!.data).toEqual({ text: 'party of the first part', source: 'CLIENT' });
    const { id: bare } = await createDocument(dave, { file: null });
    expect((await dave.client.get(`/api/v1/documents/${bare}/text`)).status).toBe(404);
  });

  it('finds words in other scripts (Urdu)', async () => {
    const erin = await createMember(api);
    const { id } = await createDocument(erin, { title: 'بجلی کا بل', tags: ['گھر'], file: null });
    const found = await erin.client.get<Document[]>(
      `/api/v1/documents?q=${encodeURIComponent('بجلی')}`,
    );
    expect(found.body!.data.map((document) => document.id)).toEqual([id]);
    expect(found.body!.data[0]!.title).toBe('بجلی کا بل');
    expect(
      (await erin.client.get<Document[]>(`/api/v1/documents?q=${encodeURIComponent('گھر بل')}`))
        .body!.data,
    ).toHaveLength(1);
  });

  it('paginates with a cursor, newest change first', async () => {
    const frank = await createMember(api);
    const ids: string[] = [];
    for (let index = 0; index < 5; index += 1) {
      ids.push((await createDocument(frank, { title: `Doc ${index}`, file: null })).id);
    }
    const seen: string[] = [];
    let cursor: string | null = null;
    let pages = 0;
    do {
      const page: ApiResponse<Document[]> = await frank.client.get<Document[]>(
        `/api/v1/documents?limit=2${cursor === null ? '' : `&cursor=${cursor}`}`,
      );
      expect(page.status).toBe(200);
      expect(page.body!.data.length).toBeLessThanOrEqual(2);
      seen.push(...page.body!.data.map((document) => document.id));
      cursor = page.body!.meta!.nextCursor;
      pages += 1;
    } while (cursor !== null);
    expect(pages).toBe(3);
    expect(seen).toEqual([...ids].reverse());
  });

  it('soft-deletes, hides deleted documents from the list and reports them to a sync', async () => {
    const gina = await createMember(api);
    const before = new Date(Date.now() - 1000).toISOString();
    const kept = await createDocument(gina, { title: 'Kept', file: null });
    const removed = await createDocument(gina, { title: 'Removed' });
    const watermark = (await gina.client.get<Document>(`/api/v1/documents/${removed.id}`)).body!
      .data.updatedAt;

    const deleted = await gina.client.delete(`/api/v1/documents/${removed.id}`);
    expect(deleted.body?.data).toEqual({ success: true });
    expect((await gina.client.delete(`/api/v1/documents/${removed.id}`)).body?.data).toEqual({
      success: true,
    });

    const live = await gina.client.get<Document[]>('/api/v1/documents');
    expect(live.body!.data.map((document) => document.id)).toEqual([kept.id]);
    // A device that syncs sees the removal, oldest change first.
    const sync = await gina.client.get<Document[]>(
      `/api/v1/documents?updatedSince=${encodeURIComponent(before)}`,
    );
    expect(sync.body!.data.map((document) => [document.id, document.deletedAt !== null])).toEqual([
      [kept.id, false],
      [removed.id, true],
    ]);
    const incremental = await gina.client.get<Document[]>(
      `/api/v1/documents?updatedSince=${encodeURIComponent(watermark)}`,
    );
    expect(incremental.body!.data.map((document) => document.id)).toEqual([removed.id]);
    // `updatedSince` is inclusive: sending back the largest updatedAt seen returns that document again
    // rather than risking a skipped change in the same millisecond.
    const newest = incremental.body!.data[0]!.updatedAt;
    expect(newest).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/u);
    const boundary = await gina.client.get<Document[]>(
      `/api/v1/documents?updatedSince=${encodeURIComponent(newest)}`,
    );
    expect(boundary.body!.data.map((document) => document.id)).toEqual([removed.id]);
    // Documents that are still PENDING_UPLOAD are part of a sync: the uploading device relies on them.
    expect(sync.body!.data.find((document) => document.id === kept.id)?.status).toBe(
      'PENDING_UPLOAD',
    );

    // The deleted document is still visible by id (with deletedAt) but cannot be changed or downloaded.
    expect(
      (await gina.client.get<Document>(`/api/v1/documents/${removed.id}`)).body!.data.deletedAt,
    ).not.toBeNull();
    expect(
      (await gina.client.patch(`/api/v1/documents/${removed.id}`, { title: 'x' })).status,
    ).toBe(404);
    expect((await gina.client.get(`/api/v1/documents/${removed.id}/file`)).status).toBe(404);
    expect((await gina.client.uploadPdf(removed.id, pdf())).status).toBe(404);
  });
});
