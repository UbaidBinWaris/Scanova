import { zeroize } from '@scanova/crypto/zeroize';

/** Unwrapped keys stay in memory for at most five minutes (§5.2). */
export const MAX_SECRET_TTL_MS = 5 * 60_000;

interface Entry {
  readonly secret: Buffer;
  readonly expiresAt: number;
}

/**
 * A small time-limited cache for unwrapped key material.
 *
 * - `get` returns a COPY. The caller wipes its copy when done; the cached
 *   original is wiped on eviction. Handing out the original would let an
 *   eviction zero a key in the middle of someone's encryption.
 * - Entries expire after the TTL (capped at five minutes) and are overwritten
 *   with zeros when they expire, are replaced, are deleted, or the cache closes.
 */
export class SecretCache {
  private readonly entries = new Map<string, Entry>();
  private readonly ttlMs: number;
  private readonly now: () => number;
  private readonly sweeper: NodeJS.Timeout;

  public constructor(options: { ttlMs?: number; now?: () => number } = {}) {
    this.ttlMs = Math.min(options.ttlMs ?? MAX_SECRET_TTL_MS, MAX_SECRET_TTL_MS);
    this.now = options.now ?? Date.now;
    // Expired keys must not linger until the next lookup of the same id.
    this.sweeper = setInterval(
      () => {
        this.evictExpired();
      },
      Math.max(1000, Math.floor(this.ttlMs / 4)),
    );
    this.sweeper.unref();
  }

  public get size(): number {
    return this.entries.size;
  }

  /** A copy of the cached secret, or null. The caller owns (and must wipe) the copy. */
  public get(id: string): Buffer | null {
    const entry = this.entries.get(id);
    if (entry === undefined) {
      return null;
    }
    if (entry.expiresAt <= this.now()) {
      this.evict(id, entry);
      return null;
    }
    return Buffer.from(entry.secret);
  }

  /** Stores a copy of `secret`; the caller keeps ownership of its own buffer. */
  public set(id: string, secret: Uint8Array): void {
    const previous = this.entries.get(id);
    if (previous !== undefined) {
      zeroize(previous.secret);
    }
    this.entries.set(id, { secret: Buffer.from(secret), expiresAt: this.now() + this.ttlMs });
  }

  public delete(id: string): void {
    const entry = this.entries.get(id);
    if (entry !== undefined) {
      this.evict(id, entry);
    }
  }

  /** Removes every entry whose id starts with `prefix` (all keys of one user). */
  public deleteByPrefix(prefix: string): void {
    for (const [id, entry] of this.entries) {
      if (id.startsWith(prefix)) {
        this.evict(id, entry);
      }
    }
  }

  public evictExpired(): void {
    const now = this.now();
    for (const [id, entry] of this.entries) {
      if (entry.expiresAt <= now) {
        this.evict(id, entry);
      }
    }
  }

  /** Wipes everything and stops the sweeper. */
  public close(): void {
    clearInterval(this.sweeper);
    for (const [id, entry] of this.entries) {
      this.evict(id, entry);
    }
  }

  private evict(id: string, entry: Entry): void {
    zeroize(entry.secret);
    this.entries.delete(id);
  }
}
