// Location and shape of the integration-test state: the connection details of
// the throwaway stack, written by provision.mjs and read by the test suites,
// which turn them into config objects. Tests never read the root `.env`.
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

/** Repository root. */
export const workspaceRoot = resolve(here, '..', '..', '..');

/** Git-ignored scratch directory for the test stack (certificates, state file). */
export const stateDir = join(workspaceRoot, 'node_modules', '.cache', 'scanova-integration');

export const stateFile = join(stateDir, 'state.json');

/** @returns {import('./state.d.ts').IntegrationState} */
export function readIntegrationState() {
  if (!existsSync(stateFile)) {
    throw new Error(
      'Integration services are not running. Start them with `pnpm test:integration:up`, or run `pnpm test:integration`.',
    );
  }
  return JSON.parse(readFileSync(stateFile, 'utf8'));
}
