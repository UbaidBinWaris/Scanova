'use client';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { ClientApiError } from '@/lib/client-api';
import { ToastProvider } from '@/components/ui/toast';

/** Server state in the browser (React Query): lists, polling and their caches. */
export function AppProviders({ children }: { children: ReactNode }): ReactNode {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            refetchOnWindowFocus: false,
            // Client errors (4xx) will not fix themselves; network and 5xx get two retries.
            retry: (failures, error) =>
              failures < 2 &&
              !(error instanceof ClientApiError && error.status >= 400 && error.status < 500),
          },
        },
      }),
  );
  return (
    <QueryClientProvider client={client}>
      <ToastProvider>{children}</ToastProvider>
    </QueryClientProvider>
  );
}
