import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { KeysPanel } from '@/components/admin/keys-panel';

export const metadata: Metadata = { title: 'Keys' };

export default function KeysPage(): ReactNode {
  return <KeysPanel />;
}
