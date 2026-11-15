import { FIELDS } from './bindings.js';
import { blindIndexToken, tokenizeWords } from './blind-index.js';
import { CryptoError } from './errors.js';
import { decryptFieldToString, encryptField } from './field.js';
import { deriveFieldKey } from './hierarchy.js';

/** Upper bound of search tokens per document: keeps one huge OCR text from flooding the index. */
export const MAX_SEARCH_TOKENS_PER_DOCUMENT = 20_000;

export interface DocumentMetadata {
  readonly title: string;
  readonly tags: readonly string[];
}

export interface SealedDocumentMetadata {
  readonly titleEnc: Buffer<ArrayBuffer>;
  readonly tagsEnc: Buffer<ArrayBuffer>;
}

/**
 * Encrypts a document's title and tags under `HKDF(DOCUMENT key, "scanova/v1/field")`.
 * The derived key exists only for the duration of the call.
 */
export function sealDocumentMetadata(
  documentKey: Uint8Array,
  documentId: string,
  metadata: DocumentMetadata,
): SealedDocumentMetadata {
  const fieldKey = deriveFieldKey(documentKey);
  try {
    return {
      titleEnc: encryptField(fieldKey, FIELDS.documentTitle(documentId), metadata.title),
      tagsEnc: encryptField(
        fieldKey,
        FIELDS.documentTags(documentId),
        JSON.stringify(metadata.tags),
      ),
    };
  } finally {
    fieldKey.fill(0);
  }
}

export function openDocumentMetadata(
  documentKey: Uint8Array,
  documentId: string,
  sealed: { readonly titleEnc: Uint8Array; readonly tagsEnc: Uint8Array },
): { title: string; tags: string[] } {
  const fieldKey = deriveFieldKey(documentKey);
  try {
    const tags: unknown = JSON.parse(
      decryptFieldToString(fieldKey, FIELDS.documentTags(documentId), sealed.tagsEnc),
    );
    if (!Array.isArray(tags) || !tags.every((tag): tag is string => typeof tag === 'string')) {
      throw new CryptoError('INVALID_FORMAT', 'Stored tags are not a list of strings.');
    }
    return {
      title: decryptFieldToString(fieldKey, FIELDS.documentTitle(documentId), sealed.titleEnc),
      tags,
    };
  } finally {
    fieldKey.fill(0);
  }
}

/** Encrypts a document's OCR text under the same derived field key as its title. */
export function sealDocumentText(
  documentKey: Uint8Array,
  documentId: string,
  text: string,
): Buffer<ArrayBuffer> {
  const fieldKey = deriveFieldKey(documentKey);
  try {
    return encryptField(fieldKey, FIELDS.documentText(documentId), text);
  } finally {
    fieldKey.fill(0);
  }
}

export function openDocumentText(
  documentKey: Uint8Array,
  documentId: string,
  textEnc: Uint8Array,
): string {
  const fieldKey = deriveFieldKey(documentKey);
  try {
    return decryptFieldToString(fieldKey, FIELDS.documentText(documentId), textEnc);
  } finally {
    fieldKey.fill(0);
  }
}

/**
 * The blind-index tokens of a document: one per distinct word of its title,
 * tags and text, keyed with the owner's INDEX key. Title and tag words come
 * first, so they are always indexed even when a very long text hits the cap.
 */
export function documentSearchTokens(
  indexKey: Uint8Array,
  content: {
    readonly title: string;
    readonly tags: readonly string[];
    readonly text: string | null;
  },
): Buffer<ArrayBuffer>[] {
  const words = tokenizeWords([content.title, ...content.tags, content.text ?? ''].join('\n'));
  return words
    .slice(0, MAX_SEARCH_TOKENS_PER_DOCUMENT)
    .map((word) => blindIndexToken(indexKey, word));
}

/** Tokens for the words of a search query. Every one of them must match (§7.3). */
export function querySearchTokens(indexKey: Uint8Array, query: string): Buffer<ArrayBuffer>[] {
  return tokenizeWords(query).map((word) => blindIndexToken(indexKey, word));
}
