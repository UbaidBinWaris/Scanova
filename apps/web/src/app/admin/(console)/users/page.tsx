import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { UsersBrowser } from '@/components/admin/users-browser';
import { requireSession } from '@/server/session/server';

export const metadata: Metadata = { title: 'Users' };

export default async function UsersPage(): Promise<ReactNode> {
  const { session } = await requireSession('admin');
  return <UsersBrowser currentUserId={session.userId} />;
}
