import { isIP } from 'node:net';

/**
 * The browser's address, as the edge (Caddy) reported it in X-Forwarded-For.
 * Caddy replaces any X-Forwarded-For a client sends, and the web app is only
 * reachable through the edge, so the last entry is the one it wrote. The API
 * uses it for sign-in rate limits and the audit log.
 */
export function clientIpFrom(forwardedFor: string | null): string | null {
  if (forwardedFor === null) {
    return null;
  }
  const last = forwardedFor.split(',').at(-1)?.trim() ?? '';
  return isIP(last) === 0 ? null : last;
}
