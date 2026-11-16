import type { Document } from '@scanova/contracts/documents';

export const DOCUMENT_SORTS = {
  recent: 'Recently changed',
  oldest: 'Oldest changed',
  'title-asc': 'Title A–Z',
  'title-desc': 'Title Z–A',
  captured: 'Newest scan',
  largest: 'Largest first',
} as const;

export type DocumentSort = keyof typeof DOCUMENT_SORTS;

export function isDocumentSort(value: unknown): value is DocumentSort {
  return typeof value === 'string' && Object.hasOwn(DOCUMENT_SORTS, value);
}

/**
 * `recent` is the API's own order, so it can page through the server. Every
 * other order needs the whole list: titles are encrypted at rest, so only the
 * browser (or the API, per request) can compare them.
 */
export function sortNeedsAllDocuments(sort: DocumentSort): boolean {
  return sort !== 'recent';
}

const collator = new Intl.Collator(undefined, { sensitivity: 'base', numeric: true });

export function sortDocuments(documents: readonly Document[], sort: DocumentSort): Document[] {
  const sorted = [...documents];
  const byUpdated = (a: Document, b: Document): number => b.updatedAt.localeCompare(a.updatedAt);
  switch (sort) {
    case 'recent':
      return sorted.sort(byUpdated);
    case 'oldest':
      return sorted.sort((a, b) => a.updatedAt.localeCompare(b.updatedAt));
    case 'title-asc':
      return sorted.sort((a, b) => collator.compare(a.title, b.title) || byUpdated(a, b));
    case 'title-desc':
      return sorted.sort((a, b) => collator.compare(b.title, a.title) || byUpdated(a, b));
    case 'captured':
      return sorted.sort((a, b) => b.capturedAt.localeCompare(a.capturedAt) || byUpdated(a, b));
    case 'largest':
      return sorted.sort((a, b) => (b.sizeBytes ?? 0) - (a.sizeBytes ?? 0) || byUpdated(a, b));
  }
}
