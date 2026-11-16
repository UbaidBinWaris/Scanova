'use client';

import type { Session } from '@scanova/contracts/me';
import { useRouter } from 'next/navigation';
import { useState, useTransition, type ReactNode } from 'react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { Alert } from '@/components/ui/feedback';
import { LocalTime } from '@/components/ui/local-time';
import { useToast } from '@/components/ui/toast';
import { revokeSession } from '@/server/actions/account';

const PLATFORM: Record<string, string> = { android: 'Android app', web: 'Web' };

export function SessionsList({ sessions }: { sessions: readonly Session[] }): ReactNode {
  const router = useRouter();
  const { notify } = useToast();
  const [target, setTarget] = useState<Session | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  const confirm = (): void => {
    if (target === null) {
      return;
    }
    startTransition(async () => {
      const result = await revokeSession(target.id, target.current);
      if (result !== undefined && !result.ok) {
        setError(result.message);
        return;
      }
      setTarget(null);
      notify('Signed out on that device');
      router.refresh();
    });
  };

  const ordered = [...sessions].sort(
    (a, b) => Number(b.current) - Number(a.current) || b.lastUsedAt.localeCompare(a.lastUsedAt),
  );

  return (
    <>
      <ul className="-my-4 divide-y divide-border">
        {ordered.map((session) => (
          <li
            key={session.id}
            className="flex flex-col gap-2 py-4 sm:flex-row sm:items-center sm:justify-between"
          >
            <div className="flex min-w-0 flex-col gap-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="truncate text-sm font-medium" dir="auto">
                  {session.deviceName}
                </span>
                {session.current ? <Badge tone="positive">This browser</Badge> : null}
              </div>
              <span className="text-xs text-muted-foreground">
                {PLATFORM[session.platform] ?? session.platform}
                {session.model !== '' && session.model !== 'Browser' ? ` · ${session.model}` : ''} ·
                Last active <LocalTime iso={session.lastUsedAt} />
              </span>
            </div>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setError(null);
                setTarget(session);
              }}
            >
              {session.current ? 'Sign out' : 'Sign out device'}
            </Button>
          </li>
        ))}
      </ul>
      {target !== null ? (
        <Dialog
          open
          onClose={() => {
            setTarget(null);
          }}
          title={target.current ? 'Sign out of this browser?' : 'Sign out this device?'}
          description={
            target.current
              ? 'You will need your password to sign in again.'
              : `“${target.deviceName}” is signed out at once. Documents already on that phone stay there until it signs in again or the app is removed.`
          }
          footer={
            <>
              <Button variant="ghost" onClick={() => setTarget(null)}>
                Cancel
              </Button>
              <Button variant="destructive" pending={pending} onClick={confirm}>
                Sign out
              </Button>
            </>
          }
        >
          {error !== null ? <Alert>{error}</Alert> : null}
        </Dialog>
      ) : null}
    </>
  );
}
