import { z } from 'zod';
import type { EnvSource } from './env-source.js';
import { envInt, envSecret, envString, parseEnvWith } from './parse.js';
import type { AiConfig } from './sections.js';

/**
 * AI runtime variables (docs/ARCHITECTURE.md §11). Off by default: with
 * `AI_PROVIDER=none` every other AI variable is ignored, so a template value
 * can never switch anything on.
 */

const providerShape = {
  AI_PROVIDER: z.enum(['none', 'openai-compatible']).default('none'),
};

/** An http(s) URL without credentials, query or fragment; trailing slashes are dropped. */
const baseUrl = z
  .url({ protocol: /^https?$/u })
  .refine((value) => {
    const url = new URL(value);
    return url.username === '' && url.password === '' && url.search === '' && url.hash === '';
  }, 'must be an http(s) URL without credentials, query or fragment')
  .transform((value) => value.replace(/\/+$/u, ''));

/** Model names travel in JSON bodies and log lines: plain identifiers only. */
const modelName = envString
  .max(128)
  .regex(/^[A-Za-z0-9][A-Za-z0-9._:/-]*$/u, 'must be a plain model name like "qwen3.5-4b"');

const runtimeShape = {
  AI_CHAT_BASE_URL: baseUrl,
  AI_CHAT_MODEL: modelName,
  AI_EMBED_BASE_URL: baseUrl,
  AI_EMBED_MODEL: modelName,
  AI_EMBED_DIMENSIONS: envInt(32, 8192),
  // Sent as a bearer token and written to the runtime's key file, one per line.
  AI_API_KEY: envSecret(32).refine(
    (value) => /^[\x21-\x7e]+$/u.test(value),
    'must be printable ASCII without spaces',
  ),
};

export function parseAiConfig(scope: string, source: EnvSource): AiConfig {
  const { AI_PROVIDER } = parseEnvWith(scope, providerShape, source);
  if (AI_PROVIDER === 'none') {
    return { provider: 'none' };
  }
  const env = parseEnvWith(scope, runtimeShape, source);
  return {
    provider: 'openai-compatible',
    chat: { baseUrl: env.AI_CHAT_BASE_URL, model: env.AI_CHAT_MODEL },
    embeddings: {
      baseUrl: env.AI_EMBED_BASE_URL,
      model: env.AI_EMBED_MODEL,
      dimensions: env.AI_EMBED_DIMENSIONS,
    },
    apiKey: env.AI_API_KEY,
  };
}
