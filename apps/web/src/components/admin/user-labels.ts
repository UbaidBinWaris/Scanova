import type { User } from '@scanova/contracts/auth';
import type { BadgeTone } from '@/components/ui/badge';

export const USER_TYPE_LABEL: Record<User['userType'], string> = {
  ADMIN: 'Administrator',
  MEMBER: 'Member',
};

export const USER_STATUS: Record<User['status'], { label: string; tone: BadgeTone }> = {
  ACTIVE: { label: 'Active', tone: 'positive' },
  DISABLED: { label: 'Disabled', tone: 'destructive' },
};
