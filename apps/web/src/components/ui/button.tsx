import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'destructive' | 'danger';
export type ButtonSize = 'md' | 'sm' | 'icon';

const VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-primary text-primary-foreground hover:bg-primary/90',
  secondary: 'border border-outline bg-transparent text-foreground hover:bg-surface-high',
  ghost: 'bg-transparent text-foreground hover:bg-surface-high',
  destructive: 'bg-destructive text-destructive-foreground hover:bg-destructive/90',
  /** A destructive action that opens a confirmation: quiet until hovered. */
  danger:
    'bg-transparent text-destructive hover:bg-destructive-container hover:text-destructive-on-container',
};

// 44 px touch targets on phones, the denser 36 px from `sm` up.
const SIZES: Record<ButtonSize, string> = {
  md: 'min-h-11 px-4 sm:min-h-9',
  sm: 'min-h-11 px-3 sm:min-h-8',
  icon: 'h-11 w-11 sm:h-9 sm:w-9',
};

/** Shared look for buttons and button-styled links. */
export function buttonClasses(
  variant: ButtonVariant = 'primary',
  size: ButtonSize = 'md',
  className?: string,
): string {
  return cn(
    'inline-flex shrink-0 select-none items-center justify-center gap-2 rounded text-sm font-semibold transition-colors',
    'focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
    'disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50',
    VARIANTS[variant],
    SIZES[size],
    className,
  );
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  readonly variant?: ButtonVariant;
  readonly size?: ButtonSize;
  /** Shows a busy state and blocks repeated clicks. */
  readonly pending?: boolean;
}

export function Button({
  variant = 'primary',
  size = 'md',
  pending = false,
  className,
  children,
  disabled,
  type = 'button',
  ...props
}: ButtonProps): ReactNode {
  return (
    <button
      type={type}
      className={buttonClasses(variant, size, className)}
      disabled={disabled === true || pending}
      aria-busy={pending || undefined}
      {...props}
    >
      {pending ? <Spinner /> : null}
      {children}
    </button>
  );
}

function Spinner(): ReactNode {
  return (
    <svg viewBox="0 0 24 24" width={16} height={16} className="animate-spin" aria-hidden="true">
      <circle
        cx="12"
        cy="12"
        r="9"
        fill="none"
        stroke="currentColor"
        strokeOpacity="0.3"
        strokeWidth="3"
      />
      <path
        d="M21 12a9 9 0 0 0-9-9"
        fill="none"
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
      />
    </svg>
  );
}
