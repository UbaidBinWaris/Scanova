'use client';

import { DocumentListSchema, type Document } from '@scanova/contracts/documents';
import { useInfiniteQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button, buttonClasses } from '@/components/ui/button';
import { Alert, EmptyState, PageHeader, Skeleton } from '@/components/ui/feedback';
import { Input, Select } from '@/components/ui/field';
import { DocumentIcon, DownloadIcon, SearchIcon } from '@/components/ui/icons';
import { LocalTime } from '@/components/ui/local-time';
import { fetchEnvelope } from '@/lib/client-api';
import { formatBytes, pluralize } from '@/lib/format';
import {
  DOCUMENT_SORTS,
  isDocumentSort,
  sortDocuments,
  sortNeedsAllDocuments,
  type DocumentSort,
} from '@/lib/sorting';
import { OcrBadge } from './ocr-badge';

/** Documents per API page; the API allows up to 200. */
const API_PAGE = 100;
/** Rows added by "Show more". */
const STEP = 50;
/** Sorting other than by last change loads at most this many documents. */
const MAX_FOR_SORT = 5000;

function listUrl(q: string, cursor: string | null): string {
  const params = new URLSearchParams({ limit: String(API_PAGE) });
  if (q !== '') {
    params.set('q', q);
  }
  if (cursor !== null) {
    params.set('cursor', cursor);
  }
  return `/app/api/documents?${params.toString()}`;
}

export function DocumentsBrowser(): ReactNode {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const initialQuery = (searchParams.get('q') ?? '').slice(0, 200);
  const initialSort = searchParams.get('sort');

  const [text, setText] = useState(initialQuery);
  const [q, setQ] = useState(initialQuery.trim());
  const [sort, setSort] = useState<DocumentSort>(
    isDocumentSort(initialSort) ? initialSort : 'recent',
  );
  const [visible, setVisible] = useState(STEP);
  const searchRef = useRef<HTMLInputElement>(null);

  const updateUrl = (nextQ: string, nextSort: DocumentSort): void => {
    const params = new URLSearchParams();
    if (nextQ !== '') {
      params.set('q', nextQ);
    }
    if (nextSort !== 'recent') {
      params.set('sort', nextSort);
    }
    const query = params.toString();
    router.replace(query === '' ? pathname : `${pathname}?${query}`, { scroll: false });
  };

  // Search as the person types, 300 ms after the last key.
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setQ(text.trim());
      setVisible(STEP);
    }, 300);
    return () => {
      window.clearTimeout(timer);
    };
  }, [text]);

  const query = useInfiniteQuery({
    queryKey: ['documents', q],
    queryFn: ({ pageParam, signal }) =>
      fetchEnvelope(listUrl(q, pageParam), DocumentListSchema, 'site', signal),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
  const { data, hasNextPage, isFetchingNextPage, fetchNextPage } = query;

  const loaded = useMemo<Document[]>(
    () =>
      (data?.pages ?? [])
        .flatMap((page) => page.data)
        // Like the app: a scan still uploading from a phone is not shown yet.
        .filter((document) => document.status === 'READY' && document.deletedAt === null),
    [data],
  );
  const needsAll = sortNeedsAllDocuments(sort);

  useEffect(() => {
    if (needsAll && hasNextPage && !isFetchingNextPage && loaded.length < MAX_FOR_SORT) {
      void fetchNextPage();
    }
  }, [needsAll, hasNextPage, isFetchingNextPage, loaded.length, fetchNextPage]);

  const sorted = useMemo(() => sortDocuments(loaded, sort), [loaded, sort]);
  const shown = sorted.slice(0, visible);
  const loadingAll = needsAll && hasNextPage && loaded.length < MAX_FOR_SORT;
  const canShowMore = visible < sorted.length || (!needsAll && hasNextPage);

  const showMore = (): void => {
    if (visible >= sorted.length && hasNextPage) {
      void fetchNextPage();
    }
    setVisible((count) => count + STEP);
  };

  const countLabel = query.isPending
    ? 'Loading…'
    : `${pluralize(sorted.length, 'document')}${hasNextPage ? ' so far' : ''}${q === '' ? '' : ` matching “${q}”`}`;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="Documents" description={<span aria-live="polite">{countLabel}</span>} />

      <div className="flex flex-col gap-4 sm:flex-row sm:items-end">
        <div className="flex flex-1 flex-col gap-1.5">
          <label htmlFor="search" className="text-sm font-medium">
            Search
          </label>
          <div className="relative">
            <SearchIcon
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
              width={18}
              height={18}
            />
            <Input
              ref={searchRef}
              id="search"
              type="search"
              value={text}
              maxLength={200}
              placeholder="Words in titles, tags or text"
              aria-describedby="search-hint"
              className="pl-10"
              onChange={(event) => {
                setText(event.target.value);
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  updateUrl(text.trim(), sort);
                }
              }}
              onBlur={() => {
                updateUrl(text.trim(), sort);
              }}
            />
          </div>
          <p id="search-hint" className="text-xs text-muted-foreground">
            Whole words; every word must appear. Recognised text counts too.
          </p>
        </div>
        <div className="flex flex-col gap-1.5 sm:w-56 sm:pb-[1.375rem]">
          <label htmlFor="sort" className="text-sm font-medium">
            Sort by
          </label>
          <Select
            id="sort"
            value={sort}
            onChange={(event) => {
              const next = isDocumentSort(event.target.value) ? event.target.value : 'recent';
              setSort(next);
              setVisible(STEP);
              updateUrl(q, next);
            }}
          >
            {Object.entries(DOCUMENT_SORTS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </Select>
        </div>
      </div>

      {query.isPending ? (
        <ListSkeleton />
      ) : query.isError ? (
        <div className="flex flex-col items-start gap-4">
          <Alert title="The documents could not be loaded">{query.error.message}</Alert>
          <Button variant="secondary" onClick={() => void query.refetch()}>
            Try again
          </Button>
        </div>
      ) : sorted.length === 0 && !hasNextPage ? (
        q === '' ? (
          <EmptyState
            icon={<DocumentIcon />}
            title="No documents yet"
            description="Scan with the Scanova app on your phone and sign in there: your documents are backed up and appear here."
            action={
              <a href="/" className={buttonClasses('primary')}>
                <DownloadIcon width={18} height={18} />
                Get the Android app
              </a>
            }
          />
        ) : (
          <EmptyState
            icon={<SearchIcon />}
            title="No matching documents"
            description={`Nothing contains every word of “${q}”. Check the spelling or search for fewer words.`}
            action={
              <Button
                variant="secondary"
                onClick={() => {
                  setText('');
                  setQ('');
                  updateUrl('', sort);
                  searchRef.current?.focus();
                }}
              >
                Clear search
              </Button>
            }
          />
        )
      ) : (
        <>
          {loadingAll ? (
            <Alert tone="info">
              Loading all documents to sort them ({pluralize(loaded.length, 'document')} so far)…
            </Alert>
          ) : null}
          <ul className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-card">
            {shown.map((document) => (
              <DocumentRow key={document.id} document={document} />
            ))}
          </ul>
          {canShowMore ? (
            <div className="flex justify-center">
              <Button variant="secondary" pending={isFetchingNextPage} onClick={showMore}>
                Show more
              </Button>
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}

function DocumentRow({ document }: { document: Document }): ReactNode {
  const extraTags = document.tags.length - 3;
  return (
    <li>
      <Link
        href={`/app/documents/${document.id}`}
        className="flex items-center gap-4 px-4 py-4 transition-colors hover:bg-surface-low focus-visible:bg-surface-low"
      >
        <span className="hidden h-10 w-10 shrink-0 items-center justify-center rounded bg-primary-container text-primary-on-container sm:flex">
          <DocumentIcon />
        </span>
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="truncate text-sm font-medium" dir="auto">
            {document.title}
          </span>
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
            <span>{pluralize(document.pageCount, 'page')}</span>
            <span aria-hidden="true">·</span>
            <span>{formatBytes(document.sizeBytes)}</span>
            <span aria-hidden="true">·</span>
            <span>
              Changed <LocalTime iso={document.updatedAt} mode="date" />
            </span>
          </span>
          {document.tags.length > 0 ? (
            <span className="flex flex-wrap gap-1">
              {document.tags.slice(0, 3).map((tag) => (
                <Badge key={tag}>
                  <span dir="auto">{tag}</span>
                </Badge>
              ))}
              {extraTags > 0 ? <Badge>+{extraTags}</Badge> : null}
            </span>
          ) : null}
        </span>
        <span className="hidden shrink-0 md:block">
          <OcrBadge status={document.ocrStatus} />
        </span>
      </Link>
    </li>
  );
}

function ListSkeleton(): ReactNode {
  return (
    <div className="flex flex-col gap-2" aria-busy="true" aria-label="Loading documents">
      {Array.from({ length: 6 }, (_, index) => (
        <Skeleton key={index} className="h-16 w-full" />
      ))}
    </div>
  );
}
