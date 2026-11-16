'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface NavItem {
  readonly href: string;
  readonly label: string;
  /** Paths that also count as this item (e.g. a document under Documents). */
  readonly match?: readonly string[];
}

export function NavLinks({
  items,
  label,
}: {
  items: readonly NavItem[];
  label: string;
}): ReactNode {
  const pathname = usePathname();
  const isActive = (item: NavItem): boolean =>
    pathname === item.href || (item.match ?? []).some((prefix) => pathname.startsWith(prefix));
  return (
    <nav aria-label={label} className="-mx-1 flex gap-1 overflow-x-auto">
      {items.map((item) => {
        const active = isActive(item);
        return (
          <Link
            key={item.href}
            href={item.href}
            aria-current={active ? 'page' : undefined}
            className={cn(
              'inline-flex min-h-11 items-center whitespace-nowrap rounded px-3 text-sm font-medium transition-colors sm:min-h-9',
              active
                ? 'bg-primary-container text-primary-on-container'
                : 'text-muted-foreground hover:bg-surface-high hover:text-foreground',
            )}
          >
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}
