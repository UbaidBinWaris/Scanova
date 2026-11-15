import { AEAD_KEY_BYTES, SEALED_OVERHEAD_BYTES, open, seal } from './aead.js';
import { CryptoError } from './errors.js';
import { dataKeyAad, type DataKeyBinding } from './hierarchy.js';

/** `0x01 ‖ nonce(12) ‖ ciphertext(32) ‖ tag(16)` */
export const WRAPPED_DATA_KEY_BYTES = SEALED_OVERHEAD_BYTES + AEAD_KEY_BYTES;

/**
 * Wraps a 32-byte data key under a user's key-wrap key. The associated data
 * binds the result to its scope, owner, reference and user-key version, so a
 * wrapped key copied to another row cannot be unwrapped there.
 */
export function wrapDataKey(
  keyWrapKey: Uint8Array,
  dataKey: Uint8Array,
  binding: DataKeyBinding,
  nonce?: Uint8Array,
): Buffer<ArrayBuffer> {
  if (dataKey.length !== AEAD_KEY_BYTES) {
    throw new CryptoError('INVALID_INPUT', 'A data key must be 32 bytes.');
  }
  return seal(keyWrapKey, dataKey, dataKeyAad(binding), nonce);
}

export function unwrapDataKey(
  keyWrapKey: Uint8Array,
  wrapped: Uint8Array,
  binding: DataKeyBinding,
): Buffer<ArrayBuffer> {
  if (wrapped.length !== WRAPPED_DATA_KEY_BYTES) {
    throw new CryptoError('INVALID_FORMAT', 'A wrapped data key must be 61 bytes.');
  }
  return open(keyWrapKey, wrapped, dataKeyAad(binding));
}
