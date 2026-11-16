import { UuidSchema } from '@scanova/contracts/common';
import { FileVariantQuerySchema } from '@scanova/contracts/documents';
import type { NextRequest } from 'next/server';
import { contentDisposition } from '@/lib/filename';
import { getApi } from '@/server/api/instance';
import { streamFile } from '@/server/download';
import { envelopeJson, errorJson, sessionForRoute } from '@/server/route';

/**
 * The document's PDF. `?variant=searchable` for the server's OCR copy,
 * `?download=1` to save it under the document's title.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> },
): Promise<Response> {
  const signedIn = await sessionForRoute('site');
  if (signedIn instanceof Response) {
    return signedIn;
  }
  const id = UuidSchema.safeParse((await params).id);
  const variant = FileVariantQuerySchema.safeParse({
    variant: request.nextUrl.searchParams.get('variant') ?? undefined,
  });
  if (!id.success || !variant.success) {
    return errorJson(404, 'NOT_FOUND', 'Not found.');
  }
  const api = getApi();
  const download = request.nextUrl.searchParams.get('download') === '1';
  let disposition = contentDisposition('inline', 'document', 'pdf');
  if (download) {
    const document = await api.documents.get(signedIn.context, id.data);
    if (!document.ok) {
      return envelopeJson(document);
    }
    const suffix = variant.data.variant === 'searchable' ? ' (searchable)' : '';
    disposition = contentDisposition('attachment', `${document.data.title}${suffix}`, 'pdf');
  }
  const upstream = await api.documents.file(
    signedIn.context,
    id.data,
    variant.data.variant,
    request.signal,
  );
  return streamFile(upstream, 'application/pdf', disposition);
}
