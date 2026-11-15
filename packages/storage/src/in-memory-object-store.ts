import { Readable } from 'node:stream';
import { StorageError } from './errors.js';
import type { ObjectStore } from './object-store.js';

/** An `ObjectStore` in process memory, for tests. Same contract as the S3 one. */
export class InMemoryObjectStore implements ObjectStore {
  private readonly objects = new Map<string, Buffer>();
  private available = true;

  /** Simulates an outage. */
  public setAvailable(available: boolean): void {
    this.available = available;
  }

  /** Raw stored bytes, for assertions about what is at rest. */
  public raw(key: string): Buffer | undefined {
    return this.objects.get(key);
  }

  /** Replaces stored bytes, for tamper tests. */
  public overwrite(key: string, bytes: Buffer): void {
    this.objects.set(key, bytes);
  }

  public keys(): string[] {
    return [...this.objects.keys()].sort();
  }

  public async put(key: string, body: Readable): Promise<void> {
    this.assertAvailable();
    const chunks: Buffer[] = [];
    // A failing stream rejects here, before anything is stored.
    for await (const chunk of body) {
      chunks.push(chunk as Buffer);
    }
    this.assertAvailable();
    this.objects.set(key, Buffer.concat(chunks));
  }

  public get(key: string): Promise<Readable> {
    try {
      this.assertAvailable();
    } catch (error) {
      return Promise.reject(toError(error));
    }
    const object = this.objects.get(key);
    if (object === undefined) {
      return Promise.reject(new StorageError('NOT_FOUND', 'Object not found.'));
    }
    // Small chunks, so consumers are exercised across chunk boundaries.
    const parts: Buffer[] = [];
    for (let offset = 0; offset < object.length; offset += 4096) {
      parts.push(object.subarray(offset, offset + 4096));
    }
    return Promise.resolve(Readable.from(parts.length === 0 ? [Buffer.alloc(0)] : parts));
  }

  public exists(key: string): Promise<boolean> {
    return this.guarded(() => this.objects.has(key));
  }

  public delete(key: string): Promise<void> {
    return this.guarded(() => {
      this.objects.delete(key);
    });
  }

  public deleteMany(keys: readonly string[]): Promise<void> {
    return this.guarded(() => {
      for (const key of keys) {
        this.objects.delete(key);
      }
    });
  }

  public async *list(prefix: string): AsyncIterable<string> {
    this.assertAvailable();
    for (const key of this.keys()) {
      if (key.startsWith(prefix)) {
        yield await Promise.resolve(key);
      }
    }
  }

  public health(): Promise<void> {
    return this.guarded(() => undefined);
  }

  public close(): void {
    this.objects.clear();
  }

  private guarded<Result>(operation: () => Result): Promise<Result> {
    try {
      this.assertAvailable();
      return Promise.resolve(operation());
    } catch (error) {
      return Promise.reject(toError(error));
    }
  }

  private assertAvailable(): void {
    if (!this.available) {
      throw new StorageError('UNAVAILABLE', 'Object storage is unavailable.');
    }
  }
}

function toError(error: unknown): Error {
  return error instanceof Error ? error : new Error('Object storage failed.');
}
