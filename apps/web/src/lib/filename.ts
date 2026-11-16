// Control characters are exactly what this strips.
// eslint-disable-next-line no-control-regex
const UNSAFE = /[\u0000-\u001f\u007f"*/:<>?\\|]+/gu;

/** A file name from a document title: no path separators, control or reserved characters. */
export function safeFileName(title: string, extension: string, fallback = 'document'): string {
  const base = title.normalize('NFC').replace(UNSAFE, ' ').replace(/\s+/gu, ' ').trim();
  const trimmed = Array.from(base)
    .slice(0, 120)
    .join('')
    .replace(/[. ]+$/u, '');
  return `${trimmed === '' ? fallback : trimmed}.${extension}`;
}

/**
 * `Content-Disposition` with an ASCII fallback and the UTF-8 name (RFC 6266 /
 * RFC 8187), so titles in Urdu or with accents download with their real name.
 */
export function contentDisposition(
  kind: 'inline' | 'attachment',
  title: string,
  extension: string,
): string {
  const name = safeFileName(title, extension);
  const ascii = name.replace(/[^\x20-\x7e]/gu, '_').replace(/[%;]/gu, '_');
  return `${kind}; filename="${ascii}"; filename*=UTF-8''${encodeRfc8187(name)}`;
}

function encodeRfc8187(value: string): string {
  return encodeURIComponent(value).replace(
    /['()*]/gu,
    (character) => `%${character.charCodeAt(0).toString(16).toUpperCase()}`,
  );
}

/** "Title — page 3.jpg", zero-padded so the files sort in page order. */
export function pageFileName(
  title: string,
  pageNumber: number,
  pageCount: number,
  suffix = '',
): string {
  const digits = String(pageCount).length;
  const page = String(pageNumber).padStart(digits, '0');
  return safeFileName(`${title} — page ${page}${suffix}`, 'jpg');
}
