import { createHash, randomBytes } from 'node:crypto';
import { PassThrough, Readable, Transform } from 'node:stream';
import { CryptoError } from '@scanova/crypto/errors';
import { describe, expect, it } from 'vitest';
import { EncryptedObjectStore } from './encrypted-object-store.js';
import { StorageError } from './errors.js';
import { InMemoryObjectStore } from './in-memory-object-store.js';
import { documentObjectPrefix, objectKeyFor, userObjectPrefix } from './object-keys.js';

const USER_ID = '0b0e7a52-5c1d-4c53-9f0a-3d2e1f4a5b6c';
const DOCUMENT_ID = '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f';
const OBJECT_ID = 'c2a4e6f8-1b3d-4f5a-8c7e-9d0b1a2c3e4f';
const OBJECT_KEY = objectKeyFor({ userId: USER_ID, documentId: DOCUMENT_ID, objectId: OBJECT_ID });

async function collect(stream: Readable): Promise<Buffer> {
  const chunks: Buffer[] = [];
  for await (const chunk of stream) {
    chunks.push(chunk as Buffer);
  }
  return Buffer.concat(chunks);
}

function setup(): { raw: InMemoryObjectStore; store: EncryptedObjectStore; streamKey: Buffer } {
  const raw = new InMemoryObjectStore();
  return { raw, store: new EncryptedObjectStore(raw), streamKey: randomBytes(32) };
}

describe('object keys', () => {
  it('are built from ids only and nest under the user prefix', () => {
    expect(OBJECT_KEY).toBe(`u/${USER_ID}/${DOCUMENT_ID}/${OBJECT_ID}`);
    expect(OBJECT_KEY.startsWith(userObjectPrefix(USER_ID))).toBe(true);
    expect(OBJECT_KEY.startsWith(documentObjectPrefix(USER_ID, DOCUMENT_ID))).toBe(true);
    expect(() =>
      objectKeyFor({ userId: '../etc', documentId: DOCUMENT_ID, objectId: OBJECT_ID }),
    ).toThrow();
  });
});

describe('EncryptedObjectStore', () => {
  it('stores SCNV1 ciphertext and returns the plaintext', async () => {
    const { raw, store, streamKey } = setup();
    const plaintext = Buffer.concat([Buffer.from('%PDF-1.7\n'), randomBytes(200_000)]);
    await store.put(
      { objectKey: OBJECT_KEY, objectId: OBJECT_ID, streamKey },
      Readable.from([plaintext]),
    );

    const atRest = raw.raw(OBJECT_KEY)!;
    expect(atRest.subarray(0, 4).toString('ascii')).toBe('SCNV');
    expect(atRest.includes(Buffer.from('%PDF-'))).toBe(false);
    expect(atRest.length).toBeGreaterThan(plaintext.length);

    const read = await collect(
      await store.get({ objectKey: OBJECT_KEY, objectId: OBJECT_ID, streamKey }),
    );
    expect(read.equals(plaintext)).toBe(true);
  });

  it('round-trips an empty object', async () => {
    const { store, streamKey } = setup();
    await store.put({ objectKey: OBJECT_KEY, objectId: OBJECT_ID, streamKey }, Readable.from([]));
    const read = await collect(
      await store.get({ objectKey: OBJECT_KEY, objectId: OBJECT_ID, streamKey }),
    );
    expect(read).toHaveLength(0);
  });

  it('lets the caller wipe the key as soon as put() has been called', async () => {
    const { store, streamKey } = setup();
    const copy = Buffer.from(streamKey);
    const pending = store.put(
      { objectKey: OBJECT_KEY, objectId: OBJECT_ID, streamKey },
      Readable.from([Buffer.from('data')]),
    );
    streamKey.fill(0);
    await pending;
    const read = await collect(
      await store.get({ objectKey: OBJECT_KEY, objectId: OBJECT_ID, streamKey: copy }),
    );
    expect(read.toString()).toBe('data');
  });

  it('stores nothing and rethrows the original error when the source fails at its end', async () => {
    const { raw, store, streamKey } = setup();
    class ChecksumMismatch extends Error {}
    const verifying = new Transform({
      transform(chunk, _encoding, done) {
        done(null, chunk);
      },
      flush(done) {
        done(new ChecksumMismatch('checksum mismatch'));
      },
    });
    Readable.from([randomBytes(150_000)]).pipe(verifying);
    await expect(
      store.put({ objectKey: OBJECT_KEY, objectId: OBJECT_ID, streamKey }, verifying),
    ).rejects.toBeInstanceOf(ChecksumMismatch);
    expect(raw.keys()).toEqual([]);
  });

  it('fails with a storage error and stops the sender when the store is down', async () => {
    const { raw, store, streamKey } = setup();
    raw.setAvailable(false);
    const source = new PassThrough();
    source.write(randomBytes(1000));
    const pending = store.put({ objectKey: OBJECT_KEY, objectId: OBJECT_ID, streamKey }, source);
    await expect(pending).rejects.toBeInstanceOf(StorageError);
    expect(source.destroyed).toBe(true);
    raw.setAvailable(true);
    expect(raw.keys()).toEqual([]);
  });

  it('fails authentication on tampered bytes, the wrong key or the wrong object id', async () => {
    const { raw, store, streamKey } = setup();
    const plaintext = randomBytes(100_000);
    const ref = { objectKey: OBJECT_KEY, objectId: OBJECT_ID, streamKey };
    await store.put(ref, Readable.from([plaintext]));

    await expect(
      collect(await store.get({ ...ref, streamKey: randomBytes(32) })),
    ).rejects.toBeInstanceOf(CryptoError);
    await expect(
      collect(await store.get({ ...ref, objectId: 'c2a4e6f8-1b3d-4f5a-8c7e-9d0b1a2c3e40' })),
    ).rejects.toBeInstanceOf(CryptoError);

    const tampered = Buffer.from(raw.raw(OBJECT_KEY)!);
    tampered[70_000] = tampered[70_000]! ^ 0x01;
    raw.overwrite(OBJECT_KEY, tampered);
    await expect(collect(await store.get(ref))).rejects.toBeInstanceOf(CryptoError);

    raw.overwrite(OBJECT_KEY, tampered.subarray(0, 65_552 + 16));
    await expect(collect(await store.get(ref))).rejects.toBeInstanceOf(CryptoError);
  });

  it('reports a missing object as NOT_FOUND', async () => {
    const { store, streamKey } = setup();
    await expect(
      store.get({ objectKey: OBJECT_KEY, objectId: OBJECT_ID, streamKey }),
    ).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
  });
});

describe('InMemoryObjectStore', () => {
  it('lists by prefix, deletes idempotently and reports existence', async () => {
    const raw = new InMemoryObjectStore();
    await raw.put('u/a/1', Readable.from([Buffer.from('1')]));
    await raw.put('u/a/2', Readable.from([Buffer.from('2')]));
    await raw.put('u/b/1', Readable.from([Buffer.from('3')]));
    const listed: string[] = [];
    for await (const key of raw.list('u/a/')) {
      listed.push(key);
    }
    expect(listed).toEqual(['u/a/1', 'u/a/2']);
    expect(await raw.exists('u/a/1')).toBe(true);
    await raw.delete('u/a/1');
    await raw.delete('u/a/1');
    expect(await raw.exists('u/a/1')).toBe(false);
    await raw.deleteMany(['u/a/2', 'u/b/1', 'missing']);
    expect(raw.keys()).toEqual([]);
    expect(createHash('sha256').update('x').digest('hex')).toHaveLength(64);
  });
});
