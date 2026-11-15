import { createHash, timingSafeEqual } from 'node:crypto';

/**
 * Compares two secrets without leaking where they differ or how long they are.
 * Both sides are hashed first, so the comparison always runs over 32 bytes.
 */
export function constantTimeEqual(a: string | Uint8Array, b: string | Uint8Array): boolean {
  const left = createHash('sha256').update(a).digest();
  const right = createHash('sha256').update(b).digest();
  return timingSafeEqual(left, right);
}
