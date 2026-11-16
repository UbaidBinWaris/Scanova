'use client';

import type { ReactNode } from 'react';
import './globals.css';

/** Last resort when the root layout itself fails. */
export default function GlobalError({ reset }: { error: Error; reset: () => void }): ReactNode {
  return (
    <html lang="en">
      <body>
        <main className="flex min-h-dvh flex-col items-center justify-center gap-4 px-4 text-center">
          <h1 className="text-2xl font-semibold tracking-tight">Something went wrong</h1>
          <p className="text-sm text-muted-foreground">The page could not be shown. Try again.</p>
          <button
            type="button"
            onClick={reset}
            className="min-h-11 rounded bg-primary px-4 text-sm font-semibold text-primary-foreground sm:min-h-9"
          >
            Try again
          </button>
        </main>
      </body>
    </html>
  );
}
