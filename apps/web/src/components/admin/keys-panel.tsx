'use client';

import {
  KeyStatusSchema,
  RotationRunListSchema,
  type CreateRotationRequest,
  type MasterKeyPurpose,
  type MasterKeyStatus,
  type RotationRun,
} from '@scanova/contracts/admin';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, useTransition, type ReactNode } from 'react';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Section } from '@/components/ui/card';
import { Dialog } from '@/components/ui/dialog';
import { Alert, PageHeader, ProgressBar, Skeleton } from '@/components/ui/feedback';
import { RefreshIcon } from '@/components/ui/icons';
import { LocalTime } from '@/components/ui/local-time';
import { useToast } from '@/components/ui/toast';
import { fetchEnvelope } from '@/lib/client-api';
import { startRotation } from '@/server/actions/admin';

const PURPOSE: Record<
  MasterKeyPurpose,
  { label: string; rotation: CreateRotationRequest | null; explains: string }
> = {
  USER_TYPE_ADMIN: {
    label: 'Administrators’ master key',
    rotation: { scope: 'USER_TYPE_MASTER', userType: 'ADMIN' },
    explains: 'Wraps every administrator’s user key.',
  },
  USER_TYPE_MEMBER: {
    label: 'Members’ master key',
    rotation: { scope: 'USER_TYPE_MASTER', userType: 'MEMBER' },
    explains: 'Wraps every member’s user key.',
  },
  SYSTEM: {
    label: 'System key',
    rotation: null,
    explains:
      'Wraps the system data keys (audit log, e-mail lookup index). Not rotated from the console.',
  },
  JWT_SIGNING: {
    label: 'Token-signing key',
    rotation: { scope: 'JWT_SIGNING' },
    explains: 'Signs access tokens. Old versions verify until the longest token has expired.',
  },
};

const STATE_TONE: Record<RotationRun['state'], BadgeTone> = {
  PENDING: 'caution',
  RUNNING: 'primary',
  COMPLETED: 'positive',
  FAILED: 'destructive',
};

const SCOPE_LABEL: Record<RotationRun['scope'], string> = {
  USER_TYPE_MASTER: 'Master key',
  USER_KEY: 'User key',
  JWT_SIGNING: 'Token-signing key',
};

const TRIGGER_LABEL: Record<RotationRun['trigger'], string> = {
  SCHEDULED: 'Scheduled',
  MANUAL: 'Manual',
  PASSWORD_RESET: 'Password reset',
  USER_TYPE_CHANGE: 'Type change',
};

/** Key versions, ages and pending work, and the rotation history (refreshes while one runs). */
export function KeysPanel(): ReactNode {
  const queryClient = useQueryClient();
  const { notify } = useToast();
  const [confirming, setConfirming] = useState<MasterKeyStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  const rotations = useQuery({
    queryKey: ['admin-rotations'],
    queryFn: ({ signal }) =>
      fetchEnvelope('/admin/api/rotations?limit=50', RotationRunListSchema, 'admin', signal),
    refetchInterval: (query) =>
      query.state.data?.data.some((run) => run.state === 'PENDING' || run.state === 'RUNNING') ===
      true
        ? 2000
        : false,
  });
  const running =
    rotations.data?.data.some((run) => run.state === 'PENDING' || run.state === 'RUNNING') === true;
  const keys = useQuery({
    queryKey: ['admin-keys'],
    queryFn: ({ signal }) => fetchEnvelope('/admin/api/keys', KeyStatusSchema, 'admin', signal),
    refetchInterval: running ? 4000 : false,
  });

  const rotate = (key: MasterKeyStatus): void => {
    const request = PURPOSE[key.purpose].rotation;
    if (request === null) {
      return;
    }
    setError(null);
    startTransition(async () => {
      const result = await startRotation(request);
      if (!result.ok) {
        setError(result.message);
        return;
      }
      setConfirming(null);
      notify('Rotation started');
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['admin-rotations'] }),
        queryClient.invalidateQueries({ queryKey: ['admin-keys'] }),
      ]);
    });
  };

  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title="Keys"
        description="Key versions and rotations. Key material never leaves the vault and is never shown here."
        actions={
          <Button
            variant="secondary"
            pending={keys.isFetching || rotations.isFetching}
            onClick={() => {
              void keys.refetch();
              void rotations.refetch();
            }}
          >
            <RefreshIcon width={18} height={18} />
            Refresh
          </Button>
        }
      />

      <Section
        title="Master keys"
        description="In OpenBao's Transit engine. Rotating adds a version and re-wraps everything under it."
      >
        {keys.isPending ? (
          <Skeleton className="h-40 w-full" />
        ) : keys.isError ? (
          <Alert title="The key status could not be loaded">{keys.error.message}</Alert>
        ) : (
          <ul className="-my-4 divide-y divide-border">
            {keys.data.data.masterKeys.map((key) => (
              <li
                key={key.name}
                className="flex flex-col gap-2 py-4 md:flex-row md:items-center md:justify-between"
              >
                <div className="flex min-w-0 flex-col gap-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{PURPOSE[key.purpose].label}</span>
                    {key.rotationDue ? <Badge tone="caution">Rotation due</Badge> : null}
                    {key.pendingRewrap > 0 ? (
                      <Badge tone="primary">
                        {key.pendingRewrap.toLocaleString('en-US')} pending re-wrap
                      </Badge>
                    ) : null}
                  </div>
                  <span className="text-xs text-muted-foreground">
                    <code className="font-mono">{key.name}</code> · version {key.latestVersion}{' '}
                    (decrypts from {key.minDecryptionVersion}) · {key.ageDays} days old · since{' '}
                    <LocalTime iso={key.latestVersionCreatedAt} mode="date" />
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {PURPOSE[key.purpose].explains}
                  </span>
                </div>
                {PURPOSE[key.purpose].rotation !== null ? (
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => {
                      setError(null);
                      setConfirming(key);
                    }}
                  >
                    Rotate now
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </Section>

      {keys.data !== undefined ? (
        <Section
          title="User and data keys"
          description="In the separate key database. User keys are rotated per user (Users → Manage)."
        >
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
            {(
              [
                ['Active user keys', keys.data.data.userKeys.active],
                ['Retired', keys.data.data.userKeys.retired],
                ['Destroyed', keys.data.data.userKeys.destroyed],
                ['Rotation due', keys.data.data.userKeys.rotationDue],
                ['Data keys to re-wrap', keys.data.data.userKeys.pendingRewrap],
                ['Data keys', keys.data.data.dataKeys],
              ] as const
            ).map(([label, value]) => (
              <div key={label} className="flex flex-col gap-1">
                <dt className="text-xs text-muted-foreground">{label}</dt>
                <dd className="text-xl font-semibold tabular-nums">
                  {value.toLocaleString('en-US')}
                </dd>
              </div>
            ))}
          </dl>
        </Section>
      ) : null}

      <Section
        title="Rotations"
        description="The 50 most recent runs. While one is running, this list updates on its own."
      >
        {rotations.isPending ? (
          <Skeleton className="h-40 w-full" />
        ) : rotations.isError ? (
          <Alert title="The rotations could not be loaded">{rotations.error.message}</Alert>
        ) : rotations.data.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">No rotation has run yet.</p>
        ) : (
          <div className="-mx-6 overflow-x-auto">
            <table className="w-full min-w-[44rem] text-left text-sm">
              <caption className="sr-only">Key rotations, newest first</caption>
              <thead className="border-b border-border text-xs text-muted-foreground">
                <tr>
                  <th scope="col" className="px-6 py-2 font-medium">
                    What
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium">
                    State
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium">
                    Progress
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium">
                    Versions
                  </th>
                  <th scope="col" className="px-6 py-2 font-medium">
                    Started
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {rotations.data.data.map((run) => {
                  const percent =
                    run.totalItems === 0
                      ? run.state === 'COMPLETED'
                        ? 100
                        : 0
                      : (run.processedItems / run.totalItems) * 100;
                  return (
                    <tr key={run.id}>
                      <td className="px-6 py-3">
                        <div className="flex flex-col">
                          <span className="font-medium">{SCOPE_LABEL[run.scope]}</span>
                          <span
                            className="max-w-56 truncate font-mono text-xs text-muted-foreground"
                            title={run.target}
                          >
                            {run.target}
                          </span>
                          <span className="text-xs text-muted-foreground">
                            {TRIGGER_LABEL[run.trigger]}
                          </span>
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex flex-col items-start gap-1">
                          <Badge tone={STATE_TONE[run.state]}>{run.state.toLowerCase()}</Badge>
                          {run.errorCode !== null ? (
                            <code className="font-mono text-xs text-destructive">
                              {run.errorCode}
                            </code>
                          ) : null}
                        </div>
                      </td>
                      <td className="w-48 px-4 py-3">
                        <div className="flex flex-col gap-1">
                          <ProgressBar
                            value={percent}
                            label={`${run.processedItems} of ${run.totalItems} done`}
                            tone={run.state === 'FAILED' ? 'destructive' : 'primary'}
                          />
                          <span className="text-xs tabular-nums text-muted-foreground">
                            {run.processedItems.toLocaleString('en-US')} /{' '}
                            {run.totalItems.toLocaleString('en-US')}
                          </span>
                        </div>
                      </td>
                      <td className="whitespace-nowrap px-4 py-3 tabular-nums">
                        {run.fromVersion ?? '—'} → {run.toVersion ?? '—'}
                      </td>
                      <td className="whitespace-nowrap px-6 py-3">
                        <LocalTime iso={run.startedAt ?? run.createdAt} />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Section>

      {confirming !== null ? (
        <Dialog
          open
          onClose={() => setConfirming(null)}
          title={`Rotate the ${PURPOSE[confirming.purpose].label.toLowerCase()}?`}
          description="A new version is created in the vault and becomes active at once. The worker re-wraps existing keys in the background; documents are not re-encrypted and stay readable throughout."
          footer={
            <>
              <Button variant="ghost" onClick={() => setConfirming(null)}>
                Cancel
              </Button>
              <Button pending={pending} onClick={() => rotate(confirming)}>
                Start rotation
              </Button>
            </>
          }
        >
          {error !== null ? <Alert>{error}</Alert> : null}
        </Dialog>
      ) : null}
    </div>
  );
}
