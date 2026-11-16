import { AppLogger } from '@scanova/config/logger';
import { getWebConfig } from './config';

let logger: AppLogger | null = null;

/** JSON lines on stdout, ids and codes only — never tokens, e-mail addresses or titles (§10). */
export function getLogger(): AppLogger {
  logger ??= new AppLogger(getWebConfig().runtime.logLevel, 'web');
  return logger;
}
