import { randomUUID } from 'node:crypto';
import type { Document, DocumentPage, DocumentPages } from '@scanova/contracts/documents';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  bucket,
  closeAllApps,
  closeInspectors,
  createDocument,
  createMember,
  jpeg,
  mainDb,
  sha256,
  startApp,
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

/** A recipe as the phone writes it (`PageEdit`), including a field this server does not know. */
function recipe(filter: string = 'enhanced'): Record<string, unknown> {
  return {
    v: 1,
    sourceRotation: 90,
    crop: [
      { x: 0.05, y: 0.04 },
      { x: 0.95, y: 0.06 },
      { x: 0.94, y: 0.96 },
      { x: 0.06, y: 0.95 },
    ],
    rotation: 0,
    filter,
    filterStrength: 0.8,
    brightness: 0.1,
    contrast: -0.2,
    markup: [
      {
        tool: 'highlighter',
        color: 0xffffeb3b,
        width: 0.02,
        points: [
          { x: 0.1, y: 0.2 },
          { x: 0.4, y: 0.2 },
        ],
      },
    ],
    futureField: { kept: true },
  };
}

function pageInput(
  id: string,
  position: number,
  edit: Record<string, unknown> | null = recipe(),
): Record<string, unknown> {
  return { id, position, width: 1240, height: 1754, ...(edit === null ? {} : { edit }) };
}

async function putPages(
  user: TestUser,
  documentId: string,
  pages: Record<string, unknown>[],
): Promise<DocumentPages> {
  const response = await user.client.put<DocumentPages>(`/api/v1/documents/${documentId}/pages`, {
    pages,
  });
  expect(response.status).toBe(200);
  return response.body!.data;
}

async function usedBytes(user: TestUser): Promise<number> {
  return (await user.client.get<{ storageUsedBytes: number }>('/api/v1/me')).body!.data
    .storageUsedBytes;
}

describe('page list', () => {
  it('stores order, sizes and encrypted recipes, and reports them back in order', async () => {
    const { id } = await createDocument(alice);
    const [first, second, third] = [randomUUID(), randomUUID(), randomUUID()];
    const before = (await alice.client.get<Document>(`/api/v1/documents/${id}`)).body!.data;
    expect(before.pagesUpdatedAt).toBeNull();
    expect(
      (await alice.client.get<DocumentPages>(`/api/v1/documents/${id}/pages`)).body!.data,
    ).toEqual({ pagesUpdatedAt: null, pages: [] });

    // Any distinct positions; the server keeps the order and numbers them 0, 1, 2.
    const stored = await putPages(alice, id, [
      pageInput(third, 7, recipe('black_white')),
      pageInput(first, 0),
      pageInput(second, 3, null),
    ]);
    expect(stored.pages.map((page) => [page.id, page.position])).toEqual([
      [first, 0],
      [second, 1],
      [third, 2],
    ]);
    expect(stored.pages[0]).toMatchObject({
      width: 1240,
      height: 1754,
      edit: recipe(),
      hasOriginal: false,
      originalSha256: null,
      originalSizeBytes: null,
    });
    expect(stored.pages[1]!.edit).toBeNull();
    expect(stored.pages[2]!.edit).toEqual(recipe('black_white'));
    expect(stored.pagesUpdatedAt).not.toBeNull();
    expect(stored.pages.every((page) => page.updatedAt === stored.pagesUpdatedAt)).toBe(true);

    const document = (await alice.client.get<Document>(`/api/v1/documents/${id}`)).body!.data;
    expect(document).toMatchObject({ pageCount: 3, pagesUpdatedAt: stored.pagesUpdatedAt });
    expect(document.updatedAt >= stored.pagesUpdatedAt!).toBe(true);
    expect(
      (await alice.client.get<DocumentPages>(`/api/v1/documents/${id}/pages`)).body!.data,
    ).toEqual(stored);

    // At rest the recipe is ciphertext: nothing of it is readable in the database.
    const rows = await mainDb().documentPage.findMany({ where: { document: { publicId: id } } });
    expect(rows).toHaveLength(3);
    for (const row of rows.filter((candidate) => candidate.editEnc !== null)) {
      expect(row.editEnc![0]).toBe(0x01);
      for (const plaintext of ['enhanced', 'black_white', 'highlighter', 'futureField']) {
        expect(Buffer.from(row.editEnc!).includes(Buffer.from(plaintext))).toBe(false);
      }
    }
  });

  it('is idempotent and changes only what changed', async () => {
    const { id } = await createDocument(alice);
    const ids = [randomUUID(), randomUUID()];
    const list = [pageInput(ids[0]!, 0), pageInput(ids[1]!, 1)];
    const first = await putPages(alice, id, list);
    const rowsBefore = await mainDb().documentPage.findMany({
      where: { document: { publicId: id } },
      orderBy: { position: 'asc' },
    });

    const repeated = await putPages(alice, id, list);
    expect(repeated).toEqual(first);
    const rowsAfter = await mainDb().documentPage.findMany({
      where: { document: { publicId: id } },
      orderBy: { position: 'asc' },
    });
    // Not even re-encrypted.
    expect(rowsAfter.map((row) => Buffer.from(row.editEnc!).toString('hex'))).toEqual(
      rowsBefore.map((row) => Buffer.from(row.editEnc!).toString('hex')),
    );

    // Reordering moves pages without touching their recipes.
    await new Promise((resolve) => setTimeout(resolve, 5));
    const swapped = await putPages(alice, id, [pageInput(ids[1]!, 0), pageInput(ids[0]!, 1)]);
    expect(swapped.pages.map((page) => page.id)).toEqual([ids[1], ids[0]]);
    expect(swapped.pagesUpdatedAt! > first.pagesUpdatedAt!).toBe(true);
    const rowsSwapped = await mainDb().documentPage.findMany({
      where: { document: { publicId: id } },
    });
    for (const row of rowsSwapped) {
      const earlier = rowsBefore.find((candidate) => candidate.publicId === row.publicId)!;
      expect(Buffer.from(row.editEnc!).equals(Buffer.from(earlier.editEnc!))).toBe(true);
    }

    // A changed recipe is stored and returned.
    const edited = await putPages(alice, id, [
      pageInput(ids[1]!, 0, recipe('greyscale')),
      pageInput(ids[0]!, 1),
    ]);
    expect(edited.pages[0]!.edit).toEqual(recipe('greyscale'));
  });

  it('refuses invalid lists and accepts lists larger than an ordinary JSON body', async () => {
    const { id } = await createDocument(alice);
    const page = randomUUID();
    const invalid = [
      { pages: [] },
      { pages: [pageInput(page, 0), pageInput(page, 1)] },
      { pages: [pageInput(page, 0), pageInput(randomUUID(), 0)] },
      { pages: [pageInput(page, 0, { rotation: 90 })] },
      { pages: [pageInput(page, 0, { v: 1, padding: 'x'.repeat(70_000) })] },
    ];
    for (const request of invalid) {
      const response = await alice.client.put(`/api/v1/documents/${id}/pages`, request);
      expect(response.status).toBe(400);
      expect(response.body?.error?.code).toBe('VALIDATION_FAILED');
    }

    // About 3 MiB of recipes (pages with a lot of markup): above the 2 MiB JSON limit, within the page-list limit.
    const heavy = {
      ...recipe(),
      markup: [
        {
          tool: 'pen',
          color: 0xff1e5eff,
          width: 0.01,
          points: Array.from({ length: 1500 }, (_, index) => ({ x: index / 1500, y: 0.5 })),
        },
      ],
    };
    const many = Array.from({ length: 80 }, (_, index) => pageInput(randomUUID(), index, heavy));
    expect(JSON.stringify({ pages: many }).length).toBeGreaterThan(2.5 * 1024 * 1024);
    const stored = await putPages(alice, id, many);
    expect(stored.pages).toHaveLength(80);
    expect(stored.pages[79]!.edit).toEqual(heavy);
  });
});

describe('page originals', () => {
  it('uploads, downloads and stores only SCNV1 ciphertext; a repeat is a no-op', async () => {
    const { id } = await createDocument(alice);
    const pageId = randomUUID();
    const photo = jpeg(200_000);

    // The page has to be in the list first.
    const early = await alice.client.uploadOriginal(id, pageId, photo);
    expect(early.status).toBe(404);
    expect(early.body?.error?.code).toBe('NOT_FOUND');

    const listed = await putPages(alice, id, [pageInput(pageId, 0)]);
    const usageBefore = await usedBytes(alice);
    const uploaded = await alice.client.uploadOriginal(id, pageId, photo);
    expect(uploaded.status).toBe(200);
    expect(uploaded.body!.data).toMatchObject({
      id: pageId,
      hasOriginal: true,
      originalSha256: sha256(photo),
      originalSizeBytes: photo.length,
      edit: recipe(),
    });
    expect(await usedBytes(alice)).toBe(usageBefore + photo.length);
    // The upload moved pagesUpdatedAt to the page's updatedAt.
    const pages = (await alice.client.get<DocumentPages>(`/api/v1/documents/${id}/pages`)).body!
      .data;
    expect(pages.pagesUpdatedAt).toBe(uploaded.body!.data.updatedAt);
    expect(pages.pagesUpdatedAt! > listed.pagesUpdatedAt!).toBe(true);
    expect(
      (await alice.client.get<Document>(`/api/v1/documents/${id}`)).body!.data.pagesUpdatedAt,
    ).toBe(pages.pagesUpdatedAt);

    const download = await alice.client.get(`/api/v1/documents/${id}/pages/${pageId}/original`);
    expect(download.status).toBe(200);
    expect(download.headers.get('content-type')).toBe('image/jpeg');
    expect(download.headers.get('content-length')).toBe(String(photo.length));
    expect(download.headers.get('cache-control')).toBe('private, no-store');
    expect(download.headers.get('x-content-sha256')).toBe(sha256(photo));
    expect(download.raw.equals(photo)).toBe(true);

    const row = await mainDb().documentPage.findFirstOrThrow({
      where: { document: { publicId: id }, publicId: pageId },
    });
    expect(row.originalObjectKey).toBe(`u/${alice.id}/${id}/${row.originalObjectId}`);
    const chunks: Buffer[] = [];
    for await (const chunk of await bucket().get(row.originalObjectKey!)) {
      chunks.push(chunk as Buffer);
    }
    const atRest = Buffer.concat(chunks);
    expect(atRest.subarray(0, 4).toString('ascii')).toBe('SCNV');
    expect(atRest.includes(photo.subarray(0, 64))).toBe(false);

    // The same bytes again: nothing new is stored and nothing is charged.
    const repeated = await alice.client.uploadOriginal(id, pageId, photo);
    expect(repeated.status).toBe(200);
    expect(repeated.body!.data.updatedAt).toBe(uploaded.body!.data.updatedAt);
    const unchanged = await mainDb().documentPage.findFirstOrThrow({ where: { id: row.id } });
    expect(unchanged.originalObjectId).toBe(row.originalObjectId);
    expect(await usedBytes(alice)).toBe(usageBefore + photo.length);

    // Different bytes replace the original; the old object is deleted and the usage adjusted.
    const replacement = jpeg(50_000);
    expect((await alice.client.uploadOriginal(id, pageId, replacement)).status).toBe(200);
    expect(await bucket().exists(row.originalObjectKey!)).toBe(false);
    expect(await usedBytes(alice)).toBe(usageBefore + replacement.length);
    expect(
      (await alice.client.get(`/api/v1/documents/${id}/pages/${pageId}/original`)).raw.equals(
        replacement,
      ),
    ).toBe(true);

    // A page without an original has nothing to download.
    const bare = randomUUID();
    await putPages(alice, id, [pageInput(pageId, 0), pageInput(bare, 1)]);
    expect((await alice.client.get(`/api/v1/documents/${id}/pages/${bare}/original`)).status).toBe(
      404,
    );
  });

  it('rejects what is not a JPEG, a wrong checksum and an oversized photo, storing nothing', async () => {
    const { id } = await createDocument(alice);
    const pageId = randomUUID();
    await putPages(alice, id, [pageInput(pageId, 0)]);

    const notJpeg = await alice.client.uploadOriginal(id, pageId, Buffer.from('%PDF-1.7 nope'));
    expect(notJpeg.status).toBe(415);
    expect(notJpeg.body?.error).toEqual({
      code: 'UNSUPPORTED_MEDIA_TYPE',
      message: 'Only JPEG images are accepted.',
    });
    const wrongType = await alice.client.uploadOriginal(id, pageId, jpeg(), {
      contentType: 'application/pdf',
    });
    expect(wrongType.status).toBe(415);
    const corrupted = await alice.client.uploadOriginal(id, pageId, jpeg(10_000), {
      sha256: sha256(Buffer.from('other')),
    });
    expect(corrupted.status).toBe(422);
    expect(corrupted.body?.error?.code).toBe('CHECKSUM_MISMATCH');

    const small = await startApp({ http: { port: 0, maxUploadBytes: 10_000 } });
    const tooLarge = await small.client.as(alice.tokens).uploadOriginal(id, pageId, jpeg(10_001));
    expect(tooLarge.status).toBe(413);
    expect(tooLarge.body?.error?.code).toBe('FILE_TOO_LARGE');
    await small.close();

    const row = await mainDb().documentPage.findFirstOrThrow({
      where: { document: { publicId: id }, publicId: pageId },
    });
    expect(row.originalObjectKey).toBeNull();
    const leftovers: string[] = [];
    for await (const key of bucket().list(`u/${alice.id}/${id}/`)) {
      leftovers.push(key);
    }
    // Only the document's PDF.
    expect(leftovers).toHaveLength(1);
  });

  it('counts originals towards the quota and gives the space back when a page is removed', async () => {
    const carol = await createMember(api);
    await mainDb().user.update({
      where: { publicId: carol.id },
      data: { storageQuotaBytes: 100_000n },
    });
    const { id } = await createDocument(carol);
    const pdfBytes = await usedBytes(carol);
    const [kept, removed] = [randomUUID(), randomUUID()];
    await putPages(carol, id, [pageInput(kept, 0), pageInput(removed, 1)]);

    expect((await carol.client.uploadOriginal(id, kept, jpeg(40_000))).status).toBe(200);
    const over = await carol.client.uploadOriginal(
      id,
      removed,
      jpeg(100_000 - pdfBytes - 40_000 + 1),
    );
    expect(over.status).toBe(413);
    expect(over.body?.error?.code).toBe('QUOTA_EXCEEDED');
    expect((await carol.client.uploadOriginal(id, removed, jpeg(30_000))).status).toBe(200);
    expect(await usedBytes(carol)).toBe(pdfBytes + 70_000);

    const removedKey = (
      await mainDb().documentPage.findFirstOrThrow({
        where: { document: { publicId: id }, publicId: removed },
      })
    ).originalObjectKey!;
    const after = await putPages(carol, id, [pageInput(kept, 0)]);
    expect(after.pages.map((page) => page.id)).toEqual([kept]);
    expect(await bucket().exists(removedKey)).toBe(false);
    expect(await usedBytes(carol)).toBe(pdfBytes + 40_000);
    expect((await carol.client.get<Document>(`/api/v1/documents/${id}`)).body!.data.pageCount).toBe(
      1,
    );
    // A new PDF replaces the PDF only; page originals stay.
    expect((await carol.client.uploadPdf(id, Buffer.from('%PDF-1.7\nnew'))).status).toBe(200);
    expect(
      (await carol.client.get<DocumentPages>(`/api/v1/documents/${id}/pages`)).body!.data.pages[0]!
        .hasOriginal,
    ).toBe(true);
  });
});

describe('isolation and deletion', () => {
  it("answers 404 NOT_FOUND on every page route for another user's document", async () => {
    const { id } = await createDocument(alice);
    const pageId = randomUUID();
    await putPages(alice, id, [pageInput(pageId, 0)]);
    const photo = jpeg();
    expect((await alice.client.uploadOriginal(id, pageId, photo)).status).toBe(200);

    const attempts = [
      await bob.client.get(`/api/v1/documents/${id}/pages`),
      await bob.client.put(`/api/v1/documents/${id}/pages`, { pages: [pageInput(pageId, 0)] }),
      await bob.client.uploadOriginal(id, pageId, jpeg()),
      await bob.client.get(`/api/v1/documents/${id}/pages/${pageId}/original`),
    ];
    for (const response of attempts) {
      expect(response.status).toBe(404);
      expect(response.body).toEqual({
        data: null,
        error: { code: 'NOT_FOUND', message: 'Not found.' },
        meta: { nextCursor: null },
      });
    }
    // Nothing changed for the owner.
    const mine = (await alice.client.get<DocumentPages>(`/api/v1/documents/${id}/pages`)).body!
      .data;
    expect(mine.pages).toHaveLength(1);
    expect(
      (await alice.client.get(`/api/v1/documents/${id}/pages/${pageId}/original`)).raw.equals(
        photo,
      ),
    ).toBe(true);
  });

  it('hides the pages of a deleted document', async () => {
    const { id } = await createDocument(alice);
    const pageId = randomUUID();
    await putPages(alice, id, [pageInput(pageId, 0)]);
    expect((await alice.client.uploadOriginal(id, pageId, jpeg())).status).toBe(200);
    expect((await alice.client.delete(`/api/v1/documents/${id}`)).status).toBe(200);

    for (const response of [
      await alice.client.get(`/api/v1/documents/${id}/pages`),
      await alice.client.put(`/api/v1/documents/${id}/pages`, { pages: [pageInput(pageId, 0)] }),
      await alice.client.uploadOriginal(id, pageId, jpeg()),
      await alice.client.get(`/api/v1/documents/${id}/pages/${pageId}/original`),
    ]) {
      expect(response.status).toBe(404);
    }
    // The rows and the object stay until the purge (worker tests cover it).
    expect(await mainDb().documentPage.count({ where: { document: { publicId: id } } })).toBe(1);
  });

  it('scopes page ids to their document', async () => {
    const first = await createDocument(alice);
    const second = await createDocument(alice);
    const shared = randomUUID();
    await putPages(alice, first.id, [pageInput(shared, 0, recipe('greyscale'))]);
    await putPages(alice, second.id, [pageInput(shared, 0, recipe('original'))]);
    const pages = async (documentId: string): Promise<DocumentPage[]> =>
      (await alice.client.get<DocumentPages>(`/api/v1/documents/${documentId}/pages`)).body!.data
        .pages;
    expect((await pages(first.id))[0]!.edit).toEqual(recipe('greyscale'));
    expect((await pages(second.id))[0]!.edit).toEqual(recipe('original'));
  });
});
