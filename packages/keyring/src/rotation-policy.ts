/**
 * The rotation policy: when is a key due?
 *
 * This is deliberately the ONLY place that decides it, and it is a pure
 * function: no clock, no database, no vault. The rotation engine asks this
 * question for every key it looks after (user-type master keys, user keys, the
 * token-signing key) and acts on the answer. To change the policy — a different
 * interval per user type, a hard maximum age, rotation after N uses — change
 * this function and its tests; the engine does not need to know.
 */

const MILLISECONDS_PER_DAY = 86_400_000;

export interface RotationDueInput {
  /**
   * When the key's newest version was created. For a key that has never been
   * rotated this is simply its creation time.
   */
  readonly lastRotatedAt: Date;
  /** The current time, passed in so the decision is reproducible in tests. */
  readonly now: Date;
  /**
   * Maximum age of the newest version, in days (`KEY_ROTATION_*_DAYS`).
   * Zero or a negative number switches scheduled rotation off for that key;
   * rotation on demand still works.
   */
  readonly intervalDays: number;
}

/**
 * A key is due when its newest version is at least `intervalDays` old.
 *
 * Edge cases, on purpose:
 * - Exactly at the boundary counts as due (">=", not ">"), so a daily sweep
 *   never lets a key run a full extra day past its interval.
 * - A `lastRotatedAt` in the future (clock skew between the vault and this
 *   machine) is never due: a wrong clock must not trigger a rotation storm.
 * - An invalid date is never due, for the same reason. Missing data is a
 *   monitoring problem, not a reason to rotate.
 */
export function isRotationDue(input: RotationDueInput): boolean {
  if (!Number.isFinite(input.intervalDays) || input.intervalDays <= 0) {
    return false;
  }
  const ageMs = input.now.getTime() - input.lastRotatedAt.getTime();
  if (Number.isNaN(ageMs) || ageMs < 0) {
    return false;
  }
  return ageMs >= input.intervalDays * MILLISECONDS_PER_DAY;
}

/** Whole days since the newest version was created; never negative. For status displays. */
export function keyAgeDays(lastRotatedAt: Date, now: Date): number {
  const ageMs = now.getTime() - lastRotatedAt.getTime();
  return Number.isNaN(ageMs) || ageMs < 0 ? 0 : Math.floor(ageMs / MILLISECONDS_PER_DAY);
}
