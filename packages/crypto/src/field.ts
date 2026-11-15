import { open, seal } from './aead.js';
import { fieldAad, type FieldBinding } from './hierarchy.js';

/**
 * Encrypts one database field: `0x01 ‖ nonce(12) ‖ ciphertext ‖ tag(16)`.
 * The associated data names the table, column and row, so a value moved to a
 * different column or row fails to decrypt.
 */
export function encryptField(
  key: Uint8Array,
  binding: FieldBinding,
  plaintext: string | Uint8Array,
  nonce?: Uint8Array,
): Buffer<ArrayBuffer> {
  const bytes = typeof plaintext === 'string' ? Buffer.from(plaintext, 'utf8') : plaintext;
  return seal(key, bytes, fieldAad(binding), nonce);
}

export function decryptField(
  key: Uint8Array,
  binding: FieldBinding,
  sealed: Uint8Array,
): Buffer<ArrayBuffer> {
  return open(key, sealed, fieldAad(binding));
}

export function decryptFieldToString(
  key: Uint8Array,
  binding: FieldBinding,
  sealed: Uint8Array,
): string {
  return decryptField(key, binding, sealed).toString('utf8');
}
