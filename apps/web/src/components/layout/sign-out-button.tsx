import type { ReactNode } from 'react';
import { buttonClasses, type ButtonVariant } from '@/components/ui/button';
import { LogOutIcon } from '@/components/ui/icons';
import { signOut } from '@/server/actions/auth';

/** A form, so signing out works even before the page's JavaScript has loaded. */
export function SignOutButton({ variant = 'ghost' }: { variant?: ButtonVariant }): ReactNode {
  return (
    <form action={signOut}>
      <button type="submit" className={buttonClasses(variant, 'md', 'w-full sm:w-auto')}>
        <LogOutIcon width={18} height={18} />
        Sign out
      </button>
    </form>
  );
}
