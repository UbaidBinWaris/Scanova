'use client';

import { AuditEventListSchema, type AuditEvent } from '@scanova/contracts/admin';
import { UuidSchema } from '@scanova/contracts/common';
import { useInfiniteQuery } from '@tanstack/react-query';
import { useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { Badge, type BadgeTone } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Alert, EmptyState, PageHeader, Skeleton } from '@/components/ui/feedback';
import { Field, fieldAria, Input, Select } from '@/components/ui/field';
import { ListIcon } from '@/components/ui/icons';
import { LocalTime } from '@/components/ui/local-time';
import { fetchEnvelope } from '@/lib/client-api';

/** The API's audit actions (apps/api/src/audit/audit.service.ts), with readable names. */
export const AUDIT_ACTIONS: Readonly<Record<string, { label: string; tone: BadgeTone }>> = {
  LOGIN_SUCCEEDED: { label: 'Signed in', tone: 'neutral' },
  SIGNUP: { label: 'Signed up', tone: 'neutral' },
  LOGIN_FAILED: { label: 'Sign-in failed', tone: 'caution' },
  LOGIN_REFUSED_ADMIN_MOBILE: { label: 'Admin refused in the app', tone: 'caution' },
  LOGIN_REFUSED_DISABLED: { label: 'Disabled account refused', tone: 'caution' },
  REFRESH_TOKEN_REUSED: { label: 'Refresh token reused', tone: 'destructive' },
  LOGOUT: { label: 'Signed out', tone: 'neutral' },
  PASSWORD_CHANGED: { label: 'Password changed', tone: 'primary' },
  SESSION_REVOKED: { label: 'Session ended', tone: 'neutral' },
  USER_CREATED: { label: 'User created', tone: 'primary' },
  USER_UPDATED: { label: 'User changed', tone: 'primary' },
  USER_PASSWORD_RESET: { label: 'Password reset', tone: 'caution' },
  USER_SESSIONS_REVOKED: { label: 'Signed out everywhere', tone: 'caution' },
  USER_DELETED: { label: 'User deleted', tone: 'destructive' },
  KEY_ROTATION_REQUESTED: { label: 'Key rotation requested', tone: 'primary' },
  OWNER_GRANTED: { label: 'Owner access granted', tone: 'caution' },
  OWNER_REVOKED: { label: 'Owner access removed', tone: 'caution' },
  AI_RUNTIME_ON: { label: 'Server AI turned on', tone: 'primary' },
  AI_RUNTIME_OFF: { label: 'Server AI turned off', tone: 'neutral' },
  AI_RUNTIME_AUTO_OFF: { label: 'Server AI turned off (app closed)', tone: 'neutral' },
};

interface Filter {
  readonly action: string;
  readonly actorId: string;
}

function auditUrl(filter: Filter, cursor: string | null): string {
  const params = new URLSearchParams({ limit: '100' });
  if (filter.action !== '') {
    params.set('action', filter.action);
  }
  if (filter.actorId !== '') {
    params.set('actorId', filter.actorId);
  }
  if (cursor !== null) {
    params.set('cursor', cursor);
  }
  return `/admin/api/audit-events?${params.toString()}`;
}

export function AuditBrowser(): ReactNode {
  const [filter, setFilter] = useState<Filter>({ action: '', actorId: '' });
  const [draftAction, setDraftAction] = useState('');
  const [draftActor, setDraftActor] = useState('');
  const [actorError, setActorError] = useState<string | undefined>(undefined);

  const query = useInfiniteQuery({
    queryKey: ['admin-audit', filter],
    queryFn: ({ pageParam, signal }) =>
      fetchEnvelope(auditUrl(filter, pageParam), AuditEventListSchema, 'admin', signal),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });
  const events = useMemo(
    () => (query.data?.pages ?? []).flatMap((page) => page.data),
    [query.data],
  );

  const apply = (event?: FormEvent): void => {
    event?.preventDefault();
    const actor = draftActor.trim();
    if (actor !== '' && !UuidSchema.safeParse(actor).success) {
      setActorError('Enter a user id (a UUID), or leave it empty.');
      return;
    }
    setActorError(undefined);
    setFilter({ action: draftAction, actorId: actor.toLowerCase() });
  };

  const filterByActor = (actorId: string): void => {
    setDraftActor(actorId);
    setActorError(undefined);
    setFilter((current) => ({ ...current, actorId }));
  };

  const filtered = filter.action !== '' || filter.actorId !== '';

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Audit log"
        description="Security events, newest first. Payloads hold ids and addresses, never content or e-mail addresses."
      />

      <form
        onSubmit={apply}
        className="flex flex-col gap-4 sm:flex-row sm:items-end"
        aria-label="Filter the audit log"
      >
        <Field id="audit-action" label="Event" className="sm:w-64">
          <Select
            id="audit-action"
            value={draftAction}
            onChange={(event) => {
              setDraftAction(event.target.value);
            }}
          >
            <option value="">All events</option>
            {Object.entries(AUDIT_ACTIONS).map(([action, { label }]) => (
              <option key={action} value={action}>
                {label}
              </option>
            ))}
          </Select>
        </Field>
        <Field id="audit-actor" label="Actor (user id)" error={actorError} className="flex-1">
          <Input
            placeholder="e.g. 6f1d7c1e-…"
            spellCheck={false}
            autoComplete="off"
            className="font-mono"
            value={draftActor}
            {...fieldAria('audit-actor', actorError)}
            onChange={(event) => {
              setDraftActor(event.target.value);
            }}
          />
        </Field>
        <div className="flex gap-2">
          <Button type="submit">Apply filter</Button>
          {filtered ? (
            <Button
              variant="ghost"
              onClick={() => {
                setDraftAction('');
                setDraftActor('');
                setActorError(undefined);
                setFilter({ action: '', actorId: '' });
              }}
            >
              Clear
            </Button>
          ) : null}
        </div>
      </form>

      {query.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : query.isError ? (
        <div className="flex flex-col items-start gap-4">
          <Alert title="The audit log could not be loaded">{query.error.message}</Alert>
          <Button variant="secondary" onClick={() => void query.refetch()}>
            Try again
          </Button>
        </div>
      ) : events.length === 0 ? (
        <EmptyState
          icon={<ListIcon />}
          title={filtered ? 'No matching events' : 'No events yet'}
          description={
            filtered
              ? 'Nothing was recorded for this filter.'
              : 'Sign-ins and administrative actions appear here.'
          }
          action={
            filtered ? (
              <Button variant="secondary" onClick={() => setFilter({ action: '', actorId: '' })}>
                Show all events
              </Button>
            ) : (
              <Button variant="secondary" onClick={() => void query.refetch()}>
                Refresh
              </Button>
            )
          }
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border border-border bg-card">
            <table className="w-full min-w-[48rem] text-left text-sm">
              <caption className="sr-only">Audit events, newest first</caption>
              <thead className="border-b border-border text-xs text-muted-foreground">
                <tr>
                  <th scope="col" className="px-4 py-3 font-medium">
                    Time
                  </th>
                  <th scope="col" className="px-4 py-3 font-medium">
                    Event
                  </th>
                  <th scope="col" className="px-4 py-3 font-medium">
                    Actor
                  </th>
                  <th scope="col" className="px-4 py-3 font-medium">
                    Details
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {events.map((event) => (
                  <AuditRow key={event.id} event={event} onActor={filterByActor} />
                ))}
              </tbody>
            </table>
          </div>
          {query.hasNextPage ? (
            <div className="flex justify-center">
              <Button
                variant="secondary"
                pending={query.isFetchingNextPage}
                onClick={() => void query.fetchNextPage()}
              >
                Load older events
              </Button>
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}

function AuditRow({
  event,
  onActor,
}: {
  event: AuditEvent;
  onActor: (actorId: string) => void;
}): ReactNode {
  const known = AUDIT_ACTIONS[event.action];
  const details = Object.entries(event.payload).filter(
    ([, value]) => value !== null && value !== '',
  );
  return (
    <tr className="align-top">
      <td className="whitespace-nowrap px-4 py-3">
        <LocalTime iso={event.createdAt} />
      </td>
      <td className="px-4 py-3">
        <div className="flex flex-col items-start gap-1">
          <Badge tone={known?.tone ?? 'neutral'}>{known?.label ?? event.action}</Badge>
          <code className="font-mono text-xs text-muted-foreground">{event.action}</code>
        </div>
      </td>
      <td className="px-4 py-3">
        {event.actorId === null ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <button
            type="button"
            title={`Show only events by ${event.actorId}`}
            className="min-h-11 rounded font-mono text-xs text-primary underline-offset-4 hover:underline sm:min-h-0"
            onClick={() => {
              onActor(event.actorId ?? '');
            }}
          >
            {event.actorId.slice(0, 8)}…
          </button>
        )}
      </td>
      <td className="px-4 py-3">
        {details.length === 0 ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <dl className="flex flex-wrap gap-1">
            {details.map(([key, value]) => (
              <div
                key={key}
                className="flex max-w-full gap-1 rounded bg-surface-high px-2 py-0.5 text-xs"
              >
                <dt className="text-muted-foreground">{key}</dt>
                <dd className="truncate font-mono" title={formatValue(value)}>
                  {formatValue(value)}
                </dd>
              </div>
            ))}
          </dl>
        )}
      </td>
    </tr>
  );
}

function formatValue(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value);
}
