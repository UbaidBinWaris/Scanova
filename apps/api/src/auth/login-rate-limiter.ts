import { Inject, Injectable } from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import { normalizeEmail } from '@scanova/crypto/blind-index';
import { sha256 } from '@scanova/crypto/random';
import { AppError } from '../common/app-error.js';
import { API_CONFIG, CLOCK, type Clock } from '../platform/tokens.js';

const WINDOW_MS = 60_000;
/** Bound on tracked keys, so a flood of distinct addresses cannot grow memory without limit. */
const MAX_TRACKED_KEYS = 50_000;
/**
 * Sign-in attempts per IP across all e-mail addresses, as a multiple of the
 * per-address limit. Stops one IP from spraying many accounts while leaving room
 * for a household or office behind one public address.
 */
const PER_IP_MULTIPLIER = 6;
/** Wrong current passwords allowed per user per minute on `POST /me/password`. */
const PASSWORD_CHANGE_ATTEMPTS_PER_MINUTE = 5;

/**
 * Sliding-window limits (§5.5), kept in memory: the API is a single process on
 * a single machine. Every attempt counts, successful or not, and it is checked
 * before the password hash is computed, so it also caps the CPU an attacker can
 * make the server burn. Limits:
 * - sign-in per IP + e-mail (`LOGIN_MAX_ATTEMPTS_PER_MINUTE`),
 * - sign-in per IP across all e-mails (that limit × PER_IP_MULTIPLIER),
 * - password change per user.
 */
@Injectable()
export class LoginRateLimiter {
  private readonly attempts = new Map<string, number[]>();

  public constructor(
    @Inject(API_CONFIG) private readonly config: ApiConfig,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  /** Records a sign-in attempt. Throws `RATE_LIMITED` with `Retry-After` when a window is full. */
  public hit(ip: string, email: string): void {
    const perAddress = this.config.auth.loginMaxAttemptsPerMinute;
    const now = this.clock().getTime();
    const ipKey = this.keyFor(`ip|${ip}`);
    const pairKey = this.keyFor(`${ip}|${normalizeEmail(email)}`);
    // Check both windows before recording, so a refused attempt does not consume either.
    this.assertRoom(ipKey, perAddress * PER_IP_MULTIPLIER, now);
    this.assertRoom(pairKey, perAddress, now);
    this.record(ipKey, now);
    this.record(pairKey, now);
  }

  /** Records a password-change attempt for a signed-in user. */
  public hitPasswordChange(userId: number): void {
    const now = this.clock().getTime();
    const key = this.keyFor(`password-change|${userId}`);
    this.assertRoom(key, PASSWORD_CHANGE_ATTEMPTS_PER_MINUTE, now);
    this.record(key, now);
  }

  /** A successful sign-in clears the counter for that IP + e-mail (not the per-IP one). */
  public reset(ip: string, email: string): void {
    this.attempts.delete(this.keyFor(`${ip}|${normalizeEmail(email)}`));
  }

  private assertRoom(key: string, limit: number, now: number): void {
    const recent = (this.attempts.get(key) ?? []).filter((time) => now - time < WINDOW_MS);
    this.attempts.set(key, recent);
    if (recent.length >= limit) {
      const oldest = recent[0] ?? now;
      const retryAfterSeconds = Math.max(1, Math.ceil((oldest + WINDOW_MS - now) / 1000));
      throw new AppError('RATE_LIMITED', { headers: { 'retry-after': String(retryAfterSeconds) } });
    }
  }

  private record(key: string, now: number): void {
    const recent = this.attempts.get(key) ?? [];
    recent.push(now);
    // Re-insert so the Map's iteration order stays least-recently-used first.
    this.attempts.delete(key);
    this.attempts.set(key, recent);
    this.evict();
  }

  private keyFor(material: string): string {
    // Hashed, so no e-mail address is ever held in this map.
    return sha256(material).toString('base64');
  }

  /**
   * Drops the least recently used half once the map is full. A Map iterates in
   * insertion order and every attempt re-inserts its key, so the first keys are
   * the ones that have been quiet longest. Amortised constant time per attempt.
   */
  private evict(): void {
    if (this.attempts.size <= MAX_TRACKED_KEYS) {
      return;
    }
    for (const key of this.attempts.keys()) {
      this.attempts.delete(key);
      if (this.attempts.size <= MAX_TRACKED_KEYS / 2) {
        break;
      }
    }
  }
}
