import type { ReactNode } from 'react';
import { Shell } from '@/components/layout/shell';
import { AppProviders } from '@/components/providers/query-provider';
import { getApi } from '@/server/api/instance';
import { unwrap } from '@/server/guard';
import { requireSession } from '@/server/session/server';

export default async function PortalLayout({
  children,
}: {
  children: ReactNode;
}): Promise<ReactNode> {
  const { context } = await requireSession('site');
  const me = unwrap(await getApi().me.get(context), 'site');
  return (
    <AppProviders>
      <Shell surface="site" user={{ name: me.name, email: me.email }}>
        {children}
      </Shell>
    </AppProviders>
  );
}
