import { createHash, randomBytes, randomInt } from 'node:crypto';

export const KEY_BYTES = 32;

/** 32 random bytes from the operating system's CSPRNG. */
export function randomKey(): Buffer<ArrayBuffer> {
  return randomBytes(KEY_BYTES);
}

/** An opaque bearer secret, base64url without padding. */
export function randomToken(bytes: number = 32): string {
  return randomBytes(bytes).toString('base64url');
}

export function sha256(data: string | Uint8Array): Buffer<ArrayBuffer> {
  return createHash('sha256').update(data).digest();
}

// No look-alike characters (0/O, 1/l/I): a temporary password is read out or typed once.
const TEMP_PASSWORD_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';

/** 20 characters from a 56-symbol alphabet: about 116 bits of entropy. */
export function generateTemporaryPassword(length: number = 20): string {
  let password = '';
  for (let index = 0; index < length; index += 1) {
    password += TEMP_PASSWORD_ALPHABET.charAt(randomInt(TEMP_PASSWORD_ALPHABET.length));
  }
  return password;
}
