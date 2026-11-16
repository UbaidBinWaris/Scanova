import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { AccountScreen } from '@/components/account/account-screen';

export const metadata: Metadata = { title: 'Account' };

export default function AccountPage(): Promise<ReactNode> {
  return AccountScreen({ surface: 'site' });
}
