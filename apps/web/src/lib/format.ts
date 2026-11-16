const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const;

/** Decimal units, like the Android app and file managers: 1 GB = 10⁹ bytes. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes) || bytes < 0) {
    return '—';
  }
  let value = bytes;
  let unit = 0;
  while (value >= 1000 && unit < UNITS.length - 1) {
    value /= 1000;
    unit += 1;
  }
  const digits = unit === 0 || value >= 100 ? 0 : value >= 10 ? 1 : 2;
  return `${value.toFixed(digits).replace(/\.0+$|(\.\d*[1-9])0+$/u, '$1')} ${UNITS[unit]}`;
}

/** Share of a quota in whole percent, clamped to 0–100. Null quota means unlimited. */
export function usagePercent(used: number, quota: number | null): number | null {
  if (quota === null) {
    return null;
  }
  if (quota <= 0) {
    return 100;
  }
  return Math.min(100, Math.max(0, Math.round((used / quota) * 100)));
}

/** Stable, timezone-free rendering used on the server before the browser localises it. */
export function formatIsoDate(iso: string): string {
  return iso.slice(0, 10);
}

export function formatLocalDateTime(iso: string, locale?: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

export function formatLocalDate(iso: string, locale?: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(date);
}

export function pluralize(count: number, singular: string, plural = `${singular}s`): string {
  return `${count.toLocaleString('en-US')} ${count === 1 ? singular : plural}`;
}
