import { describe, expect, it } from 'vitest';
import { isRotationDue, keyAgeDays } from './rotation-policy.js';

const now = new Date('2026-11-15T12:00:00Z');
const daysAgo = (days: number, extraMs = 0): Date =>
  new Date(now.getTime() - days * 86_400_000 - extraMs);

describe('isRotationDue', () => {
  it('is not due before the interval has passed', () => {
    expect(isRotationDue({ lastRotatedAt: daysAgo(0), now, intervalDays: 90 })).toBe(false);
    expect(isRotationDue({ lastRotatedAt: daysAgo(89), now, intervalDays: 90 })).toBe(false);
    expect(
      isRotationDue({ lastRotatedAt: new Date(daysAgo(90).getTime() + 1), now, intervalDays: 90 }),
    ).toBe(false);
  });

  it('is due exactly at the boundary and afterwards', () => {
    expect(isRotationDue({ lastRotatedAt: daysAgo(90), now, intervalDays: 90 })).toBe(true);
    expect(isRotationDue({ lastRotatedAt: daysAgo(90, 1), now, intervalDays: 90 })).toBe(true);
    expect(isRotationDue({ lastRotatedAt: daysAgo(400), now, intervalDays: 90 })).toBe(true);
  });

  it('never fires when scheduled rotation is switched off', () => {
    expect(isRotationDue({ lastRotatedAt: daysAgo(10_000), now, intervalDays: 0 })).toBe(false);
    expect(isRotationDue({ lastRotatedAt: daysAgo(10_000), now, intervalDays: -1 })).toBe(false);
    expect(isRotationDue({ lastRotatedAt: daysAgo(10_000), now, intervalDays: Number.NaN })).toBe(
      false,
    );
  });

  it('never fires on a clock that runs backwards or on an invalid date', () => {
    expect(
      isRotationDue({ lastRotatedAt: new Date(now.getTime() + 60_000), now, intervalDays: 1 }),
    ).toBe(false);
    expect(isRotationDue({ lastRotatedAt: new Date(Number.NaN), now, intervalDays: 1 })).toBe(
      false,
    );
  });
});

describe('keyAgeDays', () => {
  it('counts whole days and never goes negative', () => {
    expect(keyAgeDays(daysAgo(0), now)).toBe(0);
    expect(keyAgeDays(daysAgo(1, -1), now)).toBe(0);
    expect(keyAgeDays(daysAgo(1), now)).toBe(1);
    expect(keyAgeDays(daysAgo(45, 5000), now)).toBe(45);
    expect(keyAgeDays(new Date(now.getTime() + 86_400_000), now)).toBe(0);
  });
});
