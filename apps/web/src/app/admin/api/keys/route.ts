import { getApi } from '@/server/api/instance';
import { envelopeJson, sessionForRoute } from '@/server/route';

export async function GET(): Promise<Response> {
  const signedIn = await sessionForRoute('admin');
  if (signedIn instanceof Response) {
    return signedIn;
  }
  return envelopeJson(await getApi().admin.keys(signedIn.context));
}
