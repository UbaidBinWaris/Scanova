import { notFound, redirect } from 'next/navigation';
import type { ReactNode } from 'react';
import { Alert } from '@/components/ui/feedback';
import { PATHS, safeReturnPath, userTypeFits, type Surface } from '@/lib/surface';
import { requestInfo } from '@/server/request';
import { readSession } from '@/server/session/server';
import { AuthFrame } from './auth-frame';
import { SignInForm } from './sign-in-form';

/** The one sign-in form, rendered on the member host and on the admin host. */
export async function SignInScreen({
  surface,
  searchParams,
}: {
  surface: Surface;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}): Promise<ReactNode> {
  const info = await requestInfo();
  if (info.surface !== surface) {
    notFound();
  }
  const session = await readSession(info);
  if (session !== null && userTypeFits(session.userType, surface)) {
    redirect(session.mustChangePassword ? PATHS[surface].password : PATHS[surface].home);
  }
  const params = await searchParams;
  const next = safeReturnPath(params.next, surface);
  const expired = params.expired === '1';

  return (
    <AuthFrame
      surface={surface}
      title={surface === 'admin' ? 'Sign in to the admin console' : 'Sign in to your documents'}
      description={
        surface === 'admin'
          ? 'For administrators only. Every sign-in is recorded in the audit log.'
          : 'Use the account you use in the Scanova app.'
      }
    >
      {expired ? <Alert tone="info">Your session ended. Sign in again to continue.</Alert> : null}
      <SignInForm next={next} />
      {surface === 'site' ? (
        <p className="text-xs text-muted-foreground">
          No account yet? Create one in the Scanova app, or{' '}
          <a className="font-medium text-primary underline-offset-4 hover:underline" href="/">
            download the app
          </a>
          .
        </p>
      ) : null}
    </AuthFrame>
  );
}
