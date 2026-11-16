'use client';

import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist';
import { useEffect, useRef, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

/**
 * Draws one PDF page into a canvas at `cssWidth` CSS pixels (sharp on high-DPI
 * screens). The size is applied through the CSSOM, which the strict CSP allows.
 */
export function PageCanvas({
  pdf,
  pageNumber,
  cssWidth,
  label,
  className,
}: {
  pdf: PDFDocumentProxy;
  pageNumber: number;
  cssWidth: number;
  /** Empty for a decorative copy (thumbnails, whose button is labelled). */
  label: string;
  className?: string;
}): ReactNode {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas === null || cssWidth <= 0) {
      return;
    }
    let cancelled = false;
    let task: RenderTask | null = null;
    void (async () => {
      try {
        const page = await pdf.getPage(pageNumber);
        if (cancelled) {
          return;
        }
        const base = page.getViewport({ scale: 1 });
        const viewport = page.getViewport({ scale: cssWidth / base.width });
        const ratio = Math.min(window.devicePixelRatio || 1, 3);
        canvas.width = Math.max(1, Math.floor(viewport.width * ratio));
        canvas.height = Math.max(1, Math.floor(viewport.height * ratio));
        canvas.style.width = `${Math.floor(viewport.width)}px`;
        canvas.style.height = `${Math.floor(viewport.height)}px`;
        task = page.render({
          canvas,
          viewport,
          background: '#ffffff',
          ...(ratio === 1 ? {} : { transform: [ratio, 0, 0, ratio, 0, 0] }),
        });
        await task.promise;
      } catch {
        // Cancelled by a newer render (resize, zoom, page change), or the document closed.
      }
    })();
    return () => {
      cancelled = true;
      task?.cancel();
    };
  }, [pdf, pageNumber, cssWidth]);

  return (
    <canvas
      ref={canvasRef}
      {...(label === '' ? { 'aria-hidden': true } : { role: 'img', 'aria-label': label })}
      className={cn('block max-w-none bg-white', className)}
    />
  );
}
