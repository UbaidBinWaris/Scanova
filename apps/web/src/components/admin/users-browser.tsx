'use client';

import { UserListSchema } from '@scanova/contracts/admin';
import type { User } from '@scanova/contracts/auth';
import { useInfiniteQuery } from '@tanstack/react-query';
import { useMemo, useState, type ReactNode } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Alert, EmptyState, PageHeader, Skeleton } from '@/components/ui/feedback';
import { Input } from '@/components/ui/field';
import { PlusIcon, SearchIcon, UsersIcon } from '@/components/ui/icons';
import { LocalTime } from '@/components/ui/local-time';
import { fetchEnvelope } from '@/lib/client-api';
import { formatBytes, pluralize } from '@/lib/format';
import { CreateUserDialog } from './create-user-dialog';
import { ManageUserDialog } from './manage-user-dialog';
import { USER_STATUS, USER_TYPE_LABEL } from './user-labels';

function usersUrl(cursor: string | null): string {
  return `/admin/api/users?limit=100${cursor === null ? '' : `&cursor=${encodeURIComponent(cursor)}`}`;
}

export function UsersBrowser({ currentUserId }: { currentUserId: string }): ReactNode {
  const [filter, setFilter] = useState('');
  const [creating, setCreating] = useState(false);
  const [managing, setManaging] = useState<User | null>(null);

  const query = useInfiniteQuery({
    queryKey: ['admin-users'],
    queryFn: ({ pageParam, signal }) =>
      fetchEnvelope(usersUrl(pageParam), UserListSchema, 'admin', signal),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
  });

  const users = useMemo(() => (query.data?.pages ?? []).flatMap((page) => page.data), [query.data]);
  const needle = filter.trim().toLocaleLowerCase();
  const shown =
    needle === ''
      ? users
      : users.filter(
          (user) =>
            user.email.toLocaleLowerCase().includes(needle) ||
            user.name.toLocaleLowerCase().includes(needle),
        );

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Users"
        description={
          query.isPending
            ? 'Loading…'
            : `${pluralize(users.length, 'account')}${query.hasNextPage ? ' so far' : ''}`
        }
        actions={
          <Button onClick={() => setCreating(true)}>
            <PlusIcon width={18} height={18} />
            Create user
          </Button>
        }
      />

      <div className="flex max-w-md flex-col gap-1.5">
        <label htmlFor="user-filter" className="text-sm font-medium">
          Filter
        </label>
        <div className="relative">
          <SearchIcon
            className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
            width={18}
            height={18}
          />
          <Input
            id="user-filter"
            type="search"
            className="pl-10"
            placeholder="Name or e-mail"
            value={filter}
            onChange={(event) => {
              setFilter(event.target.value);
            }}
          />
        </div>
      </div>

      {query.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : query.isError ? (
        <div className="flex flex-col items-start gap-4">
          <Alert title="The accounts could not be loaded">{query.error.message}</Alert>
          <Button variant="secondary" onClick={() => void query.refetch()}>
            Try again
          </Button>
        </div>
      ) : users.length === 0 ? (
        <EmptyState
          icon={<UsersIcon />}
          title="No accounts yet"
          description="Create the first account. Members can also sign up in the app when the server allows it."
          action={<Button onClick={() => setCreating(true)}>Create user</Button>}
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border border-border bg-card">
            <table className="w-full min-w-[44rem] text-left text-sm">
              <caption className="sr-only">Accounts, oldest first</caption>
              <thead className="border-b border-border text-xs text-muted-foreground">
                <tr>
                  <th scope="col" className="px-4 py-3 font-medium">
                    Account
                  </th>
                  <th scope="col" className="px-4 py-3 font-medium">
                    Type
                  </th>
                  <th scope="col" className="px-4 py-3 font-medium">
                    Status
                  </th>
                  <th scope="col" className="px-4 py-3 font-medium">
                    Storage
                  </th>
                  <th scope="col" className="px-4 py-3 font-medium">
                    Created
                  </th>
                  <th scope="col" className="px-4 py-3 font-medium">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {shown.map((user) => (
                  <tr key={user.id} className="hover:bg-surface-low">
                    <td className="max-w-72 px-4 py-3">
                      <div className="flex flex-col">
                        <span className="truncate font-medium" dir="auto">
                          {user.name}
                          {user.id === currentUserId ? (
                            <span className="ml-2 text-xs font-normal text-muted-foreground">
                              (you)
                            </span>
                          ) : null}
                        </span>
                        <span className="truncate text-xs text-muted-foreground">{user.email}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap gap-1">
                        <Badge tone={user.userType === 'ADMIN' ? 'caution' : 'neutral'}>
                          {USER_TYPE_LABEL[user.userType]}
                        </Badge>
                        {user.isOwner ? <Badge tone="primary">Owner</Badge> : null}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap gap-1">
                        <Badge tone={USER_STATUS[user.status].tone}>
                          {USER_STATUS[user.status].label}
                        </Badge>
                        {user.mustChangePassword ? (
                          <Badge tone="caution">Temporary password</Badge>
                        ) : null}
                      </div>
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 tabular-nums">
                      {formatBytes(user.storageUsedBytes)}
                      <span className="text-muted-foreground">
                        {' '}
                        /{' '}
                        {user.storageQuotaBytes === null
                          ? 'no limit'
                          : formatBytes(user.storageQuotaBytes)}
                      </span>
                    </td>
                    <td className="whitespace-nowrap px-4 py-3">
                      <LocalTime iso={user.createdAt} mode="date" />
                    </td>
                    <td className="px-4 py-3 text-right">
                      <Button
                        variant="secondary"
                        size="sm"
                        aria-label={`Manage ${user.name}`}
                        onClick={() => {
                          setManaging(user);
                        }}
                      >
                        Manage
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {shown.length === 0 ? (
            <p className="text-sm text-muted-foreground">No loaded account matches “{filter}”.</p>
          ) : null}
          {query.hasNextPage ? (
            <div className="flex justify-center">
              <Button
                variant="secondary"
                pending={query.isFetchingNextPage}
                onClick={() => void query.fetchNextPage()}
              >
                Load more accounts
              </Button>
            </div>
          ) : null}
        </>
      )}

      {creating ? <CreateUserDialog onClose={() => setCreating(false)} /> : null}
      {managing !== null ? (
        <ManageUserDialog
          key={managing.id}
          user={managing}
          isSelf={managing.id === currentUserId}
          onClose={() => setManaging(null)}
        />
      ) : null}
    </div>
  );
}
