import type { ReactNode } from 'react';
import { BrandMark } from '@/components/ui/icons';
import type { Surface } from '@/lib/surface';

/** Centred card for sign-in and the password change, on both hosts. */
export function AuthFrame({
  surface,
  title,
  description,
  children,
}: {
  surface: Surface;
  title: string;
  description: ReactNode;
  children: ReactNode;
}): ReactNode {
  return (
    <main className="flex min-h-dvh items-center justify-center px-4 py-16">
      <div className="flex w-full max-w-sm flex-col gap-8">
        <div className="flex items-center gap-2">
          <BrandMark />
          <span className="text-base font-semibold">Scanova</span>
          {surface === 'admin' ? (
            <span className="rounded-full bg-tertiary-container px-2 py-0.5 text-xs font-medium text-tertiary-on-container">
              Admin console
            </span>
          ) : null}
        </div>
        <div className="flex flex-col gap-2">
          <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
          <div className="text-sm text-muted-foreground">{description}</div>
        </div>
        {children}
      </div>
    </main>
  );
}
