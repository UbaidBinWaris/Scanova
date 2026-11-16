import type { ReactNode } from 'react';
import { Skeleton } from '@/components/ui/feedback';

export default function Loading(): ReactNode {
  return (
    <div className="flex flex-col gap-6" aria-busy="true" aria-label="Loading">
      <Skeleton className="h-8 w-48" />
      <Skeleton className="h-64 w-full" />
    </div>
  );
}
