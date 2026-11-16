'use client';

import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter } from 'next/navigation';
import { useForm } from 'react-hook-form';
import type { ReactNode } from 'react';
import type { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Field, fieldAria, Input } from '@/components/ui/field';
import { useToast } from '@/components/ui/toast';
import { UpdateNameSchema } from '@/lib/validation';
import { updateName } from '@/server/actions/account';

export function ProfileForm({ name, email }: { name: string; email: string }): ReactNode {
  const router = useRouter();
  const { notify } = useToast();
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isSubmitting, isDirty },
  } = useForm<z.input<typeof UpdateNameSchema>, unknown, z.output<typeof UpdateNameSchema>>({
    resolver: zodResolver(UpdateNameSchema),
    defaultValues: { name },
  });
  const onSubmit = handleSubmit(async (values) => {
    const result = await updateName(values);
    if (!result.ok) {
      setError('name', { message: result.fieldErrors?.name ?? result.message });
      return;
    }
    reset({ name: result.data.name });
    notify('Name saved');
    router.refresh();
  });
  return (
    <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-4">
      <Field id="name" label="Name" error={errors.name?.message}>
        <Input
          dir="auto"
          autoComplete="name"
          maxLength={100}
          {...fieldAria('name', errors.name?.message)}
          {...register('name')}
        />
      </Field>
      <div className="flex flex-col gap-1.5">
        <span className="text-sm font-medium">E-mail</span>
        <span className="break-all text-sm text-muted-foreground">{email}</span>
      </div>
      <div>
        <Button type="submit" pending={isSubmitting} disabled={!isDirty}>
          Save name
        </Button>
      </div>
    </form>
  );
}
