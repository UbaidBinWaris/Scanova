import { describe, expect, it } from 'vitest';
import { clientIpFrom } from '@/server/client-ip';
import { buildContentSecurityPolicy } from './csp';
import { isSafeMethod, isSameOriginRequest } from './csrf';
import { classifyRequest } from './routing';
import { originFor, safeReturnPath, surfaceForHost, userTypeFits } from './surface';

const ORIGINS = {
  siteOrigin: 'https://scanova.example.org',
  adminOrigin: 'https://admin.scanova.example.org',
};

function headers(values: Record<string, string>): { get(name: string): string | null } {
  return { get: (name) => values[name.toLowerCase()] ?? null };
}

describe('surfaces', () => {
  it('recognises the admin host exactly; every other host is the member portal', () => {
    expect(surfaceForHost('admin.scanova.example.org', ORIGINS)).toBe('admin');
    expect(surfaceForHost('ADMIN.scanova.example.org', ORIGINS)).toBe('admin');
    expect(surfaceForHost('scanova.example.org', ORIGINS)).toBe('site');
    expect(surfaceForHost('admin.scanova.example.org.evil.test', ORIGINS)).toBe('site');
    expect(surfaceForHost(null, ORIGINS)).toBe('site');
    expect(surfaceForHost('admin.scanova.example.org', { ...ORIGINS, adminOrigin: null })).toBe(
      'site',
    );
    expect(originFor('admin', ORIGINS)).toBe(ORIGINS.adminOrigin);
    expect(() => originFor('admin', { ...ORIGINS, adminOrigin: null })).toThrow();
  });

  it('lets admins use only the console and members only the portal', () => {
    expect(userTypeFits('ADMIN', 'admin')).toBe(true);
    expect(userTypeFits('MEMBER', 'admin')).toBe(false);
    expect(userTypeFits('MEMBER', 'site')).toBe(true);
    expect(userTypeFits('ADMIN', 'site')).toBe(false);
  });

  it('follows a return path only inside the same surface', () => {
    expect(safeReturnPath('/app/documents/6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f', 'site')).toBe(
      '/app/documents/6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f',
    );
    expect(safeReturnPath('/admin/keys', 'admin')).toBe('/admin/keys');
    for (const value of [
      'https://evil.test/app',
      '//evil.test/app',
      '/app//evil.test',
      '/app/../admin',
      '/admin/users',
      '/app/sign-in',
      '/app/session/expired',
      '/application',
      '/app?x=1',
      '\\\\evil.test',
      42,
      undefined,
    ]) {
      expect(safeReturnPath(value, 'site')).toBeNull();
    }
  });
});

describe('request routing', () => {
  it('answers each surface only under its own root', () => {
    expect(classifyRequest('site', '/app')).toEqual({ kind: 'protected-page' });
    expect(classifyRequest('site', '/app/documents/x')).toEqual({ kind: 'protected-page' });
    expect(classifyRequest('site', '/app/sign-in')).toEqual({ kind: 'public-page' });
    expect(classifyRequest('site', '/app/session/expired')).toEqual({ kind: 'session-endpoint' });
    expect(classifyRequest('site', '/app/api/documents')).toEqual({ kind: 'protected-data' });
    expect(classifyRequest('site', '/app/documents/x/file')).toEqual({ kind: 'protected-data' });
    expect(classifyRequest('site', '/app/documents/x/pages/y/original')).toEqual({
      kind: 'protected-data',
    });
    expect(classifyRequest('site', '/admin')).toEqual({ kind: 'not-found' });
    expect(classifyRequest('site', '/admin/users')).toEqual({ kind: 'not-found' });
    expect(classifyRequest('site', '/')).toEqual({ kind: 'not-found' });
    expect(classifyRequest('site', '/apps')).toEqual({ kind: 'not-found' });

    expect(classifyRequest('admin', '/')).toEqual({ kind: 'redirect', location: '/admin' });
    expect(classifyRequest('admin', '/admin/users')).toEqual({ kind: 'protected-page' });
    expect(classifyRequest('admin', '/admin/sign-in')).toEqual({ kind: 'public-page' });
    expect(classifyRequest('admin', '/admin/api/users')).toEqual({ kind: 'protected-data' });
    expect(classifyRequest('admin', '/app')).toEqual({ kind: 'not-found' });
    expect(classifyRequest('admin', '/app/documents/x/file')).toEqual({ kind: 'not-found' });
    expect(classifyRequest('admin', '/healthz')).toEqual({ kind: 'health' });
  });
});

describe('CSRF', () => {
  it('accepts only same-origin state changes', () => {
    expect(isSafeMethod('GET')).toBe(true);
    expect(isSafeMethod('post')).toBe(false);
    const site = ORIGINS.siteOrigin;
    expect(
      isSameOriginRequest(headers({ origin: site, 'sec-fetch-site': 'same-origin' }), site),
    ).toBe(true);
    expect(isSameOriginRequest(headers({ origin: site }), site)).toBe(true);
    expect(isSameOriginRequest(headers({}), site)).toBe(false);
    expect(isSameOriginRequest(headers({ origin: 'null' }), site)).toBe(false);
    expect(isSameOriginRequest(headers({ origin: ORIGINS.adminOrigin }), site)).toBe(false);
    expect(isSameOriginRequest(headers({ origin: `${site}.evil.test` }), site)).toBe(false);
    expect(
      isSameOriginRequest(headers({ origin: site, 'sec-fetch-site': 'same-site' }), site),
    ).toBe(false);
    expect(
      isSameOriginRequest(headers({ origin: site, 'sec-fetch-site': 'cross-site' }), site),
    ).toBe(false);
  });
});

describe('Content-Security-Policy', () => {
  it('is nonce-based, without unsafe-inline, and forbids framing', () => {
    const policy = buildContentSecurityPolicy({ nonce: 'n0nce', development: false, https: true });
    expect(policy).toContain(`script-src 'self' 'nonce-n0nce' 'strict-dynamic' 'wasm-unsafe-eval'`);
    expect(policy).toContain(`style-src 'self' 'nonce-n0nce'`);
    expect(policy).toContain(`frame-ancestors 'none'`);
    expect(policy).toContain(`object-src 'none'`);
    expect(policy).toContain(`base-uri 'none'`);
    expect(policy).toContain('upgrade-insecure-requests');
    expect(policy).not.toContain('unsafe-inline');
    expect(policy).not.toContain(`'unsafe-eval'`);
  });

  it('allows eval only in development', () => {
    const policy = buildContentSecurityPolicy({ nonce: 'n', development: true, https: false });
    expect(policy).toContain(`'unsafe-eval'`);
    expect(policy).not.toContain('upgrade-insecure-requests');
  });
});

describe('client address', () => {
  it('takes the address the edge wrote, and only a valid one', () => {
    expect(clientIpFrom('203.0.113.7')).toBe('203.0.113.7');
    expect(clientIpFrom('198.51.100.1, 203.0.113.7')).toBe('203.0.113.7');
    expect(clientIpFrom('2001:db8::1')).toBe('2001:db8::1');
    expect(clientIpFrom('not-an-ip')).toBeNull();
    expect(clientIpFrom('203.0.113.7, <script>')).toBeNull();
    expect(clientIpFrom(null)).toBeNull();
  });
});
