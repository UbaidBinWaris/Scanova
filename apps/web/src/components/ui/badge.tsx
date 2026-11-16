import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type BadgeTone = 'neutral' | 'primary' | 'positive' | 'caution' | 'destructive';

const TONES: Record<BadgeTone, string> = {
  neutral: 'bg-surface-high text-foreground',
  primary: 'bg-primary-container text-primary-on-container',
  positive: 'bg-secondary-container text-secondary-on-container',
  caution: 'bg-tertiary-container text-tertiary-on-container',
  destructive: 'bg-destructive-container text-destructive-on-container',
};

/** Status label. The text carries the meaning; colour only reinforces it. */
export function Badge({
  tone = 'neutral',
  children,
  className,
}: {
  tone?: BadgeTone;
  children: ReactNode;
  className?: string;
}): ReactNode {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium',
        TONES[tone],
        className,
      )}
    >
      {children}
    </span>
  );
}
