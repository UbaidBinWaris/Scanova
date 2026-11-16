const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

export function isSafeMethod(method: string): boolean {
  return SAFE_METHODS.has(method.toUpperCase());
}

/**
 * CSRF defence for every state-changing request, on top of SameSite=Strict:
 * the browser's `Origin` must be exactly this surface's origin, and when the
 * browser says where the request comes from (`Sec-Fetch-Site`), it must be the
 * same origin. A request without `Origin` is refused: every browser that can
 * run this app sends it on POST.
 */
export function isSameOriginRequest(
  headers: { get(name: string): string | null },
  expectedOrigin: string,
): boolean {
  const origin = headers.get('origin');
  if (origin === null || origin !== expectedOrigin) {
    return false;
  }
  const site = headers.get('sec-fetch-site');
  return site === null || site === 'same-origin';
}
