import { AEAD_KEY_BYTES } from './aead.js';
import { CryptoError } from './errors.js';
import { hkdfSha256 } from './hkdf.js';

/**
 * The key hierarchy below a user key (§5.2), as pure derivations and the
 * associated-data strings that bind each ciphertext to its place (§5.3).
 *
 *   user key ──HKDF "scanova/v1/key-wrap"──► wraps data keys
 *   DOCUMENT key ──HKDF "scanova/v1/field"──► title, tags, OCR text
 *   DOCUMENT key ──HKDF "scanova/v1/object|<objectId>"──► SCNV1 stream key of one file
 *   PROFILE key ─► e-mail, name, device names (used directly)
 *   INDEX key   ─► HMAC key of the blind index (used directly)
 */

export const KEY_WRAP_LABEL = 'scanova/v1/key-wrap';
export const FIELD_LABEL = 'scanova/v1/field';
export const OBJECT_LABEL_PREFIX = 'scanova/v1/object|';

export const DATA_KEY_SCOPES = ['PROFILE', 'INDEX', 'DOCUMENT'] as const;
export type DataKeyScope = (typeof DATA_KEY_SCOPES)[number];

const SEPARATOR = '|';

function assertKey(key: Uint8Array, name: string): void {
  if (key.length !== AEAD_KEY_BYTES) {
    throw new CryptoError('INVALID_INPUT', `${name} must be 32 bytes.`);
  }
}

/** A component of a label or AAD string must not be able to forge a field boundary. */
function component(value: string, name: string): string {
  if (value.length === 0 || value.includes(SEPARATOR)) {
    throw new CryptoError('INVALID_INPUT', `${name} must be non-empty and must not contain "|".`);
  }
  return value;
}

/** Key that wraps a user's data keys. */
export function deriveKeyWrapKey(userKey: Uint8Array): Buffer<ArrayBuffer> {
  assertKey(userKey, 'User key');
  return hkdfSha256(userKey, KEY_WRAP_LABEL);
}

/** Key that encrypts a document's title, tags and OCR text. */
export function deriveFieldKey(documentKey: Uint8Array): Buffer<ArrayBuffer> {
  assertKey(documentKey, 'Document key');
  return hkdfSha256(documentKey, FIELD_LABEL);
}

/** Stream key of one stored file. Each object gets its own key, used exactly once. */
export function deriveObjectKey(documentKey: Uint8Array, objectId: string): Buffer<ArrayBuffer> {
  assertKey(documentKey, 'Document key');
  return hkdfSha256(documentKey, `${OBJECT_LABEL_PREFIX}${component(objectId, 'Object id')}`);
}

export interface DataKeyBinding {
  readonly scope: DataKeyScope;
  /** Public id of the owning user. */
  readonly userId: string;
  /** User id for PROFILE and INDEX, document id for DOCUMENT. */
  readonly refId: string;
  /** Version of the user key the data key is wrapped under. */
  readonly userKeyVersion: number;
}

/** `scanova:key:v1|<scope>|<userId>|<refId>|<ukVersion>` */
export function dataKeyAad(binding: DataKeyBinding): Buffer<ArrayBuffer> {
  if (!Number.isInteger(binding.userKeyVersion) || binding.userKeyVersion < 1) {
    throw new CryptoError('INVALID_INPUT', 'User key version must be a positive integer.');
  }
  return Buffer.from(
    [
      'scanova:key:v1',
      component(binding.scope, 'Scope'),
      component(binding.userId, 'User id'),
      component(binding.refId, 'Reference id'),
      String(binding.userKeyVersion),
    ].join(SEPARATOR),
    'utf8',
  );
}

export interface FieldBinding {
  readonly table: string;
  readonly column: string;
  /** Public id of the row. */
  readonly rowId: string;
}

/** `scanova:field:v1|<table>.<column>|<rowId>` */
export function fieldAad(binding: FieldBinding): Buffer<ArrayBuffer> {
  const location = `${component(binding.table, 'Table')}.${component(binding.column, 'Column')}`;
  return Buffer.from(
    ['scanova:field:v1', location, component(binding.rowId, 'Row id')].join(SEPARATOR),
    'utf8',
  );
}
