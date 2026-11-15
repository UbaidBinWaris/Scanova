export type StorageErrorCode =
  /** No object under that key. */
  | 'NOT_FOUND'
  /** The object store is unreachable or answered with a server error. */
  | 'UNAVAILABLE';

/** The only error an `ObjectStore` throws. Messages carry object keys at most, never content. */
export class StorageError extends Error {
  public readonly code: StorageErrorCode;

  public constructor(code: StorageErrorCode, message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'StorageError';
    this.code = code;
  }
}
