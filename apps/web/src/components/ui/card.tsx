import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>): ReactNode {
  return (
    <div className={cn('rounded-lg border border-border bg-card p-6', className)} {...props} />
  );
}

export interface SectionProps {
  readonly title: string;
  readonly description?: string;
  readonly children: ReactNode;
  readonly actions?: ReactNode;
  readonly id?: string;
}

/** Settings-style section: heading and description, then its content in a card. */
export function Section({ title, description, children, actions, id }: SectionProps): ReactNode {
  const headingId = `${id ?? title.toLowerCase().replace(/[^a-z0-9]+/gu, '-')}-heading`;
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h2 id={headingId} className="text-xl font-semibold">
            {title}
          </h2>
          {description !== undefined ? (
            <p className="max-w-prose text-sm text-muted-foreground">{description}</p>
          ) : null}
        </div>
        {actions}
      </div>
      <Card>{children}</Card>
    </section>
  );
}
