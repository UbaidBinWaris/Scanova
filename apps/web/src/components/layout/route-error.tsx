'use client';

import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Alert } from '@/components/ui/feedback';

/** Shown by error.tsx boundaries: the message never includes server details. */
export function RouteError({ reset }: { reset: () => void }): ReactNode {
  return (
    <div className="flex flex-col items-start gap-4">
      <Alert title="This page could not be loaded">
        The server did not answer as expected. Your data is safe; try again in a moment.
      </Alert>
      <Button variant="secondary" onClick={reset}>
        Try again
      </Button>
    </div>
  );
}
