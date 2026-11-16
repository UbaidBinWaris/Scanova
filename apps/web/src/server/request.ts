import 'server-only';
import { headers } from 'next/headers';
import { originFor, surfaceForHost, type Surface } from '@/lib/surface';
import { clientIpFrom } from './client-ip';
import { getWebConfig } from './config';

export interface RequestInfo {
  readonly surface: Surface;
  /** The origin the browser is on; mutations must come from it. */
  readonly origin: string;
  readonly secure: boolean;
  readonly clientIp: string | null;
  readonly userAgent: string | null;
}

/** Facts about the current request that the server trusts (host routing is done by the edge). */
export async function requestInfo(): Promise<RequestInfo> {
  const incoming = await headers();
  const config = getWebConfig();
  const surface = surfaceForHost(incoming.get('host'), config);
  const origin = originFor(surface, config);
  return {
    surface,
    origin,
    secure: origin.startsWith('https:'),
    clientIp: clientIpFrom(incoming.get('x-forwarded-for')),
    userAgent: incoming.get('user-agent'),
  };
}
