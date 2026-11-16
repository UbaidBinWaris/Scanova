'use client';

import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import type { ReactNode } from 'react';
import type { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Alert } from '@/components/ui/feedback';
import { Field, fieldAria, Input } from '@/components/ui/field';
import { SignInSchema } from '@/lib/validation';
import { signIn } from '@/server/actions/auth';

type FormInput = z.input<typeof SignInSchema>;
type FormOutput = z.output<typeof SignInSchema>;

export function SignInForm({ next }: { next: string | null }): ReactNode {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormInput, unknown, FormOutput>({
    resolver: zodResolver(SignInSchema),
    defaultValues: { email: '', password: '', ...(next === null ? {} : { next }) },
  });

  const onSubmit = handleSubmit(async (values) => {
    // On success the action redirects; it only returns on failure.
    const result = await signIn(values);
    if (result !== undefined && !result.ok) {
      if (result.fieldErrors?.email !== undefined) {
        setError('email', { message: result.fieldErrors.email });
      }
      setError('root', { message: result.message });
    }
  });

  return (
    <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
      {errors.root?.message !== undefined ? <Alert>{errors.root.message}</Alert> : null}
      <Field id="email" label="E-mail" error={errors.email?.message}>
        <Input
          type="email"
          autoComplete="username"
          inputMode="email"
          autoCapitalize="none"
          spellCheck={false}
          {...fieldAria('email', errors.email?.message)}
          {...register('email')}
        />
      </Field>
      <Field id="password" label="Password" error={errors.password?.message}>
        <Input
          type="password"
          autoComplete="current-password"
          {...fieldAria('password', errors.password?.message)}
          {...register('password')}
        />
      </Field>
      <Button type="submit" pending={isSubmitting} className="mt-2 w-full">
        Sign in
      </Button>
    </form>
  );
}
