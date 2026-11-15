import { createHmac } from 'node:crypto';
import { CryptoError } from './errors.js';

export const BLIND_INDEX_TOKEN_BYTES = 16;
export const EMAIL_INDEX_BYTES = 32;
/** Longer "words" are almost always OCR noise and are not indexed. */
export const MAX_WORD_LENGTH = 64;

// Letters, combining marks and digits in any script (Latin, Arabic/Urdu, …).
const WORD_PATTERN = /[\p{L}\p{M}\p{N}]+/gu;

/** NFKC then lower case: the normal form used on both the indexing and the query side. */
export function normalizeWord(word: string): string {
  return word.normalize('NFKC').toLowerCase();
}

/** Splits text into unique normalised words, in first-seen order. */
export function tokenizeWords(text: string): string[] {
  const seen = new Set<string>();
  for (const match of normalizeWord(text).matchAll(WORD_PATTERN)) {
    const word = match[0];
    if (word.length <= MAX_WORD_LENGTH) {
      seen.add(word);
    }
  }
  return [...seen];
}

function assertIndexKey(key: Uint8Array): void {
  if (key.length !== 32) {
    throw new CryptoError('INVALID_INPUT', 'An index key must be 32 bytes.');
  }
}

/** First 16 bytes of `HMAC-SHA256(indexKey, NFKC-lowercased word)`. */
export function blindIndexToken(indexKey: Uint8Array, word: string): Buffer<ArrayBuffer> {
  assertIndexKey(indexKey);
  return createHmac('sha256', indexKey)
    .update(normalizeWord(word), 'utf8')
    .digest()
    .subarray(0, BLIND_INDEX_TOKEN_BYTES);
}

/**
 * Tokens for every distinct word of a text. The server can match a query word
 * against stored tokens without the index ever containing a readable word.
 */
export function blindIndexTokens(indexKey: Uint8Array, text: string): Buffer<ArrayBuffer>[] {
  return tokenizeWords(text).map((word) => blindIndexToken(indexKey, word));
}

/** Trimmed and lower-cased: the form an e-mail address is looked up by. */
export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

/** `HMAC-SHA256(systemIndexKey, trimmed lowercased e-mail)`, 32 bytes. */
export function emailLookupIndex(systemIndexKey: Uint8Array, email: string): Buffer<ArrayBuffer> {
  assertIndexKey(systemIndexKey);
  return createHmac('sha256', systemIndexKey).update(normalizeEmail(email), 'utf8').digest();
}
