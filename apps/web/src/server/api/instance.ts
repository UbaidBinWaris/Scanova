import 'server-only';
import { getWebConfig } from '../config';
import { ApiClient } from './client';
import { scanovaApi, type ScanovaApi } from './scanova';

let cached: ScanovaApi | null = null;

export function getApi(): ScanovaApi {
  if (cached === null) {
    const config = getWebConfig();
    cached = scanovaApi(
      new ApiClient({ apiUrl: config.apiUrl, serviceToken: config.serviceToken }),
    );
  }
  return cached;
}
