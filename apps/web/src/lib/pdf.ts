import type * as PdfJsModule from 'pdfjs-dist';
import type { PDFDocumentProxy } from 'pdfjs-dist';

/** pdf.js's own files, copied next to the app at build time (scripts/pdfjs-assets.mjs). */
const ASSETS = '/app/pdfjs';

type PdfJs = typeof PdfJsModule;

let pdfjsPromise: Promise<PdfJs> | null = null;

/** Loaded on demand, in the browser only. The worker comes from this origin (CSP worker-src 'self'). */
export function loadPdfJs(): Promise<PdfJs> {
  pdfjsPromise ??= import('pdfjs-dist').then((pdfjs) => {
    pdfjs.GlobalWorkerOptions.workerSrc = `${ASSETS}/pdf.worker.min.mjs`;
    return pdfjs;
  });
  return pdfjsPromise;
}

export async function openPdf(data: Uint8Array): Promise<PDFDocumentProxy> {
  const pdfjs = await loadPdfJs();
  return pdfjs.getDocument({
    data,
    wasmUrl: `${ASSETS}/wasm/`,
    standardFontDataUrl: `${ASSETS}/standard_fonts/`,
    cMapUrl: `${ASSETS}/cmaps/`,
    cMapPacked: true,
    enableXfa: false,
  }).promise;
}

/** Export resolution: 300 dpi, at most 4096 px on the longest side (canvas limits on phones). */
const EXPORT_DPI = 300;
const MAX_EXPORT_SIDE = 4096;

/**
 * One page as a JPEG, drawn from the PDF exactly as it is stored: the page as
 * it was finished on the phone (cropped, filtered, with markup). The original
 * camera photo is a separate download.
 */
export async function renderPageToJpeg(pdf: PDFDocumentProxy, pageNumber: number): Promise<Blob> {
  const page = await pdf.getPage(pageNumber);
  try {
    const base = page.getViewport({ scale: 1 });
    const scale = Math.min(EXPORT_DPI / 72, MAX_EXPORT_SIDE / Math.max(base.width, base.height));
    const viewport = page.getViewport({ scale });
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(viewport.width));
    canvas.height = Math.max(1, Math.round(viewport.height));
    await page.render({ canvas, viewport, background: '#ffffff' }).promise;
    const blob = await new Promise<Blob>((resolve, reject) => {
      canvas.toBlob(
        (result) => {
          if (result === null) {
            reject(new Error('The page could not be encoded.'));
          } else {
            resolve(result);
          }
        },
        'image/jpeg',
        0.92,
      );
    });
    // Free the bitmap now rather than at the next garbage collection.
    canvas.width = 0;
    canvas.height = 0;
    return blob;
  } finally {
    page.cleanup();
  }
}

/** Hands a file to the browser's download manager. */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  link.rel = 'noopener';
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => {
    URL.revokeObjectURL(url);
  }, 30_000);
}
