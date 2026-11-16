import 'server-only';
import type { WebConfig } from '@scanova/config/sections';
import { loadWebConfig } from '@scanova/config/web';

let cached: WebConfig | null = null;

/** Validated once per process from the root .env / container environment (§4). */
export function getWebConfig(): WebConfig {
  cached ??= loadWebConfig();
  return cached;
}
