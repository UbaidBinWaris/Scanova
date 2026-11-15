import { afterEach, describe, expect, it } from 'vitest';
import { MAX_SECRET_TTL_MS, SecretCache } from './secret-cache.js';

describe('SecretCache', () => {
  let cache: SecretCache;
  let clock = 0;

  afterEach(() => {
    cache.close();
  });

  function create(ttlMs?: number): SecretCache {
    clock = 1_000_000;
    cache = new SecretCache({ ttlMs, now: () => clock });
    return cache;
  }

  /** The cached original, reached through the private map: only a test may look at it. */
  function original(id: string): Buffer | undefined {
    return (cache as unknown as { entries: Map<string, { secret: Buffer }> }).entries.get(id)
      ?.secret;
  }

  it('hands out copies, so wiping a copy does not damage the cache', () => {
    create();
    const secret = Buffer.alloc(32, 7);
    cache.set('a', secret);
    secret.fill(0);

    const first = cache.get('a')!;
    expect(first.equals(Buffer.alloc(32, 7))).toBe(true);
    first.fill(0);
    expect(cache.get('a')!.equals(Buffer.alloc(32, 7))).toBe(true);
  });

  it('expires entries after the TTL and overwrites them on eviction', () => {
    create(60_000);
    cache.set('a', Buffer.alloc(32, 7));
    const held = original('a')!;
    clock += 59_999;
    expect(cache.get('a')).not.toBeNull();
    clock += 1;
    expect(cache.get('a')).toBeNull();
    expect(held.equals(Buffer.alloc(32))).toBe(true);
    expect(cache.size).toBe(0);
  });

  it('never keeps a secret longer than five minutes, whatever TTL is asked for', () => {
    create(60 * 60_000);
    cache.set('a', Buffer.alloc(32, 7));
    clock += MAX_SECRET_TTL_MS;
    expect(cache.get('a')).toBeNull();
  });

  it('overwrites on replace, delete, prefix delete, sweep and close', () => {
    create(60_000);
    cache.set('a', Buffer.alloc(32, 1));
    const replaced = original('a')!;
    cache.set('a', Buffer.alloc(32, 2));
    expect(replaced.equals(Buffer.alloc(32))).toBe(true);

    const deleted = original('a')!;
    cache.delete('a');
    expect(deleted.equals(Buffer.alloc(32))).toBe(true);

    cache.set('user:1:a', Buffer.alloc(32, 3));
    cache.set('user:1:b', Buffer.alloc(32, 4));
    cache.set('user:2:a', Buffer.alloc(32, 5));
    const byPrefix = original('user:1:a')!;
    cache.deleteByPrefix('user:1:');
    expect(byPrefix.equals(Buffer.alloc(32))).toBe(true);
    expect(cache.get('user:1:b')).toBeNull();
    expect(cache.get('user:2:a')).not.toBeNull();

    const swept = original('user:2:a')!;
    clock += 60_000;
    cache.evictExpired();
    expect(swept.equals(Buffer.alloc(32))).toBe(true);

    cache.set('z', Buffer.alloc(32, 9));
    const closed = original('z')!;
    cache.close();
    expect(closed.equals(Buffer.alloc(32))).toBe(true);
  });
});
