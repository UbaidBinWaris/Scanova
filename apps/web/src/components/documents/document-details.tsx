'use client';

import { DocumentTextSchema, type Document, type DocumentPage } from '@scanova/contracts/documents';
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { CopyButton } from '@/components/ui/copy-button';
import { Alert, Skeleton } from '@/components/ui/feedback';
import { LocalTime } from '@/components/ui/local-time';
import { fetchEnvelope } from '@/lib/client-api';
import { cn } from '@/lib/cn';
import { formatBytes, pluralize } from '@/lib/format';
import { DocumentActions } from './document-actions';
import { DocumentViewer } from './document-viewer';
import { OcrBadge } from './ocr-badge';

type Tab = 'pages' | 'text';

export function DocumentDetails({
  document,
  pages,
}: {
  document: Document;
  pages: readonly DocumentPage[] | null;
}): ReactNode {
  const [tab, setTab] = useState<Tab>('pages');

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="flex min-w-0 flex-col gap-2">
          <h1 className="break-words text-2xl font-semibold tracking-tight" dir="auto">
            {document.title}
          </h1>
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground">
            <span>{pluralize(document.pageCount, 'page')}</span>
            <span aria-hidden="true">·</span>
            <span>{formatBytes(document.sizeBytes)}</span>
            <span aria-hidden="true">·</span>
            <span>
              Scanned <LocalTime iso={document.capturedAt} />
            </span>
            <OcrBadge status={document.ocrStatus} />
          </p>
          {document.tags.length > 0 ? (
            <ul className="flex flex-wrap gap-1" aria-label="Tags">
              {document.tags.map((tag) => (
                <li key={tag}>
                  <Badge tone="primary">
                    <span dir="auto">{tag}</span>
                  </Badge>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
        <DocumentActions document={document} />
      </div>

      <div role="tablist" aria-label="Document view" className="flex gap-1 border-b border-border">
        {(
          [
            ['pages', 'Pages'],
            ['text', 'Text'],
          ] as const
        ).map(([value, label]) => (
          <button
            key={value}
            type="button"
            role="tab"
            id={`tab-${value}`}
            aria-selected={tab === value}
            aria-controls={`panel-${value}`}
            onClick={() => {
              setTab(value);
            }}
            className={cn(
              '-mb-px min-h-11 border-b-2 px-4 text-sm font-medium sm:min-h-9',
              tab === value
                ? 'border-primary text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground',
            )}
          >
            {label}
          </button>
        ))}
      </div>

      <div id="panel-pages" role="tabpanel" aria-labelledby="tab-pages" hidden={tab !== 'pages'}>
        <DocumentViewer documentId={document.id} title={document.title} pages={pages} />
      </div>
      <div id="panel-text" role="tabpanel" aria-labelledby="tab-text" hidden={tab !== 'text'}>
        {tab === 'text' ? <DocumentTextPanel documentId={document.id} /> : null}
      </div>
    </div>
  );
}

function DocumentTextPanel({ documentId }: { documentId: string }): ReactNode {
  const query = useQuery({
    queryKey: ['document-text', documentId],
    queryFn: ({ signal }) =>
      fetchEnvelope(`/app/api/documents/${documentId}/text`, DocumentTextSchema, 'site', signal),
  });
  if (query.isPending) {
    return (
      <div className="flex flex-col gap-2" aria-busy="true" aria-label="Loading the text">
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-4 w-11/12" />
        <Skeleton className="h-4 w-4/5" />
      </div>
    );
  }
  if (query.isError) {
    if ('code' in query.error && query.error.code === 'NOT_FOUND') {
      return <Alert tone="info">No text has been recognised in this document yet.</Alert>;
    }
    return (
      <div className="flex flex-col items-start gap-4">
        <Alert title="The text could not be loaded">{query.error.message}</Alert>
        <Button variant="secondary" onClick={() => void query.refetch()}>
          Try again
        </Button>
      </div>
    );
  }
  const { text, source } = query.data.data;
  if (text.trim() === '') {
    return <Alert tone="info">No text was found on these pages.</Alert>;
  }
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">
          {source === 'SERVER' ? 'Recognised on the server.' : 'Recognised on the phone.'}
        </p>
        <CopyButton value={text} label="Copy text" />
      </div>
      <pre
        dir="auto"
        className="max-h-[70dvh] overflow-auto whitespace-pre-wrap break-words rounded-lg border border-border bg-card p-6 font-sans text-sm leading-relaxed"
      >
        {text}
      </pre>
    </div>
  );
}
