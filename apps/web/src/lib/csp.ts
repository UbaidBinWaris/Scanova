export interface CspOptions {
  /** Per-response random value; Next.js puts it on every script it renders. */
  readonly nonce: string;
  /** Development needs eval for fast refresh. */
  readonly development: boolean;
  /** Ask the browser to upgrade stray http:// subresources (only on https origins). */
  readonly https: boolean;
}

/**
 * Strict, nonce-based Content-Security-Policy. No 'unsafe-inline' for scripts
 * or styles: scripts run only with this response's nonce (and what they load,
 * 'strict-dynamic'); styles come from our own stylesheet files.
 * 'wasm-unsafe-eval' lets pdf.js run its image decoders, which ship as
 * WebAssembly served from this origin; it does not allow JavaScript eval.
 */
export function buildContentSecurityPolicy(options: CspOptions): string {
  const scriptSources = [
    `'self'`,
    `'nonce-${options.nonce}'`,
    `'strict-dynamic'`,
    `'wasm-unsafe-eval'`,
    ...(options.development ? [`'unsafe-eval'`] : []),
  ];
  const directives = [
    `default-src 'self'`,
    `script-src ${scriptSources.join(' ')}`,
    `style-src 'self' 'nonce-${options.nonce}'`,
    `img-src 'self' blob: data:`,
    `font-src 'self'`,
    `connect-src 'self'${options.development ? ' ws:' : ''}`,
    `worker-src 'self' blob:`,
    `media-src 'none'`,
    `object-src 'none'`,
    `frame-src 'none'`,
    `manifest-src 'self'`,
    `base-uri 'none'`,
    `form-action 'self'`,
    `frame-ancestors 'none'`,
    ...(options.https ? ['upgrade-insecure-requests'] : []),
  ];
  return directives.join('; ');
}
