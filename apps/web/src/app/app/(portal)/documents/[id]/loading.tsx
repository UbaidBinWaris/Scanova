import type { ReactNode } from 'react';
import { Skeleton } from '@/components/ui/feedback';

export default function Loading(): ReactNode {
  return (
    <div className="flex flex-col gap-6" aria-busy="true" aria-label="Loading the document">
      <Skeleton className="h-5 w-32" />
      <Skeleton className="h-8 w-2/3" />
      <Skeleton className="h-4 w-1/2" />
      <div className="flex flex-col gap-4 md:flex-row">
        <Skeleton className="hidden h-[32rem] w-36 md:block" />
        <Skeleton className="h-[32rem] flex-1" />
      </div>
    </div>
  );
}
