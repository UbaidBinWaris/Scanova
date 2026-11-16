import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { PasswordScreen } from '@/components/auth/password-screen';

export const metadata: Metadata = { title: 'Choose a password' };

export default function PasswordPage(): Promise<ReactNode> {
  return PasswordScreen({ surface: 'site' });
}
