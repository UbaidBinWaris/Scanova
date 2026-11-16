'use client';

import { zodResolver } from '@hookform/resolvers/zod';
import type { User } from '@scanova/contracts/auth';
import { useQueryClient } from '@tanstack/react-query';
import { useState, useTransition, type ReactNode } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import type { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Alert } from '@/components/ui/feedback';
import { Field, fieldAria, Input, Select } from '@/components/ui/field';
import { useToast } from '@/components/ui/toast';
import { formatBytes } from '@/lib/format';
import {
  EditUserFormSchema,
  quotaBytesFromForm,
  quotaGbForForm,
  type UpdateUserValues,
} from '@/lib/validation';
import {
  deleteUser,
  resetUserPassword,
  revokeUserSessions,
  startRotation,
  updateUser,
} from '@/server/actions/admin';
import { TemporaryPassword } from './temporary-password';

type View =
  | { readonly kind: 'edit' }
  | { readonly kind: 'confirm'; readonly action: 'reset' | 'revoke' | 'rotate' | 'delete' }
  | { readonly kind: 'password'; readonly password: string };

type FormInput = z.input<typeof EditUserFormSchema>;
type FormOutput = z.output<typeof EditUserFormSchema>;

/** Edit an account, and the account's security actions, in one dialog. */
export function ManageUserDialog({
  user,
  isSelf,
  onClose,
}: {
  user: User;
  /** Admins cannot disable, demote or delete themselves (the API refuses it too). */
  isSelf: boolean;
  onClose: () => void;
}): ReactNode {
  const queryClient = useQueryClient();
  const { notify } = useToast();
  const [view, setView] = useState<View>({ kind: 'edit' });
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const [confirmEmail, setConfirmEmail] = useState('');

  const refreshList = (): Promise<void> =>
    queryClient.invalidateQueries({ queryKey: ['admin-users'] });

  const form = useForm<FormInput, unknown, FormOutput>({
    resolver: zodResolver(EditUserFormSchema),
    defaultValues: {
      name: user.name,
      userType: user.userType,
      status: user.status,
      unlimited: user.storageQuotaBytes === null,
      quotaGb: quotaGbForForm(user.storageQuotaBytes),
    },
  });
  const { register, handleSubmit, control, formState } = form;
  const unlimited = useWatch({ control, name: 'unlimited' });
  const changingType = useWatch({ control, name: 'userType' }) !== user.userType;

  const onSave = handleSubmit(async (values) => {
    setError(null);
    const quota = quotaBytesFromForm(values);
    const changes: UpdateUserValues = { id: user.id };
    if (values.name !== user.name) {
      changes.name = values.name;
    }
    if (values.userType !== user.userType) {
      changes.userType = values.userType;
    }
    if (values.status !== user.status) {
      changes.status = values.status;
    }
    if (quota !== user.storageQuotaBytes) {
      changes.storageQuotaBytes = quota;
    }
    if (Object.keys(changes).length === 1) {
      onClose();
      return;
    }
    const result = await updateUser(changes);
    if (!result.ok) {
      setError(result.message);
      return;
    }
    await refreshList();
    notify('Changes saved');
    onClose();
  });

  const runConfirmed = (action: 'reset' | 'revoke' | 'rotate' | 'delete'): void => {
    setError(null);
    startTransition(async () => {
      switch (action) {
        case 'reset': {
          const result = await resetUserPassword({ id: user.id });
          if (!result.ok) {
            setError(result.message);
            return;
          }
          await refreshList();
          setView({ kind: 'password', password: result.data.temporaryPassword });
          return;
        }
        case 'revoke': {
          const result = await revokeUserSessions({ id: user.id });
          if (!result.ok) {
            setError(result.message);
            return;
          }
          notify(`${user.name} was signed out everywhere`);
          onClose();
          return;
        }
        case 'rotate': {
          const result = await startRotation({ scope: 'USER_KEY', userId: user.id });
          if (!result.ok) {
            setError(result.message);
            return;
          }
          await queryClient.invalidateQueries({ queryKey: ['admin-rotations'] });
          notify('Key rotation started; follow it under Keys');
          onClose();
          return;
        }
        case 'delete': {
          const result = await deleteUser({ id: user.id });
          if (!result.ok) {
            setError(result.message);
            return;
          }
          await refreshList();
          notify('Account deleted');
          onClose();
          return;
        }
      }
    });
  };

  if (view.kind === 'password') {
    return (
      <Dialog
        open
        onClose={onClose}
        title="Password reset"
        footer={<Button onClick={onClose}>Done</Button>}
      >
        <TemporaryPassword email={user.email} password={view.password} />
      </Dialog>
    );
  }

  if (view.kind === 'confirm') {
    const copy = CONFIRM[view.action];
    const deleting = view.action === 'delete';
    return (
      <Dialog
        open
        onClose={onClose}
        title={copy.title}
        description={copy.description(user)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setView({ kind: 'edit' })}>
              Back
            </Button>
            <Button
              variant={copy.destructive ? 'destructive' : 'primary'}
              pending={pending}
              disabled={deleting && confirmEmail.trim().toLowerCase() !== user.email.toLowerCase()}
              onClick={() => {
                runConfirmed(view.action);
              }}
            >
              {copy.confirm}
            </Button>
          </>
        }
      >
        {error !== null ? <Alert>{error}</Alert> : null}
        {deleting ? (
          <Field id="confirm-email" label={`Type ${user.email} to confirm`}>
            <Input
              id="confirm-email"
              type="email"
              autoComplete="off"
              spellCheck={false}
              value={confirmEmail}
              onChange={(event) => {
                setConfirmEmail(event.target.value);
              }}
            />
          </Field>
        ) : null}
      </Dialog>
    );
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title={user.name}
      description={
        <span className="break-all">
          {user.email} · {formatBytes(user.storageUsedBytes)} stored
        </span>
      }
    >
      <form onSubmit={(event) => void onSave(event)} noValidate className="flex flex-col gap-4">
        {error !== null ? <Alert>{error}</Alert> : null}
        <Field id="edit-name" label="Name" error={formState.errors.name?.message}>
          <Input
            dir="auto"
            {...fieldAria('edit-name', formState.errors.name?.message)}
            {...register('name')}
          />
        </Field>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field id="edit-type" label="Account type">
            <Select id="edit-type" disabled={isSelf} {...register('userType')}>
              <option value="MEMBER">Member</option>
              <option value="ADMIN">Administrator</option>
            </Select>
          </Field>
          <Field id="edit-status" label="Status">
            <Select id="edit-status" disabled={isSelf} {...register('status')}>
              <option value="ACTIVE">Active</option>
              <option value="DISABLED">Disabled (signed out, cannot sign in)</option>
            </Select>
          </Field>
        </div>
        {changingType ? (
          <Alert tone="info">
            Changing the type moves the user key to the other master key, rotates it and signs the
            user out everywhere.
          </Alert>
        ) : null}
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1.5 text-sm font-medium">Storage quota</legend>
          <label className="flex min-h-11 items-center gap-2 text-sm sm:min-h-9">
            <input type="checkbox" className="h-4 w-4 accent-primary" {...register('unlimited')} />
            No limit
          </label>
          {!unlimited ? (
            <Field
              id="edit-quota"
              label="Quota in GB"
              error={formState.errors.quotaGb?.message}
              hint="Lowering it below what is stored keeps the files; only new uploads are refused."
            >
              <Input
                inputMode="decimal"
                {...fieldAria('edit-quota', formState.errors.quotaGb?.message, 'hint')}
                {...register('quotaGb')}
              />
            </Field>
          ) : null}
        </fieldset>
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" pending={formState.isSubmitting}>
            Save changes
          </Button>
        </div>
      </form>

      <div className="flex flex-col gap-2 border-t border-border pt-4">
        <h3 className="text-base font-medium">Security</h3>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="secondary"
            size="sm"
            onClick={() => setView({ kind: 'confirm', action: 'reset' })}
          >
            Reset password
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => setView({ kind: 'confirm', action: 'revoke' })}
          >
            Sign out everywhere
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => setView({ kind: 'confirm', action: 'rotate' })}
          >
            Rotate user key
          </Button>
          <Button
            variant="danger"
            size="sm"
            disabled={isSelf}
            onClick={() => {
              setConfirmEmail('');
              setView({ kind: 'confirm', action: 'delete' });
            }}
          >
            Delete account
          </Button>
        </div>
        {isSelf ? (
          <p className="text-xs text-muted-foreground">
            You cannot disable, demote or delete your own account.
          </p>
        ) : null}
      </div>
    </Dialog>
  );
}

const CONFIRM: Record<
  'reset' | 'revoke' | 'rotate' | 'delete',
  { title: string; description: (user: User) => string; confirm: string; destructive: boolean }
> = {
  reset: {
    title: 'Reset the password?',
    description: (user) =>
      `${user.name} gets a temporary password and is signed out everywhere. Their user key is rotated.`,
    confirm: 'Reset password',
    destructive: false,
  },
  revoke: {
    title: 'Sign out everywhere?',
    description: (user) => `Every session of ${user.name}, on phones and in browsers, ends now.`,
    confirm: 'Sign out everywhere',
    destructive: false,
  },
  rotate: {
    title: 'Rotate the user key?',
    description: (user) =>
      `A new user key version wraps ${user.name}'s data keys; the old version is destroyed when the re-wrap is done. Documents are not re-encrypted and stay readable.`,
    confirm: 'Start rotation',
    destructive: false,
  },
  delete: {
    title: 'Delete this account?',
    description: (user) =>
      `${user.name}'s keys are destroyed immediately, which makes every document of theirs unreadable for good; the files are then purged. This cannot be undone.`,
    confirm: 'Delete account',
    destructive: true,
  },
};
