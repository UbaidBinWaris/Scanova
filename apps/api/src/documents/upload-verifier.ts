import { createHash, type Hash } from 'node:crypto';
import { Transform, type TransformCallback } from 'node:stream';
import { AppError } from '../common/app-error.js';

/** What the first bytes of an accepted upload must be, and what to answer otherwise. */
export interface FileSignature {
  readonly magic: Buffer;
  /** The `UNSUPPORTED_MEDIA_TYPE` message for anything else. */
  readonly message: string;
}

export const PDF_SIGNATURE: FileSignature = {
  magic: Buffer.from('%PDF-', 'ascii'),
  message: 'Only PDF files are accepted.',
};

/** The SOI marker and the 0xFF of the next marker: every JPEG file starts like this. */
export const JPEG_SIGNATURE: FileSignature = {
  magic: Buffer.from([0xff, 0xd8, 0xff]),
  message: 'Only JPEG images are accepted.',
};

export interface UploadExpectation {
  /** The request's `Content-Length`. */
  readonly declaredLength: number;
  /** `MAX_UPLOAD_BYTES`. */
  readonly maxBytes: number;
  /** `X-Content-SHA256`, lower-case hex. */
  readonly sha256Hex: string;
  /** The file type the body must be. Defaults to PDF. */
  readonly signature?: FileSignature;
}

/**
 * Checks an upload while it streams through, without holding it in memory:
 * size limit, the file signature (`%PDF-`, or a JPEG's start-of-image marker),
 * and at the very end the SHA-256 the client announced. It sits in front of the
 * encryptor, and a failure here (including the checksum, which is only known at
 * the end) aborts the upload, so nothing unverified is ever stored.
 */
export class UploadVerifier extends Transform {
  private readonly hash: Hash = createHash('sha256');
  private readonly expectation: UploadExpectation;
  private readonly signature: FileSignature;
  private head = Buffer.alloc(0);
  private signatureChecked = false;
  private received = 0;

  public constructor(expectation: UploadExpectation) {
    super();
    this.expectation = expectation;
    this.signature = expectation.signature ?? PDF_SIGNATURE;
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
      const magic = this.signature.magic;
      this.head = Buffer.concat([this.head, chunk.subarray(0, magic.length - this.head.length)]);
      if (this.head.length === magic.length) {
        if (!this.head.equals(magic)) {
          done(this.unsupported());
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
      done(this.unsupported());
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

  private unsupported(): AppError {
    return new AppError('UNSUPPORTED_MEDIA_TYPE', { message: this.signature.message });
  }
}

function lengthMismatch(): AppError {
  return new AppError('VALIDATION_FAILED', {
    details: [
      { field: 'content-length', message: 'The body length does not match Content-Length.' },
    ],
  });
}
