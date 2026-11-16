import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { SignInScreen } from '@/components/auth/sign-in-screen';

export const metadata: Metadata = { title: 'Sign in' };

export default function SignInPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}): Promise<ReactNode> {
  return SignInScreen({ surface: 'site', searchParams });
}
