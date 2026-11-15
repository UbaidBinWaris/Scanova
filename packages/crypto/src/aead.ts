import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { CryptoError } from './errors.js';

/** AES-256-GCM with 96-bit nonces and 128-bit tags, throughout (§5.3). */
export const AEAD_KEY_BYTES = 32;
export const AEAD_NONCE_BYTES = 12;
export const AEAD_TAG_BYTES = 16;
/** Every sealed blob starts with this byte so the layout can evolve. */
export const SEALED_VERSION = 0x01;
export const SEALED_OVERHEAD_BYTES = 1 + AEAD_NONCE_BYTES + AEAD_TAG_BYTES;

const ALGORITHM = 'aes-256-gcm';

function assertKey(key: Uint8Array): void {
  if (key.length !== AEAD_KEY_BYTES) {
    throw new CryptoError('INVALID_INPUT', 'AES-256-GCM needs a 32-byte key.');
  }
}

/** Raw AES-256-GCM encryption. Returns `ciphertext ‖ tag`. The caller owns nonce uniqueness. */
export function aesGcmEncrypt(
  key: Uint8Array,
  nonce: Uint8Array,
  plaintext: Uint8Array,
  aad: Uint8Array,
): Buffer<ArrayBuffer> {
  assertKey(key);
  if (nonce.length !== AEAD_NONCE_BYTES) {
    throw new CryptoError('INVALID_INPUT', 'AES-256-GCM needs a 12-byte nonce.');
  }
  const cipher = createCipheriv(ALGORITHM, key, nonce, { authTagLength: AEAD_TAG_BYTES });
  cipher.setAAD(aad);
  return Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]);
}

/**
 * Raw AES-256-GCM decryption of `ciphertext ‖ tag`. Plaintext is returned only
 * after the tag has verified; on failure nothing is released.
 */
export function aesGcmDecrypt(
  key: Uint8Array,
  nonce: Uint8Array,
  ciphertextAndTag: Uint8Array,
  aad: Uint8Array,
): Buffer<ArrayBuffer> {
  assertKey(key);
  if (nonce.length !== AEAD_NONCE_BYTES) {
    throw new CryptoError('INVALID_INPUT', 'AES-256-GCM needs a 12-byte nonce.');
  }
  if (ciphertextAndTag.length < AEAD_TAG_BYTES) {
    throw new CryptoError('INVALID_FORMAT', 'Ciphertext is shorter than an authentication tag.');
  }
  const split = ciphertextAndTag.length - AEAD_TAG_BYTES;
  const decipher = createDecipheriv(ALGORITHM, key, nonce, { authTagLength: AEAD_TAG_BYTES });
  decipher.setAAD(aad);
  decipher.setAuthTag(ciphertextAndTag.subarray(split));
  const plaintext = decipher.update(ciphertextAndTag.subarray(0, split));
  try {
    decipher.final();
  } catch {
    plaintext.fill(0);
    throw new CryptoError('AUTHENTICATION_FAILED', 'Authentication failed.');
  }
  return plaintext;
}

/**
 * Seals a small value: `0x01 ‖ nonce(12) ‖ ciphertext ‖ tag(16)`.
 *
 * A fresh random nonce is used for every call. That is safe here because sealed
 * values are only ever produced under per-user or per-document keys, which keeps
 * the number of messages per key far below the GCM limit for random nonces.
 * `nonce` exists for fixed test vectors only.
 */
export function seal(
  key: Uint8Array,
  plaintext: Uint8Array,
  aad: Uint8Array,
  nonce: Uint8Array = randomBytes(AEAD_NONCE_BYTES),
): Buffer<ArrayBuffer> {
  const body = aesGcmEncrypt(key, nonce, plaintext, aad);
  return Buffer.concat([Buffer.from([SEALED_VERSION]), nonce, body]);
}

/** Opens a blob produced by {@link seal}. The associated data must match exactly. */
export function open(key: Uint8Array, sealed: Uint8Array, aad: Uint8Array): Buffer<ArrayBuffer> {
  if (sealed.length < SEALED_OVERHEAD_BYTES) {
    throw new CryptoError('INVALID_FORMAT', 'Sealed value is too short.');
  }
  if (sealed[0] !== SEALED_VERSION) {
    throw new CryptoError('INVALID_FORMAT', 'Unsupported sealed-value version.');
  }
  const nonce = sealed.subarray(1, 1 + AEAD_NONCE_BYTES);
  return aesGcmDecrypt(key, nonce, sealed.subarray(1 + AEAD_NONCE_BYTES), aad);
}
