import { z } from 'zod';
import { readEnvSource, type EnvSource, type EnvSourceOptions } from './env-source.js';
import { ConfigError } from './errors.js';
import { runtimeShape, toRuntime } from './fragments.js';
import { envSecret, envString, parseEnvWith } from './parse.js';
import type { WebConfig } from './sections.js';

/** Host names that may be served over plain HTTP: a development machine only. */
const LOOPBACK_HOST = /^(localhost|127\.0\.0\.1|\[::1\]|[a-z0-9-]+\.localhost)$/u;

const optionalString = envString.optional();

const webShape = {
  ...runtimeShape,
  /** The API on the private network (Compose pins http://api:3000). Never the public edge. */
  WEB_API_URL: z.url().default('http://127.0.0.1:3000'),
  WEB_SERVICE_TOKEN: envSecret(32),
  /** Key material of the encrypted session cookie. */
  WEB_COOKIE_SECRET: envSecret(32),
  /** Explicit origins (development). Empty: derived from the edge settings below. */
  WEB_SITE_ORIGIN: optionalString,
  WEB_ADMIN_ORIGIN: optionalString,
  SCANOVA_TLS_MODE: z.enum(['internal', 'acme', 'public']).default('internal'),
  SCANOVA_PUBLIC_URL: optionalString,
  SCANOVA_SITE_HOST: optionalString,
  SCANOVA_ADMIN_HOST: optionalString,
};

/**
 * Configuration of the Next.js web app (docs/ARCHITECTURE.md §12).
 *
 * The member portal is served on the download site's host in `public` TLS
 * mode, otherwise on the one host of `SCANOVA_PUBLIC_URL`. The admin console
 * exists only where it has a host of its own (`SCANOVA_ADMIN_HOST`, public mode).
 */
export function parseWebConfig(source: EnvSource): WebConfig {
  const env = parseEnvWith('web', webShape, source);
  const issues: { variable: string; message: string }[] = [];

  const siteOrigin = resolveOrigin(
    'WEB_SITE_ORIGIN',
    env.WEB_SITE_ORIGIN ??
      (env.SCANOVA_TLS_MODE === 'public'
        ? hostOrigin(env.SCANOVA_SITE_HOST)
        : env.SCANOVA_PUBLIC_URL),
    issues,
  );
  const adminOrigin =
    env.WEB_ADMIN_ORIGIN !== undefined || env.SCANOVA_TLS_MODE === 'public'
      ? resolveOrigin(
          'WEB_ADMIN_ORIGIN',
          env.WEB_ADMIN_ORIGIN ?? hostOrigin(env.SCANOVA_ADMIN_HOST),
          issues,
        )
      : null;

  if (siteOrigin !== null && adminOrigin !== null && siteOrigin === adminOrigin) {
    issues.push({
      variable: 'WEB_ADMIN_ORIGIN',
      message: 'must be a different host from the member portal',
    });
  }
  if (issues.length > 0 || siteOrigin === null) {
    throw new ConfigError('web', issues);
  }

  return {
    runtime: toRuntime(env),
    apiUrl: env.WEB_API_URL.replace(/\/+$/u, ''),
    serviceToken: env.WEB_SERVICE_TOKEN,
    cookieSecret: env.WEB_COOKIE_SECRET,
    siteOrigin,
    adminOrigin,
  };
}

export function loadWebConfig(options?: EnvSourceOptions): WebConfig {
  return parseWebConfig(readEnvSource(options));
}

function hostOrigin(host: string | undefined): string | undefined {
  return host === undefined ? undefined : `https://${host}`;
}

/** An `http(s)://host[:port]` origin; plain HTTP only for loopback development hosts. */
function resolveOrigin(
  variable: string,
  value: string | undefined,
  issues: { variable: string; message: string }[],
): string | null {
  if (value === undefined) {
    issues.push({
      variable,
      message:
        variable === 'WEB_SITE_ORIGIN'
          ? 'is required: set it, SCANOVA_SITE_HOST (public mode) or SCANOVA_PUBLIC_URL'
          : 'is required: set it or SCANOVA_ADMIN_HOST (public mode)',
    });
    return null;
  }
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    issues.push({ variable, message: 'must be an absolute URL such as https://host' });
    return null;
  }
  const secure = url.protocol === 'https:';
  if (!secure && !(url.protocol === 'http:' && LOOPBACK_HOST.test(url.hostname))) {
    issues.push({ variable, message: 'must use https (plain http only for localhost)' });
    return null;
  }
  return url.origin;
}
