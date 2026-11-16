import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { AlertIcon } from './icons';

export function Alert({
  tone = 'error',
  title,
  children,
  className,
}: {
  tone?: 'error' | 'info';
  title?: string;
  children?: ReactNode;
  className?: string;
}): ReactNode {
  return (
    <div
      role={tone === 'error' ? 'alert' : 'status'}
      className={cn(
        'flex items-start gap-2 rounded border p-4 text-sm',
        tone === 'error'
          ? 'border-destructive/40 bg-destructive-container text-destructive-on-container'
          : 'border-border bg-surface-low text-foreground',
        className,
      )}
    >
      <AlertIcon className="mt-0.5 shrink-0" width={16} height={16} />
      <div className="flex flex-col gap-1">
        {title !== undefined ? <p className="font-semibold">{title}</p> : null}
        {children}
      </div>
    </div>
  );
}

export function Skeleton({ className }: { className?: string }): ReactNode {
  return (
    <div aria-hidden="true" className={cn('animate-pulse rounded bg-surface-high', className)} />
  );
}

export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon: ReactNode;
  title: string;
  description: string;
  action: ReactNode;
}): ReactNode {
  return (
    <div className="flex flex-col items-center gap-4 rounded-lg border border-dashed border-border px-6 py-16 text-center">
      <div className="flex h-12 w-12 items-center justify-center rounded-full bg-primary-container text-primary-on-container">
        {icon}
      </div>
      <div className="flex max-w-sm flex-col gap-1">
        <h2 className="text-base font-medium">{title}</h2>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>
      {action}
    </div>
  );
}

/**
 * A bar for a share of a whole, drawn with SVG attributes rather than an
 * inline style, so it works under the strict CSP (no 'unsafe-inline' styles).
 */
export function ProgressBar({
  value,
  label,
  tone = 'primary',
}: {
  value: number;
  label: string;
  tone?: 'primary' | 'caution' | 'destructive';
}): ReactNode {
  const clamped = Math.min(100, Math.max(0, value));
  const fill =
    tone === 'primary' ? 'fill-primary' : tone === 'caution' ? 'fill-caution' : 'fill-destructive';
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(clamped)}
      className="h-2 w-full overflow-hidden rounded-full"
    >
      <svg
        viewBox="0 0 100 8"
        preserveAspectRatio="none"
        className="block h-full w-full"
        aria-hidden="true"
      >
        <rect x="0" y="0" width="100" height="8" className="fill-surface-highest" />
        <rect x="0" y="0" width={clamped} height="8" className={fill} />
      </svg>
    </div>
  );
}

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}): ReactNode {
  return (
    <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div className="flex min-w-0 flex-col gap-1">
        <h1 className="break-words text-2xl font-semibold tracking-tight">{title}</h1>
        {description !== undefined ? (
          <div className="text-sm text-muted-foreground">{description}</div>
        ) : null}
      </div>
      {actions !== undefined ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
    </div>
  );
}
