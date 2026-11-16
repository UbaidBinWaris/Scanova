import type { ReactNode } from 'react';
import { ChangePasswordForm } from '@/components/auth/change-password-form';
import { SignOutButton } from '@/components/layout/sign-out-button';
import { Section } from '@/components/ui/card';
import { PageHeader, ProgressBar } from '@/components/ui/feedback';
import { formatBytes, usagePercent } from '@/lib/format';
import type { Surface } from '@/lib/surface';
import { getApi } from '@/server/api/instance';
import { unwrap } from '@/server/guard';
import { requireSession } from '@/server/session/server';
import { ProfileForm } from './profile-form';
import { SessionsList } from './sessions-list';

/** Account settings for members and admins: profile, storage, password, sessions. */
export async function AccountScreen({ surface }: { surface: Surface }): Promise<ReactNode> {
  const { context } = await requireSession(surface);
  const api = getApi();
  const [meResult, sessionsResult] = await Promise.all([
    api.me.get(context),
    api.me.sessions(context),
  ]);
  const me = unwrap(meResult, surface);
  const sessions = unwrap(sessionsResult, surface);
  const percent = usagePercent(me.storageUsedBytes, me.storageQuotaBytes);

  return (
    <div className="flex flex-col gap-8">
      <PageHeader title="Account" description="Your profile, password and signed-in devices." />
      <Section title="Profile" description="Your name is shown in the app and to administrators.">
        <ProfileForm name={me.name} email={me.email} />
      </Section>
      {surface === 'site' ? (
        <Section
          title="Storage"
          description="Every stored PDF and original page photo counts, encrypted size included."
        >
          <div className="flex flex-col gap-2">
            <p className="text-sm">
              <span className="font-medium">{formatBytes(me.storageUsedBytes)}</span>{' '}
              <span className="text-muted-foreground">
                {me.storageQuotaBytes === null
                  ? 'used · no limit'
                  : `of ${formatBytes(me.storageQuotaBytes)} used (${percent ?? 0}%)`}
              </span>
            </p>
            {percent !== null ? (
              <ProgressBar
                value={percent}
                label="Storage used"
                tone={percent >= 95 ? 'destructive' : percent >= 80 ? 'caution' : 'primary'}
              />
            ) : null}
          </div>
        </Section>
      ) : null}
      <Section title="Password" description="Changing it signs out every other device.">
        <div className="max-w-md">
          <ChangePasswordForm temporary={false} redirectTo={null} />
        </div>
      </Section>
      <Section title="Signed-in devices" description="Sign out anything you do not recognise.">
        <SessionsList sessions={sessions} />
      </Section>
      <div>
        <SignOutButton variant="secondary" />
      </div>
    </div>
  );
}
