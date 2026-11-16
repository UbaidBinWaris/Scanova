'use client';

import type { ReactNode } from 'react';
import { CopyButton } from '@/components/ui/copy-button';
import { Alert } from '@/components/ui/feedback';

/** A temporary password, shown exactly once: the server keeps only its hash. */
export function TemporaryPassword({
  email,
  password,
}: {
  email: string;
  password: string;
}): ReactNode {
  return (
    <div className="flex flex-col gap-4">
      <Alert tone="info" title="Shown only now">
        Give this password to {email} over a channel you trust. They must choose their own password
        when they first sign in. It cannot be shown again; reset it if it is lost.
      </Alert>
      <div className="flex flex-col gap-2 rounded-lg border border-border bg-surface-low p-4">
        <span className="text-xs font-medium text-muted-foreground">Temporary password</span>
        <code className="break-all font-mono text-base">{password}</code>
        <div>
          <CopyButton value={password} label="Copy password" />
        </div>
      </div>
    </div>
  );
}
