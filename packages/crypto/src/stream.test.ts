import { createHash, randomBytes } from 'node:crypto';
import { Readable } from 'node:stream';
import { describe, expect, it } from 'vitest';
import { CryptoError } from './errors.js';
import {
  SCNV1_HEADER_BYTES,
  createScnv1DecryptStream,
  createScnv1EncryptStream,
  scnv1CiphertextLength,
} from './stream.js';

// Fixed vectors were produced by an independent implementation (Python `cryptography`).
const OBJECT_ID = 'c2a4e6f8-1b3d-4f5a-8c7e-9d0b1a2c3e4f';
const KEY = Buffer.from('9fb23dedb79f73770036a383417a121f741e180a26303b071a118a81255a79c9', 'hex');
const NONCE_PREFIX = Buffer.from('b0b1b2b3b4b5b6', 'hex');
const TAG = 16;

function split(data: Buffer, size: number): Buffer[] {
  if (size <= 0) {
    return [data];
  }
  const parts: Buffer[] = [];
  for (let offset = 0; offset < data.length; offset += size) {
    parts.push(data.subarray(offset, offset + size));
  }
  return parts;
}

async function collect(stream: AsyncIterable<Buffer>): Promise<Buffer> {
  const parts: Buffer[] = [];
  for await (const part of stream) {
    parts.push(part);
  }
  return Buffer.concat(parts);
}

async function encrypt(
  plaintext: Buffer,
  options: {
    chunkLog2?: number;
    writeSize?: number;
    noncePrefix?: Buffer;
    objectId?: string;
    key?: Buffer;
  } = {},
): Promise<Buffer> {
  const stream = createScnv1EncryptStream({
    key: options.key ?? KEY,
    objectId: options.objectId ?? OBJECT_ID,
    chunkLog2: options.chunkLog2,
    noncePrefix: options.noncePrefix,
  });
  return collect(Readable.from(split(plaintext, options.writeSize ?? 0)).pipe(stream));
}

/** Decrypts and also reports what was released before a failure. */
async function decrypt(
  ciphertext: Buffer,
  options: { writeSize?: number; objectId?: string; key?: Buffer } = {},
): Promise<{ plaintext: Buffer; released: Buffer; error: unknown }> {
  const stream = createScnv1DecryptStream({
    key: options.key ?? KEY,
    objectId: options.objectId ?? OBJECT_ID,
  });
  const released: Buffer[] = [];
  let error: unknown = null;
  const source = Readable.from(split(ciphertext, options.writeSize ?? 0));
  source.pipe(stream);
  try {
    for await (const part of stream) {
      released.push(part as Buffer);
    }
  } catch (caught) {
    error = caught;
  }
  const joined = Buffer.concat(released);
  return { plaintext: joined, released: joined, error };
}

async function expectRejected(
  ciphertext: Buffer,
  options: { objectId?: string; key?: Buffer } = {},
): Promise<CryptoError> {
  const result = await decrypt(ciphertext, options);
  expect(result.error).toBeInstanceOf(CryptoError);
  return result.error as CryptoError;
}

describe('SCNV1 fixed vectors', () => {
  it('encrypts an empty file as a header plus one empty final chunk', async () => {
    const ciphertext = await encrypt(Buffer.alloc(0), { noncePrefix: NONCE_PREFIX });
    expect(ciphertext.toString('hex')).toBe(
      '53434e560101100000b0b1b2b3b4b5b6e4350d5d506ac847e0ac7706963c7724',
    );
    expect(ciphertext.length).toBe(SCNV1_HEADER_BYTES + TAG);
  });

  it('encrypts a single short chunk', async () => {
    const ciphertext = await encrypt(Buffer.from('%PDF-1.7 hello scanova'), {
      noncePrefix: NONCE_PREFIX,
    });
    expect(ciphertext.toString('hex')).toBe(
      '53434e560101100000b0b1b2b3b4b5b6f7fb6e58cedf4ad21328b96a8cf9fdc540aa79ecb7c3d965b538d836390d72f18965a961d282',
    );
  });

  it('encrypts three chunks (1024 + 1024 + 452) identically to the reference', async () => {
    const plaintext = Buffer.from(Array.from({ length: 2500 }, (_, index) => index % 251));
    const ciphertext = await encrypt(plaintext, {
      noncePrefix: NONCE_PREFIX,
      chunkLog2: 10,
      writeSize: 333,
    });
    expect(ciphertext.length).toBe(2564);
    expect(ciphertext.subarray(0, 48).toString('hex')).toBe(
      '53434e5601010a0000b0b1b2b3b4b5b6b269f9e4e1bdb16f20fb84ae16e1ef849638b87950ecdd8ef1723d0fc7cf8b7c',
    );
    expect(createHash('sha256').update(ciphertext).digest('hex')).toBe(
      'aef9da0f6674ddbf21ff067986edb56700aebb2bed30db36163a06156db0af77',
    );
  });

  it('marks a full last chunk as final instead of appending an empty one', async () => {
    const plaintext = Buffer.from(Array.from({ length: 2048 }, (_, index) => index % 7));
    const ciphertext = await encrypt(plaintext, { noncePrefix: NONCE_PREFIX, chunkLog2: 10 });
    expect(ciphertext.length).toBe(2096);
    expect(createHash('sha256').update(ciphertext).digest('hex')).toBe(
      'e4126ca0e625c96cbb6dd3d0c5adc410024e915d5e05a48959b204b63a46bebc',
    );
  });

  it('decrypts the reference vectors', async () => {
    const hello = Buffer.from(
      '53434e560101100000b0b1b2b3b4b5b6f7fb6e58cedf4ad21328b96a8cf9fdc540aa79ecb7c3d965b538d836390d72f18965a961d282',
      'hex',
    );
    const result = await decrypt(hello);
    expect(result.error).toBeNull();
    expect(result.plaintext.toString()).toBe('%PDF-1.7 hello scanova');
  });
});

describe('SCNV1 round trips across chunk boundaries', () => {
  const CHUNK = 1024;
  const sizes = [
    0,
    1,
    CHUNK - 1,
    CHUNK,
    CHUNK + 1,
    2 * CHUNK - 1,
    2 * CHUNK,
    2 * CHUNK + 1,
    5 * CHUNK + 123,
  ];
  const writeSizes = [0, 1, 7, 1000, CHUNK, CHUNK + 16, 4096];

  for (const size of sizes) {
    it(`${size} bytes with 1 KiB chunks, under every write pattern`, async () => {
      const plaintext = randomBytes(size);
      for (const writeSize of writeSizes) {
        const ciphertext = await encrypt(plaintext, { chunkLog2: 10, writeSize });
        expect(ciphertext.length).toBe(scnv1CiphertextLength(size, 10));
        for (const readSize of writeSizes) {
          const result = await decrypt(ciphertext, { writeSize: readSize });
          expect(result.error).toBeNull();
          expect(result.plaintext.equals(plaintext)).toBe(true);
        }
      }
    });
  }

  const DEFAULT = 65_536;
  for (const size of [0, 1, DEFAULT, DEFAULT + 1, 3 * DEFAULT + 17]) {
    it(`${size} bytes with the default 64 KiB chunks`, async () => {
      const plaintext = randomBytes(size);
      const ciphertext = await encrypt(plaintext, { writeSize: 16_384 });
      expect(ciphertext.length).toBe(scnv1CiphertextLength(size));
      expect(ciphertext[6]).toBe(16);
      const result = await decrypt(ciphertext, { writeSize: 10_000 });
      expect(result.error).toBeNull();
      expect(result.plaintext.equals(plaintext)).toBe(true);
    });
  }

  it('uses a fresh nonce prefix for every object', async () => {
    const a = await encrypt(Buffer.from('same'));
    const b = await encrypt(Buffer.from('same'));
    expect(a.subarray(9, 16).equals(b.subarray(9, 16))).toBe(false);
    expect(a.equals(b)).toBe(false);
  });
});

describe('SCNV1 tampering', () => {
  const CHUNK = 1024;
  const CIPHER_CHUNK = CHUNK + TAG;
  const plaintext = Buffer.from(
    Array.from({ length: 3 * CHUNK + 100 }, (_, index) => (index * 31) % 256),
  );

  async function reference(): Promise<Buffer> {
    return encrypt(plaintext, { chunkLog2: 10 });
  }

  function chunkAt(ciphertext: Buffer, index: number): Buffer {
    const start = SCNV1_HEADER_BYTES + index * CIPHER_CHUNK;
    return ciphertext.subarray(start, Math.min(start + CIPHER_CHUNK, ciphertext.length));
  }

  it('rejects a flipped bit anywhere: header, ciphertext or tag', async () => {
    const ciphertext = await reference();
    const positions = [
      0, // magic
      4, // version
      5, // algorithm
      6, // chunk size
      7, // reserved
      9, // nonce prefix
      15, // nonce prefix (last byte)
      SCNV1_HEADER_BYTES, // first ciphertext byte
      SCNV1_HEADER_BYTES + CHUNK, // first byte of the first tag
      SCNV1_HEADER_BYTES + CIPHER_CHUNK + 5, // inside the second chunk
      ciphertext.length - TAG - 1, // last ciphertext byte
      ciphertext.length - 1, // last tag byte
    ];
    for (const position of positions) {
      for (const bit of [0x01, 0x80]) {
        const tampered = Buffer.from(ciphertext);
        tampered[position] = tampered[position]! ^ bit;
        const error = await expectRejected(tampered);
        expect(['AUTHENTICATION_FAILED', 'INVALID_FORMAT']).toContain(error.code);
      }
    }
  });

  it('releases only chunks that verified, never bytes of the tampered chunk or after it', async () => {
    const ciphertext = await reference();
    const tampered = Buffer.from(ciphertext);
    const insideThird = SCNV1_HEADER_BYTES + 2 * CIPHER_CHUNK + 10;
    tampered[insideThird] = tampered[insideThird]! ^ 0x01;
    for (const writeSize of [0, 100, CIPHER_CHUNK]) {
      const result = await decrypt(tampered, { writeSize });
      expect(result.error).toBeInstanceOf(CryptoError);
      expect((result.error as CryptoError).code).toBe('AUTHENTICATION_FAILED');
      // Whatever reached the consumer is a whole-chunk prefix that ends before the bad chunk.
      expect(result.released.length % CHUNK).toBe(0);
      expect(result.released.length).toBeLessThanOrEqual(2 * CHUNK);
      expect(result.released.equals(plaintext.subarray(0, result.released.length))).toBe(true);
    }
    // Fed chunk by chunk, both good chunks are delivered before the failure.
    const stream = createScnv1DecryptStream({ key: KEY, objectId: OBJECT_ID });
    const seen: Buffer[] = [];
    stream.on('data', (part: Buffer) => seen.push(part));
    const failure = new Promise<unknown>((resolve) => stream.once('error', resolve));
    stream.write(tampered.subarray(0, SCNV1_HEADER_BYTES + 2 * CIPHER_CHUNK + 1));
    await new Promise((resolve) => setImmediate(resolve));
    expect(Buffer.concat(seen).equals(plaintext.subarray(0, 2 * CHUNK))).toBe(true);
    stream.end(tampered.subarray(SCNV1_HEADER_BYTES + 2 * CIPHER_CHUNK + 1));
    expect(await failure).toBeInstanceOf(CryptoError);
    expect(Buffer.concat(seen).length).toBe(2 * CHUNK);
  });

  it('rejects truncation at every kind of position', async () => {
    const ciphertext = await reference();
    const cuts = [
      0, // nothing at all
      5, // inside the header
      SCNV1_HEADER_BYTES, // header only
      SCNV1_HEADER_BYTES + 8, // less than one tag
      SCNV1_HEADER_BYTES + CIPHER_CHUNK, // exactly one whole chunk: a chunk boundary
      SCNV1_HEADER_BYTES + 3 * CIPHER_CHUNK, // the final chunk removed entirely
      ciphertext.length - 1, // one byte short
    ];
    for (const cut of cuts) {
      const error = await expectRejected(ciphertext.subarray(0, cut));
      expect(['AUTHENTICATION_FAILED', 'INVALID_FORMAT']).toContain(error.code);
    }
  });

  it('rejects reordered chunks', async () => {
    const ciphertext = await reference();
    const swapped = Buffer.concat([
      ciphertext.subarray(0, SCNV1_HEADER_BYTES),
      chunkAt(ciphertext, 1),
      chunkAt(ciphertext, 0),
      chunkAt(ciphertext, 2),
      chunkAt(ciphertext, 3),
    ]);
    expect(swapped.length).toBe(ciphertext.length);
    expect((await expectRejected(swapped)).code).toBe('AUTHENTICATION_FAILED');
  });

  it('rejects a duplicated, a dropped and an appended chunk', async () => {
    const ciphertext = await reference();
    const header = ciphertext.subarray(0, SCNV1_HEADER_BYTES);
    const duplicated = Buffer.concat([
      header,
      chunkAt(ciphertext, 0),
      chunkAt(ciphertext, 0),
      chunkAt(ciphertext, 1),
      chunkAt(ciphertext, 2),
      chunkAt(ciphertext, 3),
    ]);
    const dropped = Buffer.concat([
      header,
      chunkAt(ciphertext, 0),
      chunkAt(ciphertext, 2),
      chunkAt(ciphertext, 3),
    ]);
    const appended = Buffer.concat([ciphertext, chunkAt(ciphertext, 3)]);
    for (const tampered of [duplicated, dropped, appended]) {
      expect((await expectRejected(tampered)).code).toBe('AUTHENTICATION_FAILED');
    }
  });

  it('rejects a chunk spliced in from another object under the same key', async () => {
    const ciphertext = await reference();
    const other = await encrypt(plaintext, { chunkLog2: 10 });
    const spliced = Buffer.concat([
      ciphertext.subarray(0, SCNV1_HEADER_BYTES),
      chunkAt(ciphertext, 0),
      chunkAt(other, 1),
      chunkAt(ciphertext, 2),
      chunkAt(ciphertext, 3),
    ]);
    expect((await expectRejected(spliced)).code).toBe('AUTHENTICATION_FAILED');
  });

  it('rejects the wrong object id (associated data)', async () => {
    const ciphertext = await reference();
    const error = await expectRejected(ciphertext, {
      objectId: 'c2a4e6f8-1b3d-4f5a-8c7e-9d0b1a2c3e40',
    });
    expect(error.code).toBe('AUTHENTICATION_FAILED');
  });

  it('rejects the wrong key', async () => {
    const ciphertext = await reference();
    const wrongKey = Buffer.from(KEY);
    wrongKey[0] = wrongKey[0]! ^ 0x01;
    expect((await expectRejected(ciphertext, { key: wrongKey })).code).toBe(
      'AUTHENTICATION_FAILED',
    );
  });

  it('rejects a header that declares an out-of-range chunk size', async () => {
    const ciphertext = await reference();
    for (const chunkLog2 of [0, 9, 25, 255]) {
      const tampered = Buffer.from(ciphertext);
      tampered[6] = chunkLog2;
      expect((await expectRejected(tampered)).code).toBe('INVALID_FORMAT');
    }
  });

  it('rejects invalid parameters up front', () => {
    expect(() => createScnv1EncryptStream({ key: Buffer.alloc(16), objectId: OBJECT_ID })).toThrow(
      CryptoError,
    );
    expect(() => createScnv1EncryptStream({ key: KEY, objectId: '' })).toThrow(CryptoError);
    expect(() =>
      createScnv1EncryptStream({ key: KEY, objectId: OBJECT_ID, chunkLog2: 30 }),
    ).toThrow(CryptoError);
    expect(() =>
      createScnv1EncryptStream({ key: KEY, objectId: OBJECT_ID, noncePrefix: Buffer.alloc(8) }),
    ).toThrow(CryptoError);
    expect(() => createScnv1DecryptStream({ key: Buffer.alloc(31), objectId: OBJECT_ID })).toThrow(
      CryptoError,
    );
  });

  it("does not keep the caller's key buffer: the caller may wipe it immediately", async () => {
    const key = Buffer.from(KEY);
    const stream = createScnv1EncryptStream({
      key,
      objectId: OBJECT_ID,
      noncePrefix: NONCE_PREFIX,
    });
    key.fill(0);
    const ciphertext = await collect(
      Readable.from([Buffer.from('%PDF-1.7 hello scanova')]).pipe(stream),
    );
    expect(ciphertext.toString('hex')).toBe(
      '53434e560101100000b0b1b2b3b4b5b6f7fb6e58cedf4ad21328b96a8cf9fdc540aa79ecb7c3d965b538d836390d72f18965a961d282',
    );
  });
});
