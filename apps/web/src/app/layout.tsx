import type { Metadata, Viewport } from 'next';
import { connection } from 'next/server';
import type { ReactNode } from 'react';
import './globals.css';

export const metadata: Metadata = {
  title: { template: '%s · Scanova', default: 'Scanova' },
  description: 'Your scanned documents, encrypted on your own server.',
  applicationName: 'Scanova',
  // Private pages: the public, indexable page is the static download site.
  robots: { index: false, follow: false },
  icons: { icon: { url: '/favicon.svg', type: 'image/svg+xml' } },
  referrer: 'no-referrer',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  colorScheme: 'dark light',
  themeColor: [
    { media: '(prefers-color-scheme: dark)', color: '#0C0D0E' },
    { media: '(prefers-color-scheme: light)', color: '#F7F8F8' },
  ],
};

export default async function RootLayout({
  children,
}: {
  children: ReactNode;
}): Promise<ReactNode> {
  // Every page is rendered per request: each response carries its own CSP nonce.
  await connection();
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
