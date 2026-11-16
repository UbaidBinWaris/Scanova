import { PATHS, type Surface } from './surface';

/** What the proxy does with a request before the app sees it. */
export type RouteDecision =
  | { readonly kind: 'not-found' }
  | { readonly kind: 'redirect'; readonly location: string }
  /** Sign-in: open to everyone. */
  | { readonly kind: 'public-page' }
  /** Pages behind a session: without one, the browser is sent to sign-in. */
  | { readonly kind: 'protected-page' }
  /** JSON and file routes behind a session: without one, a 401. */
  | { readonly kind: 'protected-data' }
  /** The expired-session route: it inspects the session itself. */
  | { readonly kind: 'session-endpoint' }
  /** Container health check. */
  | { readonly kind: 'health' };

const DOCUMENT_FILE = /^\/app\/documents\/[^/]+\/file$/u;
const PAGE_ORIGINAL = /^\/app\/documents\/[^/]+\/pages\/[^/]+\/original$/u;

function within(pathname: string, prefix: string): boolean {
  return pathname === prefix || pathname.startsWith(`${prefix}/`);
}

/**
 * Host-based routing: each surface answers only its own paths, so the admin
 * console does not exist on the member host and vice versa.
 */
export function classifyRequest(surface: Surface, pathname: string): RouteDecision {
  if (pathname === '/healthz') {
    return { kind: 'health' };
  }
  const paths = PATHS[surface];
  const other = PATHS[surface === 'site' ? 'admin' : 'site'];
  if (surface === 'admin' && pathname === '/') {
    return { kind: 'redirect', location: paths.root };
  }
  if (!within(pathname, paths.root) || within(pathname, other.root)) {
    return { kind: 'not-found' };
  }
  if (within(pathname, paths.signIn)) {
    return { kind: 'public-page' };
  }
  if (pathname === paths.expired) {
    return { kind: 'session-endpoint' };
  }
  if (
    within(pathname, `${paths.root}/api`) ||
    (surface === 'site' && (DOCUMENT_FILE.test(pathname) || PAGE_ORIGINAL.test(pathname)))
  ) {
    return { kind: 'protected-data' };
  }
  return { kind: 'protected-page' };
}
