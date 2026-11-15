import { hkdfSync } from 'node:crypto';
import { CryptoError } from './errors.js';

const EMPTY_SALT = new Uint8Array(0);

/**
 * HKDF-SHA256 (RFC 5869). `info` is the purpose label; the same input key with
 * different labels yields independent keys. The input keys in this system are
 * uniformly random, so the default empty salt is appropriate.
 */
export function hkdfSha256(
  inputKey: Uint8Array,
  info: string | Uint8Array,
  length: number = 32,
  salt: Uint8Array = EMPTY_SALT,
): Buffer<ArrayBuffer> {
  if (inputKey.length === 0) {
    throw new CryptoError('INVALID_INPUT', 'HKDF needs a non-empty input key.');
  }
  const infoBytes = typeof info === 'string' ? Buffer.from(info, 'utf8') : info;
  return Buffer.from(hkdfSync('sha256', inputKey, salt, infoBytes, length));
}
