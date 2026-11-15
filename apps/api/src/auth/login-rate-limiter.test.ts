import type { ApiConfig } from '@scanova/config/sections';
import { describe, expect, it } from 'vitest';
import { AppError } from '../common/app-error.js';
import { LoginRateLimiter } from './login-rate-limiter.js';

function setup(limit: number): { limiter: LoginRateLimiter; clock: { now: number } } {
  const clock = { now: 1_000_000 };
  const config = { auth: { loginMaxAttemptsPerMinute: limit } } as unknown as ApiConfig;
  return { limiter: new LoginRateLimiter(config, () => new Date(clock.now)), clock };
}

function retryAfter(action: () => void): number | null {
  try {
    action();
    return null;
  } catch (error) {
    expect(error).toBeInstanceOf(AppError);
    expect((error as AppError).code).toBe('RATE_LIMITED');
    return Number((error as AppError).headers['retry-after']);
  }
}

describe('LoginRateLimiter', () => {
  it('allows the configured number of attempts per minute, then asks to wait', () => {
    const { limiter, clock } = setup(3);
    for (let attempt = 0; attempt < 3; attempt += 1) {
      expect(retryAfter(() => limiter.hit('10.0.0.1', 'a@example.org'))).toBeNull();
      clock.now += 10_000;
    }
    // The oldest attempt was 30 s ago: 30 s until it leaves the window.
    expect(retryAfter(() => limiter.hit('10.0.0.1', 'a@example.org'))).toBe(30);
    clock.now += 30_000;
    expect(retryAfter(() => limiter.hit('10.0.0.1', 'a@example.org'))).toBeNull();
  });

  it('counts per IP and e-mail, ignoring case and surrounding space in the address', () => {
    const { limiter } = setup(1);
    expect(retryAfter(() => limiter.hit('10.0.0.1', 'a@example.org'))).toBeNull();
    expect(retryAfter(() => limiter.hit('10.0.0.1', '  A@Example.ORG '))).toBe(60);
    expect(retryAfter(() => limiter.hit('10.0.0.2', 'a@example.org'))).toBeNull();
    expect(retryAfter(() => limiter.hit('10.0.0.1', 'b@example.org'))).toBeNull();
  });

  it('starts over after a successful sign-in', () => {
    const { limiter } = setup(2);
    limiter.hit('10.0.0.1', 'a@example.org');
    limiter.hit('10.0.0.1', 'a@example.org');
    limiter.reset('10.0.0.1', 'A@example.org');
    expect(retryAfter(() => limiter.hit('10.0.0.1', 'a@example.org'))).toBeNull();
  });

  it('keeps blocking while attempts continue and does not grow without bound', () => {
    const { limiter, clock } = setup(2);
    limiter.hit('10.0.0.1', 'a@example.org');
    limiter.hit('10.0.0.1', 'a@example.org');
    for (let attempt = 0; attempt < 100; attempt += 1) {
      expect(retryAfter(() => limiter.hit('10.0.0.1', 'a@example.org'))).not.toBeNull();
    }
    for (let index = 0; index < 60_000; index += 1) {
      limiter.hit(`10.1.${index >> 8}.${index & 255}`, 'flood@example.org');
    }
    const tracked = (limiter as unknown as { attempts: Map<string, number[]> }).attempts.size;
    expect(tracked).toBeLessThanOrEqual(50_001);
    clock.now += 61_000;
    expect(retryAfter(() => limiter.hit('10.0.0.1', 'a@example.org'))).toBeNull();
  });
});
