import 'server-only';
import type { ApiFailure } from './api/client';
import { envelopeJson } from './route';

/**
 * Passes a file from the API to the browser as a stream: nothing is buffered
 * or written to disk here. The answer is private and never cached.
 */
export function streamFile(
  upstream: Response | ApiFailure,
  contentType: 'application/pdf' | 'image/jpeg',
  disposition: string,
): Response {
  if (!(upstream instanceof Response)) {
    return envelopeJson(upstream);
  }
  const headers = new Headers({
    'content-type': contentType,
    'content-disposition': disposition,
    'cache-control': 'private, no-store',
    'x-content-type-options': 'nosniff',
  });
  for (const name of ['content-length', 'etag', 'x-content-sha256']) {
    const value = upstream.headers.get(name);
    if (value !== null) {
      headers.set(name, value);
    }
  }
  return new Response(upstream.body, { status: 200, headers });
}
