import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { Readable, Transform } from 'node:stream';
import { ListMultipartUploadsCommand } from '@aws-sdk/client-s3';
import type { StorageConfig } from '@scanova/config/sections';
import { readIntegrationState } from '@scanova/tooling/integration/state';
import { afterAll, describe, expect, it } from 'vitest';
import { EncryptedObjectStore } from '../../src/encrypted-object-store.js';
import { objectKeyFor, userObjectPrefix } from '../../src/object-keys.js';
import { S3ObjectStore, createS3Client } from '../../src/s3-object-store.js';

const state = readIntegrationState();
const config: StorageConfig = {
  endpoint: state.s3.endpoint,
  region: state.s3.region,
  bucket: state.s3.bucket,
  forcePathStyle: true,
  accessKeyId: state.s3.accessKeyId,
  secretAccessKey: state.s3.secretAccessKey,
};

const client = createS3Client(config);
const raw = new S3ObjectStore(config, client);
const store = new EncryptedObjectStore(raw);

afterAll(() => {
  raw.close();
});

async function collect(stream: Readable): Promise<Buffer> {
  const chunks: Buffer[] = [];
  for await (const chunk of stream) {
    chunks.push(chunk as Buffer);
  }
  return Buffer.concat(chunks);
}

/** A stream of `total` pseudo-random bytes that is never held in memory as a whole. */
function generated(total: number, hash: ReturnType<typeof createHash>): Readable {
  let remaining = total;
  return new Readable({
    read() {
      if (remaining === 0) {
        this.push(null);
        return;
      }
      const chunk = randomBytes(Math.min(64 * 1024, remaining));
      remaining -= chunk.length;
      hash.update(chunk);
      this.push(chunk);
    },
  });
}

function ids(): { userId: string; documentId: string; objectId: string } {
  return { userId: randomUUID(), documentId: randomUUID(), objectId: randomUUID() };
}

describe('S3ObjectStore against Garage', () => {
  it('is healthy with the provisioned credentials and unhealthy with wrong ones', async () => {
    await expect(raw.health()).resolves.toBeUndefined();
    const wrong = new S3ObjectStore({ ...config, secretAccessKey: 'f'.repeat(64) });
    await expect(wrong.health()).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    wrong.close();
  });

  it('puts, gets, lists, checks and deletes objects', async () => {
    const one = ids();
    const two = { ...one, objectId: randomUUID() };
    const keyOne = objectKeyFor(one);
    const keyTwo = objectKeyFor(two);
    await raw.put(keyOne, Readable.from([Buffer.from('first')]));
    await raw.put(keyTwo, Readable.from([Buffer.from('second')]));

    expect((await collect(await raw.get(keyOne))).toString()).toBe('first');
    expect(await raw.exists(keyOne)).toBe(true);
    const listed: string[] = [];
    for await (const key of raw.list(userObjectPrefix(one.userId))) {
      listed.push(key);
    }
    expect(listed.sort()).toEqual([keyOne, keyTwo].sort());

    await raw.delete(keyOne);
    await raw.delete(keyOne);
    expect(await raw.exists(keyOne)).toBe(false);
    await expect(raw.get(keyOne)).rejects.toMatchObject({ code: 'NOT_FOUND' });
    await raw.deleteMany([keyTwo, keyOne]);
    expect(await raw.exists(keyTwo)).toBe(false);
  });
});

describe('EncryptedObjectStore against Garage', () => {
  it('round-trips a 20 MiB object through a multipart upload with a matching checksum', async () => {
    const ref = ids();
    const objectKey = objectKeyFor(ref);
    const streamKey = randomBytes(32);
    const sent = createHash('sha256');
    const total = 20 * 1024 * 1024 + 12_345;
    await store.put({ objectKey, objectId: ref.objectId, streamKey }, generated(total, sent));

    // What is at rest is SCNV1, not the plaintext.
    const head = (await collect(await raw.get(objectKey))).subarray(0, 16);
    expect(head.subarray(0, 4).toString('ascii')).toBe('SCNV');

    const received = createHash('sha256');
    let bytes = 0;
    for await (const chunk of await store.get({ objectKey, objectId: ref.objectId, streamKey })) {
      received.update(chunk as Buffer);
      bytes += (chunk as Buffer).length;
    }
    expect(bytes).toBe(total);
    expect(received.digest('hex')).toBe(sent.digest('hex'));
    await store.delete(objectKey);
  });

  it('round-trips an empty object and a tiny one', async () => {
    for (const plaintext of [Buffer.alloc(0), Buffer.from('%PDF-1.7 tiny')]) {
      const ref = ids();
      const objectKey = objectKeyFor(ref);
      const streamKey = randomBytes(32);
      await store.put(
        { objectKey, objectId: ref.objectId, streamKey },
        Readable.from(plaintext.length === 0 ? [] : [plaintext]),
      );
      const read = await collect(await store.get({ objectKey, objectId: ref.objectId, streamKey }));
      expect(read.equals(plaintext)).toBe(true);
      await store.delete(objectKey);
    }
  });

  it('leaves no object and no dangling multipart upload when the source fails late', async () => {
    const ref = ids();
    const objectKey = objectKeyFor(ref);
    class LateFailure extends Error {}
    const failing = new Transform({
      transform(chunk, _encoding, done) {
        done(null, chunk);
      },
      flush(done) {
        done(new LateFailure('late failure'));
      },
    });
    // Larger than one part, so a multipart upload has really started.
    generated(12 * 1024 * 1024, createHash('sha256')).pipe(failing);
    await expect(
      store.put({ objectKey, objectId: ref.objectId, streamKey: randomBytes(32) }, failing),
    ).rejects.toBeInstanceOf(LateFailure);
    expect(await raw.exists(objectKey)).toBe(false);
    const uploads = await client.send(
      new ListMultipartUploadsCommand({ Bucket: config.bucket, Prefix: objectKey }),
    );
    expect(uploads.Uploads ?? []).toEqual([]);
  });

  it('cannot be read with another key', async () => {
    const ref = ids();
    const objectKey = objectKeyFor(ref);
    await store.put(
      { objectKey, objectId: ref.objectId, streamKey: randomBytes(32) },
      Readable.from([randomBytes(100_000)]),
    );
    await expect(
      collect(await store.get({ objectKey, objectId: ref.objectId, streamKey: randomBytes(32) })),
    ).rejects.toMatchObject({ code: 'AUTHENTICATION_FAILED' });
    await store.delete(objectKey);
  });
});
