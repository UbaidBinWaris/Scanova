import type { Readable } from 'node:stream';

/**
 * A bucket of opaque objects. Implementations only ever see ciphertext: the
 * encryption happens in `EncryptedObjectStore`, above this interface.
 */
export interface ObjectStore {
  /**
   * Stores a stream of unknown length without buffering it whole. If the stream
   * fails, nothing is left under the key.
   */
  put(key: string, body: Readable): Promise<void>;
  /** Streams an object. Throws `StorageError('NOT_FOUND')` when it does not exist. */
  get(key: string): Promise<Readable>;
  exists(key: string): Promise<boolean>;
  /** Idempotent: deleting a missing object succeeds. */
  delete(key: string): Promise<void>;
  deleteMany(keys: readonly string[]): Promise<void>;
  /** Keys under a prefix, in pages. */
  list(prefix: string): AsyncIterable<string>;
  /** Resolves when the bucket is reachable with the configured credentials. */
  health(): Promise<void>;
  close(): void;
}
