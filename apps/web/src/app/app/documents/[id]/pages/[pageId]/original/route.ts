import { PageParamSchema } from '@scanova/contracts/documents';
import type { NextRequest } from 'next/server';
import { contentDisposition } from '@/lib/filename';
import { getApi } from '@/server/api/instance';
import { streamFile } from '@/server/download';
import { envelopeJson, errorJson, sessionForRoute } from '@/server/route';

/**
 * A page's original photo, byte for byte as the camera took it (before crop
 * and filters). `?n=` is the page number for the file name.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string; pageId: string }> },
): Promise<Response> {
  const signedIn = await sessionForRoute('site');
  if (signedIn instanceof Response) {
    return signedIn;
  }
  const path = PageParamSchema.safeParse(await params);
  if (!path.success) {
    return errorJson(404, 'NOT_FOUND', 'Not found.');
  }
  const api = getApi();
  const document = await api.documents.get(signedIn.context, path.data.id);
  if (!document.ok) {
    return envelopeJson(document);
  }
  const number = Number(request.nextUrl.searchParams.get('n'));
  const page =
    Number.isInteger(number) && number > 0 && number <= 100_000 ? ` — page ${number}` : '';
  const upstream = await api.documents.pageOriginal(
    signedIn.context,
    path.data.id,
    path.data.pageId,
    request.signal,
  );
  return streamFile(
    upstream,
    'image/jpeg',
    contentDisposition('attachment', `${document.data.title}${page} (original)`, 'jpg'),
  );
}
