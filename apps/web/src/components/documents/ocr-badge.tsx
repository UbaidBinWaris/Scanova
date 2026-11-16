import type { OcrStatus } from '@scanova/contracts/enums';
import type { ReactNode } from 'react';
import { Badge } from '@/components/ui/badge';

const LABELS: Record<
  OcrStatus,
  { text: string; tone: 'neutral' | 'positive' | 'caution' | 'destructive' }
> = {
  NONE: { text: 'No text recognition', tone: 'neutral' },
  PENDING: { text: 'Recognising text…', tone: 'caution' },
  DONE: { text: 'Searchable', tone: 'positive' },
  FAILED: { text: 'Text recognition failed', tone: 'destructive' },
};

export function OcrBadge({ status }: { status: OcrStatus }): ReactNode {
  const label = LABELS[status];
  return <Badge tone={label.tone}>{label.text}</Badge>;
}
