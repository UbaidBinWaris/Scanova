import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { AuditBrowser } from '@/components/admin/audit-browser';

export const metadata: Metadata = { title: 'Audit log' };

export default function AuditPage(): ReactNode {
  return <AuditBrowser />;
}
