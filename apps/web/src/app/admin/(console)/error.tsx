'use client';

import type { ReactNode } from 'react';
import { RouteError } from '@/components/layout/route-error';

export default function ConsoleError({ reset }: { error: Error; reset: () => void }): ReactNode {
  return <RouteError reset={reset} />;
}
