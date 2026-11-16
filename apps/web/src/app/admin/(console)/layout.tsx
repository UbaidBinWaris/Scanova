import type { ReactNode } from 'react';
import { Shell } from '@/components/layout/shell';
import { AppProviders } from '@/components/providers/query-provider';
import { getApi } from '@/server/api/instance';
import { unwrap } from '@/server/guard';
import { requireSession } from '@/server/session/server';

export default async function ConsoleLayout({
  children,
}: {
  children: ReactNode;
}): Promise<ReactNode> {
  const { context } = await requireSession('admin');
  const me = unwrap(await getApi().me.get(context), 'admin');
  return (
    <AppProviders>
      <Shell surface="admin" user={{ name: me.name, email: me.email }}>
        {children}
      </Shell>
    </AppProviders>
  );
}
