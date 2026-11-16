import { AuditEventQuerySchema } from '@scanova/contracts/admin';
import type { NextRequest } from 'next/server';
import { getApi } from '@/server/api/instance';
import { envelopeJson, errorJson, sessionForRoute } from '@/server/route';

export async function GET(request: NextRequest): Promise<Response> {
  const signedIn = await sessionForRoute('admin');
  if (signedIn instanceof Response) {
    return signedIn;
  }
  const query = AuditEventQuerySchema.safeParse(Object.fromEntries(request.nextUrl.searchParams));
  if (!query.success) {
    return errorJson(400, 'VALIDATION_FAILED', 'Check the filters.');
  }
  return envelopeJson(await getApi().admin.auditEvents(signedIn.context, query.data));
}
