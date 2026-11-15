import type { z } from 'zod';
import { AppError } from './app-error.js';

/** Cursors are opaque to clients: base64url of a small JSON object. */
export function encodeCursor(value: object): string {
  return Buffer.from(JSON.stringify(value), 'utf8').toString('base64url');
}

/** Decodes and validates a cursor. Anything a client made up is a validation error. */
export function decodeCursor<Schema extends z.ZodType>(
  cursor: string,
  schema: Schema,
): z.infer<Schema> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8'));
  } catch {
    throw invalidCursor();
  }
  const result = schema.safeParse(parsed);
  if (!result.success) {
    throw invalidCursor();
  }
  return result.data;
}

function invalidCursor(): AppError {
  return new AppError('VALIDATION_FAILED', {
    details: [{ field: 'cursor', message: 'Invalid cursor.' }],
  });
}
