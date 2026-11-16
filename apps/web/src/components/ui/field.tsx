import type { InputHTMLAttributes, ReactNode, Ref, SelectHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

const CONTROL =
  'block w-full rounded border border-outline bg-card px-3 text-sm text-foreground placeholder:text-muted-foreground ' +
  'min-h-11 sm:min-h-9 focus-visible:border-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-0 ' +
  'disabled:opacity-60 aria-[invalid=true]:border-destructive';

export function Input({
  className,
  ref,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & { ref?: Ref<HTMLInputElement> }): ReactNode {
  return <input ref={ref} className={cn(CONTROL, className)} {...props} />;
}

export function Select({
  className,
  ref,
  children,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement> & { ref?: Ref<HTMLSelectElement> }): ReactNode {
  return (
    <select ref={ref} className={cn(CONTROL, 'pr-8', className)} {...props}>
      {children}
    </select>
  );
}

export interface FieldProps {
  readonly id: string;
  readonly label: string;
  readonly error?: string | undefined;
  readonly hint?: string | undefined;
  readonly children: ReactNode;
  readonly className?: string;
}

/** Label, control, hint and error, wired together for screen readers (aria-describedby). */
export function Field({ id, label, error, hint, children, className }: FieldProps): ReactNode {
  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {children}
      {hint !== undefined && error === undefined ? (
        <p id={`${id}-hint`} className="text-xs text-muted-foreground">
          {hint}
        </p>
      ) : null}
      {error !== undefined ? (
        <p id={`${id}-error`} className="text-xs font-medium text-destructive" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/** aria attributes for a control inside <Field>. */
export function fieldAria(
  id: string,
  error: string | undefined,
  hint?: string,
): { id: string; 'aria-invalid': boolean; 'aria-describedby'?: string } {
  const describedBy =
    error !== undefined ? `${id}-error` : hint !== undefined ? `${id}-hint` : undefined;
  return {
    id,
    'aria-invalid': error !== undefined,
    ...(describedBy === undefined ? {} : { 'aria-describedby': describedBy }),
  };
}
