import { createHash } from 'node:crypto';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { describe, expect, it } from 'vitest';
import { AppError } from '../common/app-error.js';
import { UploadVerifier } from './upload-verifier.js';

const PDF = Buffer.concat([Buffer.from('%PDF-1.7\n'), Buffer.alloc(5000, 0x41)]);
const sha = (data: Buffer): string => createHash('sha256').update(data).digest('hex');

async function run(
  body: Buffer,
  expectation: { declaredLength?: number; maxBytes?: number; sha256Hex?: string },
  chunkSize = 1000,
): Promise<{ out: Buffer; error: unknown }> {
  const verifier = new UploadVerifier({
    declaredLength: expectation.declaredLength ?? body.length,
    maxBytes: expectation.maxBytes ?? 1_000_000,
    sha256Hex: expectation.sha256Hex ?? sha(body),
  });
  const chunks: Buffer[] = [];
  for (let offset = 0; offset < body.length; offset += chunkSize) {
    chunks.push(body.subarray(offset, offset + chunkSize));
  }
  const out: Buffer[] = [];
  verifier.on('data', (chunk: Buffer) => out.push(chunk));
  let error: unknown = null;
  try {
    await pipeline(Readable.from(chunks), verifier);
  } catch (caught) {
    error = caught;
  }
  return { out: Buffer.concat(out), error };
}

function code(error: unknown): string | null {
  return error instanceof AppError ? error.code : null;
}

describe('UploadVerifier', () => {
  it('passes a PDF through unchanged when length and checksum match', async () => {
    for (const chunkSize of [1, 3, 1000, 100_000]) {
      const result = await run(PDF, {}, chunkSize);
      expect(result.error).toBeNull();
      expect(result.out.equals(PDF)).toBe(true);
    }
  });

  it('fails at the end when the checksum does not match', async () => {
    const result = await run(PDF, { sha256Hex: sha(Buffer.from('something else')) });
    expect(code(result.error)).toBe('CHECKSUM_MISMATCH');
  });

  it('rejects anything that does not start with %PDF-', async () => {
    expect(code((await run(Buffer.from('<html>not a pdf</html>'), {})).error)).toBe(
      'UNSUPPORTED_MEDIA_TYPE',
    );
    expect(code((await run(Buffer.from('%PDF'), {})).error)).toBe('UNSUPPORTED_MEDIA_TYPE');
    expect(code((await run(Buffer.alloc(0), {})).error)).toBe('UNSUPPORTED_MEDIA_TYPE');
    // The signature split across one-byte chunks is still recognised.
    expect((await run(PDF, {}, 1)).error).toBeNull();
    expect(code((await run(Buffer.from('%PDX-1.7 fake'), {}, 1)).error)).toBe(
      'UNSUPPORTED_MEDIA_TYPE',
    );
  });

  it('stops as soon as the body exceeds the upload limit', async () => {
    const result = await run(PDF, { maxBytes: 2000 });
    expect(code(result.error)).toBe('FILE_TOO_LARGE');
    expect(result.out.length).toBeLessThanOrEqual(2000);
  });

  it('rejects a body longer or shorter than Content-Length', async () => {
    expect(code((await run(PDF, { declaredLength: PDF.length - 1 })).error)).toBe(
      'VALIDATION_FAILED',
    );
    expect(code((await run(PDF, { declaredLength: PDF.length + 1 })).error)).toBe(
      'VALIDATION_FAILED',
    );
  });
});
