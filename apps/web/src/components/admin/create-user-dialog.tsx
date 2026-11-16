'use client';

import { zodResolver } from '@hookform/resolvers/zod';
import type { AdminCreateUserResponse } from '@scanova/contracts/admin';
import { useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useForm } from 'react-hook-form';
import type { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Alert } from '@/components/ui/feedback';
import { Field, fieldAria, Input, Select } from '@/components/ui/field';
import { CreateUserSchema } from '@/lib/validation';
import { createUser } from '@/server/actions/admin';
import { TemporaryPassword } from './temporary-password';

export function CreateUserDialog({ onClose }: { onClose: () => void }): ReactNode {
  const queryClient = useQueryClient();
  const [created, setCreated] = useState<AdminCreateUserResponse | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<z.input<typeof CreateUserSchema>, unknown, z.output<typeof CreateUserSchema>>({
    resolver: zodResolver(CreateUserSchema),
    defaultValues: { email: '', name: '', userType: 'MEMBER' },
  });

  const onSubmit = handleSubmit(async (values) => {
    const result = await createUser(values);
    if (!result.ok) {
      if (result.fieldErrors?.email !== undefined) {
        setError('email', { message: result.fieldErrors.email });
      } else {
        setError('root', { message: result.message });
      }
      return;
    }
    setCreated(result.data);
    await queryClient.invalidateQueries({ queryKey: ['admin-users'] });
  });

  if (created !== null) {
    return (
      <Dialog
        open
        onClose={onClose}
        title="User created"
        footer={<Button onClick={onClose}>Done</Button>}
      >
        <TemporaryPassword email={created.user.email} password={created.temporaryPassword} />
      </Dialog>
    );
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title="Create user"
      description="The account gets a temporary password, shown once, which must be changed at the first sign-in."
    >
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
        {errors.root?.message !== undefined ? <Alert>{errors.root.message}</Alert> : null}
        <Field id="create-email" label="E-mail" error={errors.email?.message}>
          <Input
            type="email"
            autoComplete="off"
            spellCheck={false}
            {...fieldAria('create-email', errors.email?.message)}
            {...register('email')}
          />
        </Field>
        <Field id="create-name" label="Name" error={errors.name?.message}>
          <Input
            dir="auto"
            autoComplete="off"
            {...fieldAria('create-name', errors.name?.message)}
            {...register('name')}
          />
        </Field>
        <Field
          id="create-type"
          label="Account type"
          hint="Members own documents and use the app. Administrators manage accounts and keys, on this console only."
        >
          <Select {...fieldAria('create-type', undefined, 'hint')} {...register('userType')}>
            <option value="MEMBER">Member</option>
            <option value="ADMIN">Administrator</option>
          </Select>
        </Field>
        <div className="flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" pending={isSubmitting}>
            Create user
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
