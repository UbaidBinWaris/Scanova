import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { loadApiConfig } from '@scanova/config/api';
import { ConfigError } from '@scanova/config/errors';
import { AppModule } from './app.module.js';
import { runCli } from './cli/run.js';
import { AppLogger, describeError } from '@scanova/config/logger';

/**
 * Operator CLI: `node dist/cli.js <command>`. Runs on the server itself with the
 * API's own configuration, and covers user and key management until the web
 * console exists (§5.5).
 */
async function main(): Promise<number> {
  return runCli(
    process.argv.slice(2),
    {
      out: (line) => process.stdout.write(`${line}\n`),
      err: (line) => process.stderr.write(`${line}\n`),
    },
    () => {
      const config = loadApiConfig();
      // Diagnostics go to stderr so stdout carries only the command's result.
      const logger = new AppLogger('warn', 'cli', (line) => process.stderr.write(line));
      return NestFactory.createApplicationContext(AppModule.register(config, { logger }), {
        logger,
      });
    },
  );
}

main().then(
  (code) => {
    process.exitCode = code;
  },
  (error: unknown) => {
    const message =
      error instanceof ConfigError
        ? `Configuration is incomplete:\n${error.issues.map((issue) => `  ${issue.variable}: ${issue.message}`).join('\n')}`
        : `Command failed: ${JSON.stringify(describeError(error))}`;
    process.stderr.write(`${message}\n`);
    process.exitCode = 1;
  },
);
