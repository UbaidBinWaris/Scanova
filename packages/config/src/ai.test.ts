import { describe, expect, it } from 'vitest';
import { parseAiConfig } from './ai.js';
import { parseApiConfig } from './api.js';
import type { EnvSource } from './env-source.js';
import { ConfigError } from './errors.js';
import { parseWorkerConfig } from './worker.js';

const KEY = 'k'.repeat(48);

const AI_ON = {
  AI_PROVIDER: 'openai-compatible',
  AI_CHAT_BASE_URL: 'http://ai-chat:8080/v1/',
  AI_CHAT_MODEL: 'qwen3.5-4b',
  AI_EMBED_BASE_URL: 'http://ai-embed:8080/v1',
  AI_EMBED_MODEL: 'qwen3-embedding-0.6b',
  AI_EMBED_DIMENSIONS: '256',
  AI_API_KEY: KEY,
};

function source(values: Record<string, string | undefined>): EnvSource {
  return { values, baseDir: '/srv/scanova' };
}

function issuesOf(run: () => unknown): { variables: string[]; message: string } {
  try {
    run();
  } catch (error) {
    expect(error).toBeInstanceOf(ConfigError);
    const config = error as ConfigError;
    return { variables: config.issues.map((issue) => issue.variable), message: config.message };
  }
  throw new Error('expected a ConfigError');
}

describe('AI configuration', () => {
  it('is off by default', () => {
    expect(parseAiConfig('worker', source({}))).toEqual({ provider: 'none' });
  });

  it('ignores every other AI variable while the provider is none, even malformed ones', () => {
    const config = parseAiConfig(
      'worker',
      source({ ...AI_ON, AI_PROVIDER: 'none', AI_CHAT_BASE_URL: 'not a url', AI_API_KEY: 'x' }),
    );
    expect(config).toEqual({ provider: 'none' });
  });

  it('builds the runtime settings and drops a trailing slash from base URLs', () => {
    expect(parseAiConfig('worker', source(AI_ON))).toEqual({
      provider: 'openai-compatible',
      chat: { baseUrl: 'http://ai-chat:8080/v1', model: 'qwen3.5-4b' },
      embeddings: {
        baseUrl: 'http://ai-embed:8080/v1',
        model: 'qwen3-embedding-0.6b',
        dimensions: 256,
      },
      apiKey: KEY,
    });
  });

  it('requires every runtime variable once switched on and names each missing one', () => {
    const { variables } = issuesOf(() =>
      parseAiConfig('worker', source({ AI_PROVIDER: 'openai-compatible' })),
    );
    expect(variables.sort()).toEqual([
      'AI_API_KEY',
      'AI_CHAT_BASE_URL',
      'AI_CHAT_MODEL',
      'AI_EMBED_BASE_URL',
      'AI_EMBED_DIMENSIONS',
      'AI_EMBED_MODEL',
    ]);
  });

  it('rejects an unknown provider, including the retired "ollama"', () => {
    expect(
      issuesOf(() => parseAiConfig('worker', source({ AI_PROVIDER: 'ollama' }))).variables,
    ).toEqual(['AI_PROVIDER']);
  });

  it('rejects URLs with credentials or another scheme, and never echoes them', () => {
    const { variables, message } = issuesOf(() =>
      parseAiConfig(
        'worker',
        source({
          ...AI_ON,
          AI_CHAT_BASE_URL: 'http://user:hunter2@ai-chat:8080/v1',
          AI_EMBED_BASE_URL: 'file:///etc/passwd',
        }),
      ),
    );
    expect(variables.sort()).toEqual(['AI_CHAT_BASE_URL', 'AI_EMBED_BASE_URL']);
    expect(message).not.toContain('hunter2');
  });

  it('rejects a short or non-printable API key without echoing it', () => {
    const short = issuesOf(() =>
      parseAiConfig('worker', source({ ...AI_ON, AI_API_KEY: 'short-key' })),
    );
    expect(short.variables).toEqual(['AI_API_KEY']);
    expect(short.message).not.toContain('short-key');
    const spaced = `${'a'.repeat(20)} ${'b'.repeat(20)}`;
    expect(
      issuesOf(() => parseAiConfig('worker', source({ ...AI_ON, AI_API_KEY: spaced }))).variables,
    ).toEqual(['AI_API_KEY']);
  });

  it('keeps model names and dimensions within bounds', () => {
    const { variables } = issuesOf(() =>
      parseAiConfig(
        'worker',
        source({ ...AI_ON, AI_CHAT_MODEL: 'model with spaces', AI_EMBED_DIMENSIONS: '8' }),
      ),
    );
    expect(variables.sort()).toEqual(['AI_CHAT_MODEL', 'AI_EMBED_DIMENSIONS']);
  });
});

describe('AI in the process loaders', () => {
  const SHARED = {
    SCANOVA_CA_CERT_PATH: './infra/.state/pki/ca.crt',
    POSTGRES_HOST: 'db.internal',
    POSTGRES_PORT: '55432',
    POSTGRES_DB: 'scanova',
    POSTGRES_APP_USER: 'scanova_app',
    POSTGRES_APP_PASSWORD: 'app-secret-1',
    KEYS_POSTGRES_HOST: 'db.internal',
    KEYS_POSTGRES_PORT: '55432',
    KEYS_POSTGRES_DB: 'scanova_keys',
    KEYS_POSTGRES_APP_USER: 'scanova_keys_app',
    KEYS_POSTGRES_APP_PASSWORD: 'keys-secret-1',
    S3_ENDPOINT: 'http://127.0.0.1:53900',
    S3_REGION: 'garage',
    S3_BUCKET: 'scanova-documents',
    S3_ACCESS_KEY_ID: 'GK0123456789',
    S3_SECRET_ACCESS_KEY: '0123456789abcdef0123456789abcdef',
    BAO_ADDR: 'https://127.0.0.1:58200',
  };
  const WORKER = {
    ...SHARED,
    SCANOVA_TMP_DIR: '/run/scanova-tmp',
    BAO_WORKER_ROLE_ID: 'worker-role',
    BAO_WORKER_SECRET_ID: 'worker-secret',
  };
  const API = {
    ...SHARED,
    WEB_SERVICE_TOKEN: 'w'.repeat(48),
    BAO_API_ROLE_ID: 'api-role',
    BAO_API_SECRET_ID: 'api-secret',
  };

  it('gives the worker an inert AI section unless switched on', () => {
    expect(parseWorkerConfig(source(WORKER)).ai).toEqual({ provider: 'none' });
    expect(parseWorkerConfig(source({ ...WORKER, ...AI_ON })).ai.provider).toBe(
      'openai-compatible',
    );
  });

  it('reports a broken AI section as a worker configuration error', () => {
    expect(() =>
      parseWorkerConfig(source({ ...WORKER, AI_PROVIDER: 'openai-compatible' })),
    ).toThrow(/Invalid worker configuration[\s\S]*AI_CHAT_BASE_URL/u);
  });

  it('lets the API advertise AI only when the provider is switched on', () => {
    expect(parseApiConfig(source(API)).features.ai).toBe(false);
    expect(parseApiConfig(source(API)).ai).toEqual({ provider: 'none' });
    const on = parseApiConfig(source({ ...API, ...AI_ON }));
    expect(on.features.ai).toBe(true);
    expect(on.ai.provider).toBe('openai-compatible');
    expect(() => parseApiConfig(source({ ...API, AI_PROVIDER: 'ollama' }))).toThrow(ConfigError);
  });

  it('gives the API the runtime, because it loads and unloads the models for an owner', () => {
    expect(() => parseApiConfig(source({ ...API, AI_PROVIDER: 'openai-compatible' }))).toThrow(
      /Invalid api configuration[\s\S]*AI_CHAT_BASE_URL/u,
    );
  });

  it('keeps 2 GiB free for other services by default when loading the models', () => {
    expect(parseApiConfig(source(API)).aiControl.keepFreeBytes).toBe(2048 * 1024 * 1024);
    expect(parseApiConfig(source({ ...API, AI_KEEP_FREE_MB: '512' })).aiControl.keepFreeBytes).toBe(
      512 * 1024 * 1024,
    );
  });
});
