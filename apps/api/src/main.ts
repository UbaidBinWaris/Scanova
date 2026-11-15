import { loadApiConfig } from '@scanova/config/api';
import { ConfigError } from '@scanova/config/errors';
import { createApp } from './bootstrap.js';
import { AppLogger, describeError } from '@scanova/config/logger';

/** HTTP entry point: `node dist/main.js`. */
async function main(): Promise<void> {
  const config = loadApiConfig();
  const app = await createApp(config);
  await app.listen(config.http.port, '0.0.0.0');
  app
    .get(AppLogger)
    .info('api.started', { port: config.http.port, nodeEnv: config.runtime.nodeEnv });
}

main().catch((error: unknown) => {
  // Configuration problems name variables, never values, and are safe to print in full.
  const message =
    error instanceof ConfigError
      ? JSON.stringify({
          level: 'fatal',
          service: 'api',
          event: 'api.config_invalid',
          issues: error.issues,
        })
      : JSON.stringify({
          level: 'fatal',
          service: 'api',
          event: 'api.start_failed',
          ...describeError(error),
        });
  process.stderr.write(`${message}\n`);
  process.exit(1);
});
