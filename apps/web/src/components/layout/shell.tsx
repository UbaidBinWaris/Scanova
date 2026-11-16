import Link from 'next/link';
import type { ReactNode } from 'react';
import { BrandMark } from '@/components/ui/icons';
import { PATHS, type Surface } from '@/lib/surface';
import { NavLinks, type NavItem } from './nav-links';
import { SignOutButton } from './sign-out-button';

const NAV: Readonly<Record<Surface, readonly NavItem[]>> = {
  site: [
    { href: '/app', label: 'Documents', match: ['/app/documents'] },
    { href: '/app/account', label: 'Account' },
  ],
  admin: [
    { href: '/admin/users', label: 'Users' },
    { href: '/admin/keys', label: 'Keys' },
    { href: '/admin/audit', label: 'Audit log' },
    { href: '/admin/account', label: 'Account' },
  ],
};

/** Header with brand, navigation and the signed-in person; content below. */
export function Shell({
  surface,
  user,
  children,
}: {
  surface: Surface;
  user: { name: string; email: string };
  children: ReactNode;
}): ReactNode {
  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main"
        className="sr-only z-50 rounded bg-card px-4 py-2 focus:not-sr-only focus:fixed focus:left-4 focus:top-4"
      >
        Skip to content
      </a>
      <header className="sticky top-0 z-40 border-b border-border bg-background/95 backdrop-blur">
        <div className="mx-auto flex max-w-6xl flex-col gap-2 px-4 py-2 md:flex-row md:items-center md:gap-6">
          <div className="flex items-center justify-between gap-4">
            <Link
              href={PATHS[surface].home}
              className="flex min-h-11 items-center gap-2 rounded font-semibold sm:min-h-9"
            >
              <BrandMark />
              <span>Scanova</span>
              {surface === 'admin' ? (
                <span className="rounded-full bg-tertiary-container px-2 py-0.5 text-xs font-medium text-tertiary-on-container">
                  Admin
                </span>
              ) : null}
            </Link>
            <div className="md:hidden">
              <SignOutButton />
            </div>
          </div>
          <NavLinks items={NAV[surface]} label={surface === 'admin' ? 'Admin console' : 'Main'} />
          <div className="ml-auto hidden items-center gap-4 md:flex">
            <div className="flex min-w-0 flex-col items-end leading-tight">
              <span className="max-w-48 truncate text-sm font-medium">{user.name}</span>
              <span className="max-w-48 truncate text-xs text-muted-foreground">{user.email}</span>
            </div>
            <SignOutButton />
          </div>
        </div>
      </header>
      <main id="main" className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-8 px-4 py-8">
        {children}
      </main>
    </div>
  );
}
