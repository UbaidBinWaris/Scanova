import type { FieldBinding } from './hierarchy.js';

/**
 * Every encrypted database field and what it is bound to (§5.3). The table,
 * column and row id go into the associated data of the ciphertext, so a value
 * cannot be moved to another column or row and still decrypt. Row ids are public
 * UUIDs. This list is the single definition used by the API and the worker.
 *
 * Under a user's PROFILE key: e-mail, name and device names.
 * Under a key derived from a DOCUMENT key: title, tags and OCR text.
 * Under the system AUDIT key: audit payloads.
 */
export const FIELDS = {
  userEmail: (userId: string): FieldBinding => ({
    table: 'users',
    column: 'email_enc',
    rowId: userId,
  }),
  userName: (userId: string): FieldBinding => ({
    table: 'users',
    column: 'name_enc',
    rowId: userId,
  }),
  sessionDeviceName: (sessionId: string): FieldBinding => ({
    table: 'sessions',
    column: 'device_name_enc',
    rowId: sessionId,
  }),
  sessionDeviceModel: (sessionId: string): FieldBinding => ({
    table: 'sessions',
    column: 'device_model_enc',
    rowId: sessionId,
  }),
  documentTitle: (documentId: string): FieldBinding => ({
    table: 'documents',
    column: 'title_enc',
    rowId: documentId,
  }),
  documentTags: (documentId: string): FieldBinding => ({
    table: 'documents',
    column: 'tags_enc',
    rowId: documentId,
  }),
  documentText: (documentId: string): FieldBinding => ({
    table: 'document_texts',
    column: 'text_enc',
    rowId: documentId,
  }),
  auditPayload: (eventId: string): FieldBinding => ({
    table: 'audit_events',
    column: 'payload_enc',
    rowId: eventId,
  }),
} as const;
