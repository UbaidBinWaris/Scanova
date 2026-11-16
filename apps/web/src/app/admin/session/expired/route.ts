import { handleExpiredSession } from '@/server/session/expired';

export function GET(): Promise<Response> {
  return handleExpiredSession('admin');
}
