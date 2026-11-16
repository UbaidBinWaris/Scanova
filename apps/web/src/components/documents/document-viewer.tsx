'use client';

import type { DocumentPage } from '@scanova/contracts/documents';
import type { PDFDocumentProxy } from 'pdfjs-dist';
import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Button, buttonClasses } from '@/components/ui/button';
import { Alert, Skeleton } from '@/components/ui/feedback';
import {
  ChevronLeftIcon,
  ChevronRightIcon,
  DownloadIcon,
  ImageIcon,
  ZoomInIcon,
  ZoomOutIcon,
} from '@/components/ui/icons';
import { useToast } from '@/components/ui/toast';
import { cn } from '@/lib/cn';
import { pageFileName, safeFileName } from '@/lib/filename';
import { openPdf, renderPageToJpeg, saveBlob } from '@/lib/pdf';
import { PATHS } from '@/lib/surface';
import { PageCanvas } from './page-canvas';
import { useElementWidth, useNearViewport } from './use-element-width';

type LoadState =
  | { readonly status: 'loading' }
  | { readonly status: 'ready'; readonly pdf: PDFDocumentProxy }
  | { readonly status: 'error'; readonly message: string };

const ZOOMS = [0.5, 0.75, 1, 1.25, 1.5, 2, 3] as const;
const FIT = 2; // index of 1 (fit width)

/**
 * Renders the document's PDF in the browser with pdf.js. The file comes from
 * the web server (which streams it from the API); it is decrypted by the API
 * and never stored by the browser beyond this page's memory.
 */
export function DocumentViewer({
  documentId,
  title,
  pages,
}: {
  documentId: string;
  title: string;
  /** The server's page list (originals and recipes), when the phone backed it up. */
  pages: readonly DocumentPage[] | null;
}): ReactNode {
  const [state, setState] = useState<LoadState>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    let opened: PDFDocumentProxy | null = null;
    void (async () => {
      try {
        const response = await fetch(`/app/documents/${documentId}/file`, {
          credentials: 'same-origin',
          cache: 'no-store',
          signal: controller.signal,
        });
        if (response.status === 401) {
          window.location.assign(PATHS.site.expired);
          return;
        }
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        const pdf = await openPdf(new Uint8Array(await response.arrayBuffer()));
        if (controller.signal.aborted) {
          void pdf.loadingTask.destroy();
          return;
        }
        opened = pdf;
        setState({ status: 'ready', pdf });
      } catch {
        if (!controller.signal.aborted) {
          setState({ status: 'error', message: 'The PDF could not be opened.' });
        }
      }
    })();
    return () => {
      controller.abort();
      void opened?.loadingTask.destroy();
    };
  }, [documentId, attempt]);

  if (state.status === 'loading') {
    return (
      <div
        className="flex flex-col gap-4 md:flex-row"
        aria-busy="true"
        aria-label="Opening the document"
      >
        <Skeleton className="hidden h-[32rem] w-36 md:block" />
        <Skeleton className="h-[32rem] flex-1" />
      </div>
    );
  }
  if (state.status === 'error') {
    return (
      <div className="flex flex-col items-start gap-4">
        <Alert title={state.message}>Check your connection and try again.</Alert>
        <Button
          variant="secondary"
          onClick={() => {
            setState({ status: 'loading' });
            setAttempt((value) => value + 1);
          }}
        >
          Try again
        </Button>
      </div>
    );
  }
  return <ReadyViewer pdf={state.pdf} documentId={documentId} title={title} pages={pages} />;
}

function ReadyViewer({
  pdf,
  documentId,
  title,
  pages,
}: {
  pdf: PDFDocumentProxy;
  documentId: string;
  title: string;
  pages: readonly DocumentPage[] | null;
}): ReactNode {
  const { notify } = useToast();
  const count = pdf.numPages;
  const [current, setCurrent] = useState(1);
  const [zoomIndex, setZoomIndex] = useState(FIT);
  const [busy, setBusy] = useState<string | null>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const stageWidth = useElementWidth(stageRef);
  // The page list matches the PDF page for page when both describe the same document.
  const pageInfo = pages !== null && pages.length === count ? pages[current - 1] : undefined;

  const go = (page: number): void => {
    setCurrent(Math.min(count, Math.max(1, page)));
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.key === 'ArrowRight' || event.key === 'PageDown') {
      event.preventDefault();
      go(current + 1);
    } else if (event.key === 'ArrowLeft' || event.key === 'PageUp') {
      event.preventDefault();
      go(current - 1);
    }
  };

  const downloadPage = async (): Promise<void> => {
    setBusy('page');
    try {
      saveBlob(await renderPageToJpeg(pdf, current), pageFileName(title, current, count));
    } catch {
      notify('The page could not be saved as an image.');
    } finally {
      setBusy(null);
    }
  };

  const downloadAll = async (): Promise<void> => {
    setBusy('all-0');
    try {
      const { zipSync } = await import('fflate');
      const files: Record<string, [Uint8Array, { level: 0 }]> = {};
      for (let page = 1; page <= count; page += 1) {
        setBusy(`all-${page}`);
        const blob = await renderPageToJpeg(pdf, page);
        // JPEGs are already compressed: store them as they are.
        files[pageFileName(title, page, count)] = [
          new Uint8Array(await blob.arrayBuffer()),
          { level: 0 },
        ];
      }
      saveBlob(new Blob([zipSync(files)], { type: 'application/zip' }), safeFileName(title, 'zip'));
    } catch {
      notify('The pages could not be saved.');
    } finally {
      setBusy(null);
    }
  };

  const zoom = ZOOMS[zoomIndex] ?? 1;
  const pageWidth = Math.max(0, Math.floor(stageWidth * zoom));
  const allProgress = busy?.startsWith('all-') === true ? Number(busy.slice(4)) : null;

  return (
    <div className="flex flex-col gap-4 md:flex-row md:items-start">
      <nav
        aria-label="Pages"
        className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-2 md:sticky md:top-24 md:mx-0 md:max-h-[calc(100dvh-8rem)] md:w-36 md:shrink-0 md:flex-col md:overflow-y-auto md:overflow-x-hidden md:px-0"
      >
        {Array.from({ length: count }, (_, index) => (
          <Thumbnail
            key={index}
            pdf={pdf}
            pageNumber={index + 1}
            active={index + 1 === current}
            onSelect={() => {
              go(index + 1);
            }}
          />
        ))}
      </nav>

      <section
        aria-label={`Page ${current} of ${count}`}
        className="flex min-w-0 flex-1 flex-col gap-4"
      >
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              aria-label="Previous page"
              disabled={current <= 1}
              onClick={() => go(current - 1)}
            >
              <ChevronLeftIcon />
            </Button>
            <span className="min-w-24 text-center text-sm tabular-nums" aria-live="polite">
              Page {current} of {count}
            </span>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Next page"
              disabled={current >= count}
              onClick={() => go(current + 1)}
            >
              <ChevronRightIcon />
            </Button>
          </div>
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              aria-label="Zoom out"
              disabled={zoomIndex <= 0}
              onClick={() => setZoomIndex((value) => value - 1)}
            >
              <ZoomOutIcon />
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setZoomIndex(FIT)}
              aria-label="Fit to width"
            >
              {Math.round(zoom * 100)}%
            </Button>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Zoom in"
              disabled={zoomIndex >= ZOOMS.length - 1}
              onClick={() => setZoomIndex((value) => value + 1)}
            >
              <ZoomInIcon />
            </Button>
          </div>
          <div className="flex flex-wrap items-center gap-2 sm:ml-auto">
            <Button
              variant="secondary"
              size="sm"
              pending={busy === 'page'}
              disabled={busy !== null}
              onClick={() => void downloadPage()}
            >
              <DownloadIcon width={16} height={16} />
              Page as JPEG
            </Button>
            {pageInfo?.hasOriginal === true ? (
              <a
                className={buttonClasses('secondary', 'sm')}
                href={`/app/documents/${documentId}/pages/${pageInfo.id}/original?n=${current}`}
                download
              >
                <ImageIcon width={16} height={16} />
                Original photo
              </a>
            ) : null}
            <Button
              variant="secondary"
              size="sm"
              pending={allProgress !== null}
              disabled={busy !== null}
              onClick={() => void downloadAll()}
            >
              <DownloadIcon width={16} height={16} />
              {allProgress === null
                ? 'All pages (ZIP)'
                : `Page ${Math.max(1, allProgress)} of ${count}…`}
            </Button>
          </div>
        </div>

        {/* Focusable so the arrow keys turn pages; overflow scrolls when zoomed in. */}
        <div
          ref={stageRef}
          tabIndex={0}
          role="group"
          aria-roledescription="page viewer"
          aria-label="Use the arrow keys to turn pages"
          onKeyDown={onKeyDown}
          className="overflow-auto rounded-lg border border-border bg-surface-low p-0"
        >
          <div className={cn('flex min-w-full', zoom <= 1 ? 'justify-center' : 'justify-start')}>
            <PageCanvas
              pdf={pdf}
              pageNumber={current}
              cssWidth={pageWidth}
              label={`Page ${current} of ${count}`}
            />
          </div>
        </div>
        <p className="text-xs text-muted-foreground">
          JPEG downloads are drawn from the PDF at up to 300 dpi: the page as it was finished on the
          phone. “Original photo” is the untouched camera image, when the phone backed it up.
        </p>
      </section>
    </div>
  );
}

function Thumbnail({
  pdf,
  pageNumber,
  active,
  onSelect,
}: {
  pdf: PDFDocumentProxy;
  pageNumber: number;
  active: boolean;
  onSelect: () => void;
}): ReactNode {
  const ref = useRef<HTMLButtonElement>(null);
  const near = useNearViewport(ref);
  useEffect(() => {
    if (active) {
      ref.current?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }
  }, [active]);
  return (
    <button
      ref={ref}
      type="button"
      onClick={onSelect}
      aria-current={active ? 'page' : undefined}
      aria-label={`Page ${pageNumber}`}
      className={cn(
        'flex w-24 shrink-0 flex-col items-center gap-1 rounded p-1 text-xs md:w-full',
        active
          ? 'bg-primary-container text-primary-on-container'
          : 'text-muted-foreground hover:bg-surface-high',
      )}
    >
      <span className="flex min-h-28 w-full items-center justify-center overflow-hidden rounded-sm">
        {near ? (
          <PageCanvas
            pdf={pdf}
            pageNumber={pageNumber}
            cssWidth={88}
            label=""
            className="shadow-sm"
          />
        ) : (
          <span className="block h-28 w-[88px] bg-surface-high" />
        )}
      </span>
      <span aria-hidden="true">{pageNumber}</span>
    </button>
  );
}
