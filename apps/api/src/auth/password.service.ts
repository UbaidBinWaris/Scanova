import { Inject, Injectable } from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import { hashPassword, needsRehash, verifyPassword } from '@scanova/crypto/password';
import { randomToken } from '@scanova/crypto/random';
import { AppError } from '../common/app-error.js';
import { API_CONFIG } from '../platform/tokens.js';

/**
 * Argon2id runs at most this many at once. Each run holds ARGON2_MEMORY_KIB
 * (64 MiB by default), so this bounds the memory sign-in floods can take.
 */
const MAX_CONCURRENT_HASHES = 2;
/** Callers allowed to wait for a slot; beyond this the server answers 429 at once. */
const MAX_WAITING_HASHES = 16;

/** Argon2id with the parameters from the configuration (§5.5). */
@Injectable()
export class PasswordService {
  private decoyHash: Promise<string> | null = null;
  private running = 0;
  private readonly waiting: Array<() => void> = [];

  public constructor(@Inject(API_CONFIG) private readonly config: ApiConfig) {}

  public hash(password: string): Promise<string> {
    return this.gated(() => hashPassword(password, this.config.auth.argon2));
  }

  public verify(encodedHash: string, password: string): Promise<boolean> {
    return this.gated(() => verifyPassword(encodedHash, password));
  }

  private async gated<T>(work: () => Promise<T>): Promise<T> {
    if (this.running >= MAX_CONCURRENT_HASHES) {
      if (this.waiting.length >= MAX_WAITING_HASHES) {
        throw new AppError('RATE_LIMITED', { headers: { 'retry-after': '5' } });
      }
      await new Promise<void>((resolve) => this.waiting.push(resolve));
    } else {
      this.running += 1;
    }
    try {
      return await work();
    } finally {
      // Hand the slot straight to the next waiter, or free it.
      const next = this.waiting.shift();
      if (next === undefined) {
        this.running -= 1;
      } else {
        next();
      }
    }
  }

  /** True when the stored hash was made with other parameters than today's. */
  public needsRehash(encodedHash: string): boolean {
    return needsRehash(encodedHash, this.config.auth.argon2);
  }

  /**
   * Spends the same time as a real verification when there is no such account,
   * so the response time does not reveal which e-mail addresses exist.
   */
  public async verifyDecoy(password: string): Promise<void> {
    this.decoyHash ??= this.hash(randomToken());
    await this.verify(await this.decoyHash, password);
  }
}
