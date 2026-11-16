import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { DocumentsBrowser } from '@/components/documents/documents-browser';

export const metadata: Metadata = { title: 'Documents' };

export default function DocumentsPage(): ReactNode {
  return <DocumentsBrowser />;
}
