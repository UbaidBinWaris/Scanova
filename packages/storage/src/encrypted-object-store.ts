import { pipeline, type Readable } from 'node:stream';
import { pipeline as pipelineAsync } from 'node:stream/promises';
import { createScnv1DecryptStream, createScnv1EncryptStream } from '@scanova/crypto/stream';
import type { ObjectStore } from './object-store.js';

export interface EncryptedObjectRef {
  /** Key of the object in the bucket. */
  readonly objectKey: string;
  /** Public id of the object; bound into every chunk as associated data. */
  readonly objectId: string;
  /**
   * 32-byte SCNV1 stream key, `HKDF(DOCUMENT key, "scanova/v1/object|<objectId>")`.
   * The stream copies it, so the caller should wipe its buffer right after the call returns.
   */
  readonly streamKey: Uint8Array;
}

/**
 * The only way file content reaches the bucket: plaintext stream in → SCNV1
 * ciphertext → multipart upload, and the reverse. Memory use is constant (one
 * 64 KiB chunk plus one upload part), whatever the file size.
 */
export class EncryptedObjectStore {
  public constructor(private readonly store: ObjectStore) {}

  /**
   * Encrypts and stores a plaintext stream. If the plaintext stream fails (for
   * example a checksum mismatch detected at its end), the upload is aborted,
   * nothing is stored, and that original error is rethrown. If storage fails,
   * the plaintext stream is destroyed so the sender stops.
   */
  public async put(ref: EncryptedObjectRef, plaintext: Readable): Promise<void> {
    const encrypt = createScnv1EncryptStream({ key: ref.streamKey, objectId: ref.objectId });
    const encrypting = pipelineAsync(plaintext, encrypt);
    const uploading = this.store.put(ref.objectKey, encrypt).catch((error: unknown) => {
      // Stop pulling plaintext once storage has given up, or the pipeline would stall.
      encrypt.destroy(error instanceof Error ? error : new Error('upload failed'));
      throw error;
    });
    const [encrypted, uploaded] = await Promise.allSettled([encrypting, uploading]);
    if (uploaded.status === 'fulfilled' && encrypted.status === 'fulfilled') {
      return;
    }
    // Make sure a partial object never survives a failed put.
    await this.store.delete(ref.objectKey).catch(() => undefined);
    if (encrypted.status === 'rejected' && !isDestroyedByUpload(encrypted.reason, uploaded)) {
      // The source failed first: that is the meaningful error (e.g. checksum mismatch).
      throw encrypted.reason;
    }
    if (uploaded.status === 'rejected') {
      throw uploaded.reason;
    }
    throw (encrypted as PromiseRejectedResult).reason;
  }

  /**
   * Streams the plaintext of a stored object. Each chunk is released only after
   * its tag verified. A wrong key, a wrong object id or tampered bytes surface as
   * a stream error; a consumer must not treat a partial stream as a file.
   */
  public async get(ref: EncryptedObjectRef): Promise<Readable> {
    const ciphertext = await this.store.get(ref.objectKey);
    const decrypt = createScnv1DecryptStream({ key: ref.streamKey, objectId: ref.objectId });
    // Errors are reported on `decrypt`; the callback only keeps them from being unhandled.
    pipeline(ciphertext, decrypt, () => undefined);
    return decrypt;
  }

  public delete(objectKey: string): Promise<void> {
    return this.store.delete(objectKey);
  }
}

/** True when the encryption pipeline failed only because the failed upload tore it down. */
function isDestroyedByUpload(reason: unknown, uploaded: PromiseSettledResult<void>): boolean {
  return uploaded.status === 'rejected' && reason === uploaded.reason;
}
