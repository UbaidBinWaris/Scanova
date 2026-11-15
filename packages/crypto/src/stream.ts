import { Transform, type TransformCallback } from 'node:stream';
import { randomBytes } from 'node:crypto';
import { AEAD_KEY_BYTES, AEAD_TAG_BYTES, aesGcmDecrypt, aesGcmEncrypt } from './aead.js';
import { CryptoError } from './errors.js';

/**
 * Object stream format SCNV1 (§5.3): authenticated encryption of a file of any
 * size in constant memory.
 *
 *   header (16) = "SCNV" ‖ version 0x01 ‖ alg 0x01 ‖ chunkLog2 ‖ 0x00 0x00 ‖ noncePrefix(7)
 *   chunk i     = AES-256-GCM(key, nonce = noncePrefix ‖ counter_i (u32 BE) ‖ final flag,
 *                             aad = header ‖ objectId) → ciphertext ‖ tag(16)
 *
 * Every chunk but the last holds exactly 2^chunkLog2 plaintext bytes. Exactly one
 * chunk carries the final flag and it is the last, so truncation (even at a chunk
 * boundary), reordering, duplication and splicing all fail authentication.
 */

export const SCNV1_MAGIC = Buffer.from('SCNV', 'ascii');
export const SCNV1_VERSION = 0x01;
export const SCNV1_ALG_AES_256_GCM = 0x01;
export const SCNV1_HEADER_BYTES = 16;
export const SCNV1_NONCE_PREFIX_BYTES = 7;
/** 64 KiB plaintext chunks. */
export const SCNV1_DEFAULT_CHUNK_LOG2 = 16;
/** Bounds on the chunk size a header may declare: 1 KiB … 16 MiB. Caps decryptor memory. */
export const SCNV1_MIN_CHUNK_LOG2 = 10;
export const SCNV1_MAX_CHUNK_LOG2 = 24;

const MAX_CHUNK_COUNT = 2 ** 32;
const FINAL_FLAG = 0x01;
const NOT_FINAL_FLAG = 0x00;

export interface Scnv1EncryptOptions {
  /** 32-byte stream key: `HKDF(DOCUMENT key, "scanova/v1/object|<objectId>")`. Copied; the caller may wipe its own. */
  readonly key: Uint8Array;
  /** Public id of the stored object; part of every chunk's associated data. */
  readonly objectId: string;
  readonly chunkLog2?: number;
  /** For fixed test vectors only. Production always uses a fresh random prefix. */
  readonly noncePrefix?: Uint8Array;
}

export interface Scnv1DecryptOptions {
  readonly key: Uint8Array;
  readonly objectId: string;
}

function assertStreamKey(key: Uint8Array): void {
  if (key.length !== AEAD_KEY_BYTES) {
    throw new CryptoError('INVALID_INPUT', 'An SCNV1 stream key must be 32 bytes.');
  }
}

function assertChunkLog2(chunkLog2: number): void {
  if (
    !Number.isInteger(chunkLog2) ||
    chunkLog2 < SCNV1_MIN_CHUNK_LOG2 ||
    chunkLog2 > SCNV1_MAX_CHUNK_LOG2
  ) {
    throw new CryptoError('INVALID_FORMAT', 'Unsupported SCNV1 chunk size.');
  }
}

function buildHeader(chunkLog2: number, noncePrefix: Uint8Array): Buffer {
  const header = Buffer.alloc(SCNV1_HEADER_BYTES);
  SCNV1_MAGIC.copy(header, 0);
  header[4] = SCNV1_VERSION;
  header[5] = SCNV1_ALG_AES_256_GCM;
  header[6] = chunkLog2;
  // bytes 7 and 8 are reserved and zero
  Buffer.from(noncePrefix).copy(header, 9);
  return header;
}

function chunkNonce(noncePrefix: Uint8Array, counter: number, final: boolean): Buffer {
  const nonce = Buffer.alloc(12);
  Buffer.from(noncePrefix).copy(nonce, 0);
  nonce.writeUInt32BE(counter, SCNV1_NONCE_PREFIX_BYTES);
  nonce[11] = final ? FINAL_FLAG : NOT_FINAL_FLAG;
  return nonce;
}

/** Bytes of an SCNV1 object holding `plaintextLength` bytes. */
export function scnv1CiphertextLength(
  plaintextLength: number,
  chunkLog2: number = SCNV1_DEFAULT_CHUNK_LOG2,
): number {
  assertChunkLog2(chunkLog2);
  const chunkSize = 2 ** chunkLog2;
  const chunks = Math.max(1, Math.ceil(plaintextLength / chunkSize));
  return SCNV1_HEADER_BYTES + plaintextLength + chunks * AEAD_TAG_BYTES;
}

/** FIFO of pending bytes that hands out exact-size slices without re-copying large writes. */
class ByteQueue {
  private parts: Buffer[] = [];
  private size = 0;

  public get length(): number {
    return this.size;
  }

  public push(chunk: Buffer): void {
    if (chunk.length > 0) {
      this.parts.push(chunk);
      this.size += chunk.length;
    }
  }

  public take(count: number): Buffer {
    const joined = this.parts.length === 1 ? this.parts[0]! : Buffer.concat(this.parts, this.size);
    const head = joined.subarray(0, count);
    const rest = joined.subarray(count);
    this.parts = rest.length > 0 ? [rest] : [];
    this.size = rest.length;
    return head;
  }
}

function toBuffer(chunk: unknown): Buffer {
  if (Buffer.isBuffer(chunk)) {
    return chunk;
  }
  if (chunk instanceof Uint8Array) {
    return Buffer.from(chunk.buffer, chunk.byteOffset, chunk.byteLength);
  }
  throw new CryptoError('INVALID_INPUT', 'SCNV1 streams accept binary chunks only.');
}

/** Plaintext in → SCNV1 ciphertext out. */
export class Scnv1EncryptStream extends Transform {
  private readonly key: Buffer;
  private readonly header: Buffer;
  private readonly aad: Buffer;
  private readonly noncePrefix: Buffer;
  private readonly chunkSize: number;
  private readonly queue = new ByteQueue();
  private counter = 0;
  private headerSent = false;

  public constructor(options: Scnv1EncryptOptions) {
    super();
    assertStreamKey(options.key);
    const chunkLog2 = options.chunkLog2 ?? SCNV1_DEFAULT_CHUNK_LOG2;
    assertChunkLog2(chunkLog2);
    const noncePrefix = options.noncePrefix ?? randomBytes(SCNV1_NONCE_PREFIX_BYTES);
    if (noncePrefix.length !== SCNV1_NONCE_PREFIX_BYTES) {
      throw new CryptoError('INVALID_INPUT', 'An SCNV1 nonce prefix must be 7 bytes.');
    }
    if (options.objectId.length === 0) {
      throw new CryptoError('INVALID_INPUT', 'An SCNV1 object id must not be empty.');
    }
    this.key = Buffer.from(options.key);
    this.noncePrefix = Buffer.from(noncePrefix);
    this.chunkSize = 2 ** chunkLog2;
    this.header = buildHeader(chunkLog2, this.noncePrefix);
    this.aad = Buffer.concat([this.header, Buffer.from(options.objectId, 'utf8')]);
  }

  public override _transform(
    chunk: unknown,
    _encoding: BufferEncoding,
    done: TransformCallback,
  ): void {
    try {
      this.sendHeader();
      this.queue.push(toBuffer(chunk));
      // Strictly greater: a full chunk is held back until more data proves it is not the last.
      while (this.queue.length > this.chunkSize) {
        this.push(this.encryptChunk(this.queue.take(this.chunkSize), false));
      }
      done();
    } catch (error) {
      done(error as Error);
    }
  }

  public override _flush(done: TransformCallback): void {
    try {
      this.sendHeader();
      this.push(this.encryptChunk(this.queue.take(this.queue.length), true));
      this.key.fill(0);
      done();
    } catch (error) {
      done(error as Error);
    }
  }

  public override _destroy(error: Error | null, done: (error?: Error | null) => void): void {
    this.key.fill(0);
    done(error);
  }

  private sendHeader(): void {
    if (!this.headerSent) {
      this.headerSent = true;
      this.push(this.header);
    }
  }

  private encryptChunk(plaintext: Buffer, final: boolean): Buffer {
    if (this.counter >= MAX_CHUNK_COUNT) {
      throw new CryptoError('INVALID_INPUT', 'Object is too large for SCNV1.');
    }
    const nonce = chunkNonce(this.noncePrefix, this.counter, final);
    this.counter += 1;
    return aesGcmEncrypt(this.key, nonce, plaintext, this.aad);
  }
}

/**
 * SCNV1 ciphertext in → plaintext out. A chunk's plaintext is pushed downstream
 * only after its tag has verified. On any failure the stream errors and nothing
 * further is released; callers must treat already-delivered chunks as part of an
 * incomplete file until the stream ends cleanly.
 */
export class Scnv1DecryptStream extends Transform {
  private readonly key: Buffer;
  private readonly objectId: Buffer;
  private readonly queue = new ByteQueue();
  private aad: Buffer | null = null;
  private noncePrefix: Buffer | null = null;
  private cipherChunkSize = 0;
  private counter = 0;

  public constructor(options: Scnv1DecryptOptions) {
    super();
    assertStreamKey(options.key);
    if (options.objectId.length === 0) {
      throw new CryptoError('INVALID_INPUT', 'An SCNV1 object id must not be empty.');
    }
    this.key = Buffer.from(options.key);
    this.objectId = Buffer.from(options.objectId, 'utf8');
  }

  public override _transform(
    chunk: unknown,
    _encoding: BufferEncoding,
    done: TransformCallback,
  ): void {
    try {
      this.queue.push(toBuffer(chunk));
      if (this.aad === null) {
        if (this.queue.length < SCNV1_HEADER_BYTES) {
          done();
          return;
        }
        this.readHeader(this.queue.take(SCNV1_HEADER_BYTES));
      }
      // Strictly greater: the last chunk is only known to be last when the input ends.
      while (this.queue.length > this.cipherChunkSize) {
        this.push(this.decryptChunk(this.queue.take(this.cipherChunkSize), false));
      }
      done();
    } catch (error) {
      done(error as Error);
    }
  }

  public override _flush(done: TransformCallback): void {
    try {
      if (this.aad === null) {
        throw new CryptoError('INVALID_FORMAT', 'SCNV1 object is truncated.');
      }
      this.push(this.decryptChunk(this.queue.take(this.queue.length), true));
      this.key.fill(0);
      done();
    } catch (error) {
      done(error as Error);
    }
  }

  public override _destroy(error: Error | null, done: (error?: Error | null) => void): void {
    this.key.fill(0);
    done(error);
  }

  private readHeader(header: Buffer): void {
    if (!header.subarray(0, 4).equals(SCNV1_MAGIC)) {
      throw new CryptoError('INVALID_FORMAT', 'Not an SCNV1 object.');
    }
    if (header[4] !== SCNV1_VERSION || header[5] !== SCNV1_ALG_AES_256_GCM) {
      throw new CryptoError('INVALID_FORMAT', 'Unsupported SCNV1 version or algorithm.');
    }
    const chunkLog2 = header[6]!;
    assertChunkLog2(chunkLog2);
    if (header[7] !== 0 || header[8] !== 0) {
      throw new CryptoError('INVALID_FORMAT', 'SCNV1 reserved bytes must be zero.');
    }
    this.noncePrefix = Buffer.from(header.subarray(9, SCNV1_HEADER_BYTES));
    this.cipherChunkSize = 2 ** chunkLog2 + AEAD_TAG_BYTES;
    this.aad = Buffer.concat([header, this.objectId]);
  }

  private decryptChunk(ciphertext: Buffer, final: boolean): Buffer {
    if (this.aad === null || this.noncePrefix === null) {
      throw new CryptoError('INVALID_FORMAT', 'SCNV1 header missing.');
    }
    if (ciphertext.length < AEAD_TAG_BYTES) {
      throw new CryptoError('INVALID_FORMAT', 'SCNV1 object is truncated.');
    }
    if (this.counter >= MAX_CHUNK_COUNT) {
      throw new CryptoError('INVALID_FORMAT', 'SCNV1 object has too many chunks.');
    }
    const nonce = chunkNonce(this.noncePrefix, this.counter, final);
    this.counter += 1;
    return aesGcmDecrypt(this.key, nonce, ciphertext, this.aad);
  }
}

export function createScnv1EncryptStream(options: Scnv1EncryptOptions): Scnv1EncryptStream {
  return new Scnv1EncryptStream(options);
}

export function createScnv1DecryptStream(options: Scnv1DecryptOptions): Scnv1DecryptStream {
  return new Scnv1DecryptStream(options);
}
