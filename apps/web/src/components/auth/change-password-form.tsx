'use client';

import { zodResolver } from '@hookform/resolvers/zod';
import { MIN_PASSWORD_LENGTH } from '@scanova/contracts/auth';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import type { ReactNode } from 'react';
import type { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Alert } from '@/components/ui/feedback';
import { Field, fieldAria, Input } from '@/components/ui/field';
import { useToast } from '@/components/ui/toast';
import { ChangePasswordSchema } from '@/lib/validation';
import { changePassword } from '@/server/actions/auth';

type FormInput = z.input<typeof ChangePasswordSchema>;
type FormOutput = z.output<typeof ChangePasswordSchema>;

const HINT = `At least ${MIN_PASSWORD_LENGTH} characters. A few unrelated words make a strong one.`;

export function ChangePasswordForm({
  temporary,
  redirectTo,
}: {
  /** The forced change after an admin set a temporary password. */
  temporary: boolean;
  /** Where to go afterwards; null stays on the page. */
  redirectTo: string | null;
}): ReactNode {
  const router = useRouter();
  const { notify } = useToast();
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<FormInput, unknown, FormOutput>({
    resolver: zodResolver(ChangePasswordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  });

  const onSubmit = handleSubmit(async (values) => {
    const result = await changePassword(values);
    if (result === undefined) {
      return;
    }
    if (!result.ok) {
      for (const [field, message] of Object.entries(result.fieldErrors ?? {})) {
        if (field === 'currentPassword' || field === 'newPassword' || field === 'confirmPassword') {
          setError(field, { message });
        }
      }
      if (result.fieldErrors === undefined) {
        setError('root', { message: result.message });
      }
      return;
    }
    reset();
    notify('Password changed. Your other sessions were signed out.');
    if (redirectTo !== null) {
      router.replace(redirectTo);
    }
    router.refresh();
  });

  return (
    <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
      {errors.root?.message !== undefined ? <Alert>{errors.root.message}</Alert> : null}
      {/* Lets password managers pair the new password with the account. */}
      <input type="text" name="username" autoComplete="username" hidden readOnly />
      <Field
        id="currentPassword"
        label={temporary ? 'Temporary password' : 'Current password'}
        error={errors.currentPassword?.message}
      >
        <Input
          type="password"
          autoComplete="current-password"
          {...fieldAria('currentPassword', errors.currentPassword?.message)}
          {...register('currentPassword')}
        />
      </Field>
      <Field id="newPassword" label="New password" error={errors.newPassword?.message} hint={HINT}>
        <Input
          type="password"
          autoComplete="new-password"
          {...fieldAria('newPassword', errors.newPassword?.message, HINT)}
          {...register('newPassword')}
        />
      </Field>
      <Field
        id="confirmPassword"
        label="Repeat the new password"
        error={errors.confirmPassword?.message}
      >
        <Input
          type="password"
          autoComplete="new-password"
          {...fieldAria('confirmPassword', errors.confirmPassword?.message)}
          {...register('confirmPassword')}
        />
      </Field>
      <div>
        <Button type="submit" pending={isSubmitting} className={temporary ? 'mt-2 w-full' : 'mt-2'}>
          {temporary ? 'Set new password' : 'Change password'}
        </Button>
      </div>
    </form>
  );
}
