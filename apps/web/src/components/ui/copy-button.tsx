'use client';

import type { ReactNode } from 'react';
import { Button } from './button';
import { CopyIcon } from './icons';
import { useToast } from './toast';

export function CopyButton({
  value,
  label = 'Copy',
}: {
  value: string;
  label?: string;
}): ReactNode {
  const { notify } = useToast();
  return (
    <Button
      variant="secondary"
      size="sm"
      onClick={() => {
        navigator.clipboard.writeText(value).then(
          () => {
            notify('Copied to the clipboard');
          },
          () => {
            notify('Copying failed. Select the text and copy it instead.');
          },
        );
      }}
    >
      <CopyIcon width={16} height={16} />
      {label}
    </Button>
  );
}
