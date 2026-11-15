import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** Version of this server, from the app's package.json (one place to bump). */
export const SERVER_VERSION: string = (
  JSON.parse(readFileSync(resolve(import.meta.dirname, '..', 'package.json'), 'utf8')) as {
    version: string;
  }
).version;
