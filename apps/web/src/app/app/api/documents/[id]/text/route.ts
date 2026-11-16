import { UuidSchema } from '@scanova/contracts/common';
import { getApi } from '@/server/api/instance';
import { envelopeJson, errorJson, sessionForRoute } from '@/server/route';

/** A document's text (OCR), loaded only when the reader opens the Text tab. */
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ id: string }> },
): Promise<Response> {
  const signedIn = await sessionForRoute('site');
  if (signedIn instanceof Response) {
    return signedIn;
  }
  const id = UuidSchema.safeParse((await params).id);
  if (!id.success) {
    return errorJson(404, 'NOT_FOUND', 'Not found.');
  }
  return envelopeJson(await getApi().documents.text(signedIn.context, id.data));
}
