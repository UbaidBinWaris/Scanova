'use client';

import { useSyncExternalStore, type ReactNode } from 'react';
import { formatIsoDate, formatLocalDate, formatLocalDateTime } from '@/lib/format';

const subscribe = (): (() => void) => () => undefined;

/** True after hydration. The server renders a timezone-free value; the browser localises it. */
export function useIsClient(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => true,
    () => false,
  );
}

export function LocalTime({
  iso,
  mode = 'datetime',
}: {
  iso: string;
  mode?: 'date' | 'datetime';
}): ReactNode {
  const client = useIsClient();
  const text = client
    ? mode === 'date'
      ? formatLocalDate(iso)
      : formatLocalDateTime(iso)
    : formatIsoDate(iso);
  return (
    <time dateTime={iso} title={iso}>
      {text}
    </time>
  );
}
