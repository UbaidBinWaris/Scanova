import { ListDocumentsQuerySchema } from '@scanova/contracts/documents';
import type { NextRequest } from 'next/server';
import { getApi } from '@/server/api/instance';
import { envelopeJson, errorJson, sessionForRoute } from '@/server/route';

/** The documents list for the browser (React Query): the API's page, revalidated. */
export async function GET(request: NextRequest): Promise<Response> {
  const signedIn = await sessionForRoute('site');
  if (signedIn instanceof Response) {
    return signedIn;
  }
  const query = ListDocumentsQuerySchema.safeParse(
    Object.fromEntries(request.nextUrl.searchParams),
  );
  if (!query.success || query.data.updatedSince !== undefined) {
    return errorJson(400, 'VALIDATION_FAILED', 'The request is not valid.');
  }
  return envelopeJson(
    await getApi().documents.list(signedIn.context, {
      limit: query.data.limit,
      cursor: query.data.cursor,
      q: query.data.q,
    }),
  );
}
