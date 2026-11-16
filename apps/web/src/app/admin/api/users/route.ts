import { PageQuerySchema } from '@scanova/contracts/common';
import type { NextRequest } from 'next/server';
import { getApi } from '@/server/api/instance';
import { envelopeJson, errorJson, sessionForRoute } from '@/server/route';

export async function GET(request: NextRequest): Promise<Response> {
  const signedIn = await sessionForRoute('admin');
  if (signedIn instanceof Response) {
    return signedIn;
  }
  const query = PageQuerySchema.safeParse(Object.fromEntries(request.nextUrl.searchParams));
  if (!query.success) {
    return errorJson(400, 'VALIDATION_FAILED', 'The request is not valid.');
  }
  return envelopeJson(await getApi().admin.users(signedIn.context, query.data));
}
