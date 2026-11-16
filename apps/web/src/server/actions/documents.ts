'use server';

import { UuidSchema } from '@scanova/contracts/common';
import type { Document } from '@scanova/contracts/documents';
import type { ActionResult } from '@/lib/action-result';
import { DocumentTagsInputSchema, RenameDocumentSchema, normaliseTags } from '@/lib/validation';
import { getApi } from '../api/instance';
import { requireSession } from '../session/server';
import { fromApiFailure, invalid, ok } from './support';

export async function renameDocument(input: unknown): Promise<ActionResult<Document>> {
  const parsed = RenameDocumentSchema.safeParse(input);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  const { context } = await requireSession('site');
  const result = await getApi().documents.update(context, parsed.data.id, {
    title: parsed.data.title,
  });
  return result.ok ? ok(result.data) : fromApiFailure(result, 'site', 'rename-document');
}

export async function setDocumentTags(input: unknown): Promise<ActionResult<Document>> {
  const candidate =
    typeof input === 'object' && input !== null && Array.isArray((input as { tags?: unknown }).tags)
      ? { ...input, tags: normaliseTags((input as { tags: unknown[] }).tags.map(String)) }
      : input;
  const parsed = DocumentTagsInputSchema.safeParse(candidate);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  const { context } = await requireSession('site');
  const result = await getApi().documents.update(context, parsed.data.id, {
    tags: parsed.data.tags,
  });
  return result.ok ? ok(result.data) : fromApiFailure(result, 'site', 'set-document-tags');
}

/** Moves the document to the trash on every device (purged after the server's retention time). */
export async function deleteDocument(id: unknown): Promise<ActionResult<null>> {
  const parsed = UuidSchema.safeParse(id);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  const { context } = await requireSession('site');
  const result = await getApi().documents.remove(context, parsed.data);
  return result.ok ? ok(null) : fromApiFailure(result, 'site', 'delete-document');
}
