import { notFound } from 'next/navigation';
import type { ReactNode } from 'react';
import { AppProviders } from '@/components/providers/query-provider';
import { PATHS, type Surface } from '@/lib/surface';
import { requestInfo } from '@/server/request';
import { requireSession } from '@/server/session/server';
import { AuthFrame } from './auth-frame';
import { ChangePasswordForm } from './change-password-form';
import { SignOutButton } from '../layout/sign-out-button';

/** The forced password change after an administrator set a temporary password. */
export async function PasswordScreen({ surface }: { surface: Surface }): Promise<ReactNode> {
  if ((await requestInfo()).surface !== surface) {
    notFound();
  }
  const { session } = await requireSession(surface, { allowPasswordChange: true });
  return (
    <AppProviders>
      <AuthFrame
        surface={surface}
        title={session.mustChangePassword ? 'Choose your own password' : 'Change your password'}
        description={
          session.mustChangePassword
            ? 'You signed in with a temporary password. Choose a new one to continue.'
            : 'Your other sessions are signed out when you change it.'
        }
      >
        <ChangePasswordForm
          temporary={session.mustChangePassword}
          redirectTo={PATHS[surface].home}
        />
        <SignOutButton variant="ghost" />
      </AuthFrame>
    </AppProviders>
  );
}
