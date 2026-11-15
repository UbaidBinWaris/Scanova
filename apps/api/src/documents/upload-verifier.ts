import { createHash, type Hash } from 'node:crypto';
import { Transform, type TransformCallback } from 'node:stream';
import { AppError } from '../common/app-error.js';

const PDF_MAGIC = Buffer.from('%PDF-', 'ascii');

export interface UploadExpectation {
  /** The request's `Content-Length`. */
  readonly declaredLength: number;
  /** `MAX_UPLOAD_BYTES`. */
  readonly maxBytes: number;
  /** `X-Content-SHA256`, lower-case hex. */
  readonly sha256Hex: string;
}

/**
 * Checks an upload while it streams through, without holding it in memory:
 * size limit, the `%PDF-` signature, and at the very end the SHA-256 the client
 * announced. It sits in front of the encryptor, and a failure here (including
 * the checksum, which is only known at the end) aborts the upload, so nothing
 * unverified is ever stored.
 */
export class UploadVerifier extends Transform {
  private readonly hash: Hash = createHash('sha256');
  private readonly expectation: UploadExpectation;
  private head = Buffer.alloc(0);
  private signatureChecked = false;
  private received = 0;

  public constructor(expectation: UploadExpectation) {
    super();
    this.expectation = expectation;
  }

  /** Bytes that passed through. */
  public get size(): number {
    return this.received;
  }

  public override _transform(
    chunk: Buffer,
    _encoding: BufferEncoding,
    done: TransformCallback,
  ): void {
    this.received += chunk.length;
    if (this.received > this.expectation.maxBytes) {
      done(new AppError('FILE_TOO_LARGE'));
      return;
    }
    if (this.received > this.expectation.declaredLength) {
      done(lengthMismatch());
      return;
    }
    if (!this.signatureChecked) {
      this.head = Buffer.concat([
        this.head,
        chunk.subarray(0, PDF_MAGIC.length - this.head.length),
      ]);
      if (this.head.length === PDF_MAGIC.length) {
        if (!this.head.equals(PDF_MAGIC)) {
          done(new AppError('UNSUPPORTED_MEDIA_TYPE'));
          return;
        }
        this.signatureChecked = true;
      }
    }
    this.hash.update(chunk);
    done(null, chunk);
  }

  public override _flush(done: TransformCallback): void {
    if (!this.signatureChecked) {
      done(new AppError('UNSUPPORTED_MEDIA_TYPE'));
      return;
    }
    if (this.received !== this.expectation.declaredLength) {
      done(lengthMismatch());
      return;
    }
    if (this.hash.digest('hex') !== this.expectation.sha256Hex) {
      done(new AppError('CHECKSUM_MISMATCH'));
      return;
    }
    done();
  }
}

function lengthMismatch(): AppError {
  return new AppError('VALIDATION_FAILED', {
    details: [
      { field: 'content-length', message: 'The body length does not match Content-Length.' },
    ],
  });
}
