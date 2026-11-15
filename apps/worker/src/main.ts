import { ConfigError } from '@scanova/config/errors';
import { AppLogger, describeError } from '@scanova/config/logger';
import { loadWorkerConfig } from '@scanova/config/worker';
import { createWorker } from './bootstrap.js';
import { JobRunner } from './job-runner.js';

/** Worker entry point: `node dist/main.js`. Runs until it receives SIGTERM or SIGINT. */
async function main(): Promise<void> {
  const config = loadWorkerConfig();
  const app = await createWorker(config);
  await app.get(JobRunner).start();
  app.get(AppLogger).info('worker.started', { nodeEnv: config.runtime.nodeEnv });
}

main().catch((error: unknown) => {
  const message =
    error instanceof ConfigError
      ? JSON.stringify({
          level: 'fatal',
          service: 'worker',
          event: 'worker.config_invalid',
          issues: error.issues,
        })
      : JSON.stringify({
          level: 'fatal',
          service: 'worker',
          event: 'worker.start_failed',
          ...describeError(error),
        });
  process.stderr.write(`${message}\n`);
  process.exit(1);
});
