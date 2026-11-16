import { createHash } from 'node:crypto';
import type { Tokens } from '@scanova/contracts/auth';

export type RefreshOutcome =
  | { readonly kind: 'refreshed'; readonly tokens: Tokens }
  /** The API refused the refresh token for good (expired, revoked, account disabled). */
  | { readonly kind: 'rejected'; readonly code: string }
  /** The API could not be reached or failed; the session may still be fine. */
  | { readonly kind: 'unavailable' };

export type RefreshFunction = (
  refreshToken: string,
  clientIp: string | null,
) => Promise<RefreshOutcome>;

/**
 * Refresh tokens are single use, and the API revokes the whole session when a
 * spent one is presented again (§5.5). A browser easily sends several requests
 * at once with the same, about-to-expire cookie, so the web server must never
 * exchange the same refresh token twice:
 *
 * - concurrent refreshes of one token share a single API call, and
 * - a request that still carries the old cookie shortly after (it was sent
 *   before the browser stored the new one) receives the same new tokens.
 *
 * Entries are keyed by the SHA-256 of the refresh token and kept only for the
 * grace period. The web app runs as one process, so this state is complete.
 */
export class RefreshCoordinator {
  private readonly inflight = new Map<string, Promise<RefreshOutcome>>();
  private readonly recent = new Map<string, { outcome: RefreshOutcome; until: number }>();

  public constructor(
    private readonly refreshFn: RefreshFunction,
    private readonly now: () => number = Date.now,
    private readonly graceMs = 30_000,
    private readonly maxEntries = 10_000,
  ) {}

  public refresh(refreshToken: string, clientIp: string | null): Promise<RefreshOutcome> {
    const key = createHash('sha256').update(refreshToken).digest('base64url');
    this.sweep();
    const recent = this.recent.get(key);
    if (recent !== undefined) {
      return Promise.resolve(recent.outcome);
    }
    const pending = this.inflight.get(key);
    if (pending !== undefined) {
      return pending;
    }
    const promise = this.refreshFn(refreshToken, clientIp)
      .catch((): RefreshOutcome => ({ kind: 'unavailable' }))
      .then((outcome) => {
        // A failure to reach the API is not remembered: the next request may succeed.
        if (outcome.kind !== 'unavailable') {
          this.remember(key, outcome);
        }
        return outcome;
      })
      .finally(() => {
        this.inflight.delete(key);
      });
    this.inflight.set(key, promise);
    return promise;
  }

  private remember(key: string, outcome: RefreshOutcome): void {
    if (this.recent.size >= this.maxEntries) {
      const oldest = this.recent.keys().next();
      if (oldest.done !== true) {
        this.recent.delete(oldest.value);
      }
    }
    this.recent.set(key, { outcome, until: this.now() + this.graceMs });
  }

  private sweep(): void {
    const now = this.now();
    for (const [key, entry] of this.recent) {
      if (entry.until > now) {
        // Insertion order is expiry order: everything after this one is newer.
        break;
      }
      this.recent.delete(key);
    }
  }
}
