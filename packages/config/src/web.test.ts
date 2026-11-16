import { describe, expect, it } from 'vitest';
import type { EnvSource } from './env-source.js';
import { ConfigError } from './errors.js';
import { parseWebConfig } from './web.js';

const SECRETS = {
  WEB_SERVICE_TOKEN: 's'.repeat(64),
  WEB_COOKIE_SECRET: 'c'.repeat(64),
};

function source(values: Record<string, string | undefined>): EnvSource {
  return { values, baseDir: '/srv/scanova' };
}

function issuesOf(values: Record<string, string | undefined>): string[] {
  try {
    parseWebConfig(source(values));
  } catch (error) {
    expect(error).toBeInstanceOf(ConfigError);
    return (error as ConfigError).issues.map((issue) => `${issue.variable}: ${issue.message}`);
  }
  throw new Error('expected a ConfigError');
}

describe('web config', () => {
  it('serves the member portal on the site host and the console on the admin host in public mode', () => {
    const config = parseWebConfig(
      source({
        ...SECRETS,
        SCANOVA_TLS_MODE: 'public',
        SCANOVA_PUBLIC_URL: 'https://api.scanova.example.org',
        SCANOVA_SITE_HOST: 'scanova.example.org',
        SCANOVA_ADMIN_HOST: 'admin.scanova.example.org',
        WEB_API_URL: 'http://api:3000/',
      }),
    );
    expect(config).toEqual({
      runtime: { nodeEnv: 'production', logLevel: 'info' },
      apiUrl: 'http://api:3000',
      serviceToken: SECRETS.WEB_SERVICE_TOKEN,
      cookieSecret: SECRETS.WEB_COOKIE_SECRET,
      siteOrigin: 'https://scanova.example.org',
      adminOrigin: 'https://admin.scanova.example.org',
    });
  });

  it('serves only the member portal, on the public URL, without an admin host', () => {
    const config = parseWebConfig(
      source({ ...SECRETS, SCANOVA_PUBLIC_URL: 'https://192.168.1.100:8443/' }),
    );
    expect(config.siteOrigin).toBe('https://192.168.1.100:8443');
    expect(config.adminOrigin).toBeNull();
    expect(config.apiUrl).toBe('http://127.0.0.1:3000');
  });

  it('takes explicit origins for development, plain http only on loopback hosts', () => {
    const config = parseWebConfig(
      source({
        ...SECRETS,
        WEB_SITE_ORIGIN: 'http://localhost:3001',
        WEB_ADMIN_ORIGIN: 'http://admin.localhost:3001',
      }),
    );
    expect(config.siteOrigin).toBe('http://localhost:3001');
    expect(config.adminOrigin).toBe('http://admin.localhost:3001');

    expect(issuesOf({ ...SECRETS, WEB_SITE_ORIGIN: 'http://scanova.example.org' })).toEqual([
      'WEB_SITE_ORIGIN: must use https (plain http only for localhost)',
    ]);
  });

  it('requires the secrets and an origin, and names variables but never values', () => {
    const issues = issuesOf({ WEB_COOKIE_SECRET: 'short' });
    expect(issues).toEqual(
      expect.arrayContaining([
        'WEB_SERVICE_TOKEN: is required but missing or empty',
        'WEB_COOKIE_SECRET: must be at least 32 characters',
      ]),
    );
    expect(issues.join('\n')).not.toContain('short');

    expect(issuesOf({ ...SECRETS, SCANOVA_TLS_MODE: 'public' })).toEqual([
      'WEB_SITE_ORIGIN: is required: set it, SCANOVA_SITE_HOST (public mode) or SCANOVA_PUBLIC_URL',
      'WEB_ADMIN_ORIGIN: is required: set it or SCANOVA_ADMIN_HOST (public mode)',
    ]);
  });

  it('refuses one host for both the member portal and the admin console', () => {
    expect(
      issuesOf({
        ...SECRETS,
        WEB_SITE_ORIGIN: 'https://scanova.example.org',
        WEB_ADMIN_ORIGIN: 'https://scanova.example.org/admin',
      }),
    ).toEqual(['WEB_ADMIN_ORIGIN: must be a different host from the member portal']);
  });
});
