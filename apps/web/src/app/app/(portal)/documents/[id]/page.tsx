import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import type { ReactNode } from 'react';
import { DocumentDetails } from '@/components/documents/document-details';
import { Alert } from '@/components/ui/feedback';
import { ChevronLeftIcon } from '@/components/ui/icons';
import { DocumentIdSchema } from '@/lib/validation';
import { getApi } from '@/server/api/instance';
import { handleSessionFailure, unwrap } from '@/server/guard';
import { requireSession } from '@/server/session/server';

// Generic on purpose: a title in the tab would end up in browser history and its cloud sync.
export const metadata: Metadata = { title: 'Document' };

export default async function DocumentPage({
  params,
}: {
  params: Promise<{ id: string }>;
}): Promise<ReactNode> {
  const { context } = await requireSession('site');
  const id = DocumentIdSchema.safeParse((await params).id);
  if (!id.success) {
    notFound();
  }
  const api = getApi();
  const [documentResult, pagesResult] = await Promise.all([
    api.documents.get(context, id.data),
    api.documents.pages(context, id.data),
  ]);
  const document = unwrap(documentResult, 'site');
  if (document.deletedAt !== null) {
    notFound();
  }
  if (!pagesResult.ok) {
    handleSessionFailure(pagesResult, 'site');
  }

  return (
    <div className="flex flex-col gap-6">
      <Link
        href="/app"
        className="inline-flex min-h-11 w-fit items-center gap-1 rounded text-sm font-medium text-muted-foreground hover:text-foreground sm:min-h-9"
      >
        <ChevronLeftIcon width={18} height={18} />
        All documents
      </Link>
      {document.status === 'READY' ? (
        <DocumentDetails
          document={document}
          pages={pagesResult.ok ? pagesResult.data.pages : null}
        />
      ) : (
        <Alert tone="info" title="Still uploading">
          This document is still being uploaded from a phone. It appears here once the upload is
          complete.
        </Alert>
      )}
    </div>
  );
}
