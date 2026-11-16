import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { loadApiConfig, parseApiConfig } from './api.js';
import { parseDatabaseConfig } from './database.js';
import { readEnvSource, type EnvSource } from './env-source.js';
import { ConfigError } from './errors.js';
import { parseKeystoreConfig } from './keystore.js';
import { parseMigrateConfig } from './migrate.js';
import { buildPostgresConfig, quoteIdentifier, toPgConnectionOptions } from './postgres.js';
import { PRISMA_CLI_URL_VARIABLE, prismaCliEnvironment } from './prisma-cli.js';
import { parseWorkerConfig } from './worker.js';

const SHARED = {
  SCANOVA_CA_CERT_PATH: './infra/.state/pki/ca.crt',
  POSTGRES_HOST: 'db.internal',
  POSTGRES_PORT: '55432',
  POSTGRES_DB: 'scanova',
  POSTGRES_APP_USER: 'scanova_app',
  POSTGRES_APP_PASSWORD: 'p@ss/word:1',
  POSTGRES_MIGRATOR_USER: 'scanova_migrator',
  POSTGRES_MIGRATOR_PASSWORD: 'migrator-secret',
  KEYS_POSTGRES_HOST: 'keys.internal',
  KEYS_POSTGRES_PORT: '55433',
  KEYS_POSTGRES_DB: 'scanova_keys',
  KEYS_POSTGRES_APP_USER: 'scanova_keys_app',
  KEYS_POSTGRES_APP_PASSWORD: 'keys-app-secret',
  KEYS_POSTGRES_MIGRATOR_USER: 'scanova_keys_migrator',
  KEYS_POSTGRES_MIGRATOR_PASSWORD: 'keys-migrator-secret',
  S3_ENDPOINT: 'http://127.0.0.1:53900',
  S3_REGION: 'garage',
  S3_BUCKET: 'scanova-documents',
  S3_ACCESS_KEY_ID: 'GK0123456789',
  S3_SECRET_ACCESS_KEY: '0123456789abcdef0123456789abcdef', // gitleaks:allow -- not a secret (test fixture, alphabet or config pass-through)
  BAO_ADDR: 'https://127.0.0.1:58200',
};

const API_ONLY = {
  WEB_SERVICE_TOKEN: 'w'.repeat(48),
  BAO_API_ROLE_ID: 'api-role',
  BAO_API_SECRET_ID: 'api-secret',
};

const WORKER_ONLY = {
  SCANOVA_TMP_DIR: '/run/scanova-tmp',
  BAO_WORKER_ROLE_ID: 'worker-role',
  BAO_WORKER_SECRET_ID: 'worker-secret',
};

function source(values: Record<string, string | undefined>): EnvSource {
  return { values, baseDir: '/srv/scanova' };
}

describe('per-process loaders', () => {
  it('builds the API config with defaults and resolves the CA path against the base directory', () => {
    const config = parseApiConfig(source({ ...SHARED, ...API_ONLY }));
    expect(config.http).toEqual({ port: 3000, maxUploadBytes: 104_857_600 });
    expect(config.auth.argon2).toEqual({ memoryKib: 65_536, timeCost: 3, parallelism: 1 });
    expect(config.database.tls).toEqual({ caCertPath: '/srv/scanova/infra/.state/pki/ca.crt' });
    expect(config.kms.caCertPath).toBe('/srv/scanova/infra/.state/pki/ca.crt');
    expect(config.kms.roleId).toBe('api-role');
    expect(config.keys.userTypeMember).toBe('scanova-utk-member');
    expect(config.features).toEqual({ serverOcr: true, ai: false, signup: false });
    // Open sign-up is off unless asked for, and has a quota and limits when on.
    expect(config.signup).toEqual({
      enabled: false,
      quotaBytes: 2_000_000_000,
      maxPerIpPerHour: 3,
      maxPerHour: 20,
    });
  });

  it('keeps the key database connection independent of the main one', () => {
    const config = parseApiConfig(source({ ...SHARED, ...API_ONLY }));
    expect(config.database.host).toBe('db.internal');
    expect(config.database.user).toBe('scanova_app');
    expect(config.keystore.host).toBe('keys.internal');
    expect(config.keystore.port).toBe(55_433);
    expect(config.keystore.database).toBe('scanova_keys');
    expect(config.keystore.user).toBe('scanova_keys_app');
  });

  it('does not require the API secrets for the worker', () => {
    const config = parseWorkerConfig(source({ ...SHARED, ...WORKER_ONLY }));
    expect(config.kms.roleId).toBe('worker-role');
    expect(config.ocr).toEqual({
      concurrency: 1,
      languages: 'eng',
      tmpDir: '/run/scanova-tmp',
    });
  });

  it('does not require the worker secrets for the API', () => {
    expect(() => parseApiConfig(source({ ...SHARED, ...API_ONLY }))).not.toThrow();
    expect(() => parseApiConfig(source({ ...SHARED, ...WORKER_ONLY }))).toThrow(ConfigError);
  });

  it('gives the migrate step both migrator roles and the runtime role names only', () => {
    const config = parseMigrateConfig(
      source({
        ...SHARED,
        POSTGRES_APP_PASSWORD: undefined,
        KEYS_POSTGRES_APP_PASSWORD: undefined,
      }),
    );
    expect(config.database.migrator.user).toBe('scanova_migrator');
    expect(config.database.runtimeRole).toBe('scanova_app');
    expect(config.keystore.migrator.user).toBe('scanova_keys_migrator');
    expect(config.keystore.migrator.database).toBe('scanova_keys');
    expect(config.keystore.runtimeRole).toBe('scanova_keys_app');
  });

  it('reports every missing or malformed variable by name and never echoes a value', () => {
    const broken = {
      ...SHARED,
      ...API_ONLY,
      POSTGRES_PORT: 'not-a-port',
      WEB_SERVICE_TOKEN: 'short-secret-value',
      S3_SECRET_ACCESS_KEY: undefined,
    };
    let caught: unknown;
    try {
      parseApiConfig(source(broken));
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(ConfigError);
    const error = caught as ConfigError;
    const variables = error.issues.map((issue) => issue.variable);
    expect(variables).toEqual(
      expect.arrayContaining(['WEB_SERVICE_TOKEN', 'S3_SECRET_ACCESS_KEY']),
    );
    expect(error.message).not.toContain('short-secret-value');
    expect(error.message).toContain('S3_SECRET_ACCESS_KEY: is required but missing or empty');
    expect(() => parseDatabaseConfig(source(broken))).toThrow(/POSTGRES_PORT/u);
  });

  it('rejects an OCR language list that is not a plain language code list', () => {
    expect(() =>
      parseWorkerConfig(source({ ...SHARED, ...WORKER_ONLY, OCR_LANGUAGES: 'eng; rm -rf /' })),
    ).toThrow(/OCR_LANGUAGES/u);
  });
});

describe('connection strings', () => {
  it('assembles a strictly verified URL from the parts and escapes the password', () => {
    const config = parseDatabaseConfig(source(SHARED));
    const url = new URL(config.prismaCliUrl);
    expect(url.protocol).toBe('postgresql:');
    expect(url.hostname).toBe('db.internal');
    expect(url.port).toBe('55432');
    expect(url.pathname).toBe('/scanova');
    expect(decodeURIComponent(url.password)).toBe('p@ss/word:1');
    // The schema engine verifies the server certificate only with sslaccept=strict.
    expect(url.searchParams.get('sslmode')).toBe('require');
    expect(url.searchParams.get('sslaccept')).toBe('strict');
    expect(url.searchParams.get('sslcert')).toBe('/srv/scanova/infra/.state/pki/ca.crt');
  });

  it('builds a plaintext URL only when TLS is explicitly null (tests)', () => {
    const config = buildPostgresConfig({
      host: '127.0.0.1',
      port: 56_432,
      database: 'scanova',
      user: 'u',
      password: 'p',
      tls: null,
    });
    expect(config.prismaCliUrl).toBe('postgresql://u:p@127.0.0.1:56432/scanova?sslmode=disable');
    expect(toPgConnectionOptions(config).ssl).toBe(false);
  });

  it('uses the key-database variables for the keystore', () => {
    const config = parseKeystoreConfig(source(SHARED));
    expect(new URL(config.prismaCliUrl).pathname).toBe('/scanova_keys');
    expect(config.user).toBe('scanova_keys_app');
  });

  it('quotes only plain identifiers for DDL', () => {
    expect(quoteIdentifier('scanova_keys_app')).toBe('"scanova_keys_app"');
    expect(() => quoteIdentifier('app"; DROP ROLE x; --')).toThrow();
    expect(() => quoteIdentifier('App')).toThrow();
  });
});

describe('Prisma CLI hand-off', () => {
  it('builds a minimal child environment that carries only the datasource URL', () => {
    const environment = prismaCliEnvironment('postgresql://m:secret@db/scanova');
    expect(environment[PRISMA_CLI_URL_VARIABLE]).toBe('postgresql://m:secret@db/scanova');
    expect(Object.keys(environment).sort()).toEqual(
      expect.arrayContaining(['CHECKPOINT_DISABLE', PRISMA_CLI_URL_VARIABLE]),
    );
    for (const name of Object.keys(environment)) {
      expect([
        'CHECKPOINT_DISABLE',
        'PRISMA_HIDE_UPDATE_MESSAGE',
        'NO_COLOR',
        'PATH',
        'HOME',
        'TMPDIR',
        PRISMA_CLI_URL_VARIABLE,
      ]).toContain(name);
    }
  });
});

describe('env source', () => {
  const dirs: string[] = [];
  afterEach(() => {
    for (const dir of dirs.splice(0)) {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  function workspace(envFile: string): string {
    const dir = mkdtempSync(join(tmpdir(), 'scanova-config-'));
    dirs.push(dir);
    writeFileSync(join(dir, 'pnpm-workspace.yaml'), 'packages: []\n');
    writeFileSync(join(dir, '.env'), envFile);
    return dir;
  }

  it('reads the root .env, lets the process environment win, and treats empty values as unset', () => {
    const dir = workspace('LOG_LEVEL=debug\nAPI_PORT=4000\nWEB_SERVICE_TOKEN=\n');
    const merged = readEnvSource({ cwd: dir, processEnv: { API_PORT: '5000', EMPTY: '' } });
    expect(merged.values.LOG_LEVEL).toBe('debug');
    expect(merged.values.API_PORT).toBe('5000');
    expect(merged.values.WEB_SERVICE_TOKEN).toBeUndefined();
    expect(merged.values.EMPTY).toBeUndefined();
    expect(merged.baseDir).toBe(dir);
  });

  it('falls back to the process environment when there is no workspace root', () => {
    const dir = mkdtempSync(join(tmpdir(), 'scanova-config-'));
    dirs.push(dir);
    const merged = readEnvSource({ cwd: dir, processEnv: { LOG_LEVEL: 'warn' } });
    expect(merged.values).toEqual({ LOG_LEVEL: 'warn' });
  });

  it('loads a full API config from a file', () => {
    const lines = Object.entries({ ...SHARED, ...API_ONLY }).map(([k, v]) => `${k}=${v}`);
    const dir = workspace(`${lines.join('\n')}\n`);
    const config = loadApiConfig({ cwd: dir, processEnv: {} });
    expect(config.database.tls?.caCertPath).toBe(join(dir, 'infra/.state/pki/ca.crt'));
  });
});
