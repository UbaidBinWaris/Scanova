import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { BrandMark } from '@/components/ui/icons';

export const metadata: Metadata = { title: 'Not found' };

export default function NotFound(): ReactNode {
  return (
    <main className="flex min-h-dvh flex-col items-center justify-center gap-4 px-4 py-16 text-center">
      <BrandMark />
      <h1 className="text-2xl font-semibold tracking-tight">Page not found</h1>
      <p className="max-w-prose text-sm text-muted-foreground">
        This page does not exist, or it is not yours to see.
      </p>
    </main>
  );
}
