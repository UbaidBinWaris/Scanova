import { existsSync, readFileSync } from 'node:fs';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { parseEnv } from 'node:util';

/** Raw variables after merging the root `.env` with the process environment. */
export interface EnvSource {
  readonly values: Readonly<Record<string, string | undefined>>;
  /** Directory relative paths (for example `SCANOVA_CA_CERT_PATH`) resolve against. */
  readonly baseDir: string;
}

export interface EnvSourceOptions {
  /** Explicit env file. Default: `.env` beside the nearest `pnpm-workspace.yaml`. */
  readonly envFilePath?: string;
  /** Where the search for the workspace root starts. Default: the working directory. */
  readonly cwd?: string;
  /** Overrides the process environment (tests). */
  readonly processEnv?: Readonly<Record<string, string | undefined>>;
}

const WORKSPACE_MARKER = 'pnpm-workspace.yaml';
const ENV_FILE_NAME = '.env';

/** Walks up from `startDir` to the repository root. Returns null outside a checkout (containers). */
export function findWorkspaceRoot(startDir: string): string | null {
  let current = resolve(startDir);
  for (;;) {
    if (existsSync(join(current, WORKSPACE_MARKER))) {
      return current;
    }
    const parent = dirname(current);
    if (parent === current) {
      return null;
    }
    current = parent;
  }
}

/**
 * Builds the variable source for a loader.
 *
 * On a host the single root `.env` is read. In a container there is no file:
 * Compose passes each process exactly the variables it needs. When both exist the
 * process environment wins, so one-off overrides do not require editing the file.
 * Empty values count as unset, which is what an unfilled `<generated>` secret is.
 */
export function readEnvSource(options: EnvSourceOptions = {}): EnvSource {
  const cwd = options.cwd ?? process.cwd();
  const processEnv = options.processEnv ?? process.env;
  const root = findWorkspaceRoot(cwd);
  const envFilePath = options.envFilePath ?? (root === null ? null : join(root, ENV_FILE_NAME));

  let fileValues: Record<string, string | undefined> = {};
  let baseDir = cwd;
  if (envFilePath !== null && existsSync(envFilePath)) {
    fileValues = parseEnv(readFileSync(envFilePath, 'utf8'));
    baseDir = dirname(resolve(envFilePath));
  }

  const values: Record<string, string | undefined> = {};
  for (const [name, value] of Object.entries({ ...fileValues, ...definedOnly(processEnv) })) {
    values[name] = value === undefined || value.trim() === '' ? undefined : value;
  }
  return { values, baseDir };
}

/** Resolves a possibly relative path from the environment against the source's base directory. */
export function resolveFromBase(source: EnvSource, path: string): string {
  return isAbsolute(path) ? path : resolve(source.baseDir, path);
}

function definedOnly(
  env: Readonly<Record<string, string | undefined>>,
): Record<string, string | undefined> {
  const result: Record<string, string | undefined> = {};
  for (const [name, value] of Object.entries(env)) {
    if (value !== undefined && value.trim() !== '') {
      result[name] = value;
    }
  }
  return result;
}
