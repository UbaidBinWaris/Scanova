import { DocumentSchema } from '@scanova/contracts/documents';
import { SuccessSchema } from '@scanova/contracts/common';
import { describe, expect, it, vi } from 'vitest';
import { ApiClient, SERVICE_TOKEN_HEADER } from './client';

const SERVICE_TOKEN = 'service-token-0123456789abcdef0123456789';
const DOCUMENT = {
  id: '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f',
  title: 'Invoice',
  tags: [],
  pageCount: 1,
  sizeBytes: 1024,
  contentSha256: null,
  status: 'READY',
  ocrStatus: 'DONE',
  hasSearchablePdf: true,
  capturedAt: '2026-11-16T12:00:00.000Z',
  createdAt: '2026-11-16T12:00:00.000Z',
  updatedAt: '2026-11-16T12:00:00.000Z',
  deletedAt: null,
  pagesUpdatedAt: null,
};

interface Recorded {
  readonly calls: { url: string; init: RequestInit }[];
  readonly fetch: typeof globalThis.fetch;
}

function recordingFetch(response: Response | (() => Promise<Response>)): Recorded {
  const calls: { url: string; init: RequestInit }[] = [];
  const fetch = vi.fn((url: URL | string, init?: RequestInit) => {
    calls.push({ url: url.toString(), init: init ?? {} });
    return typeof response === 'function' ? response() : Promise.resolve(response.clone());
  });
  return { calls, fetch: fetch as unknown as typeof globalThis.fetch };
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', ...headers },
  });
}

const ok = (data: unknown, nextCursor: string | null = null): Response =>
  json({ data, error: null, meta: { nextCursor } });

function client(fetch: typeof globalThis.fetch): ApiClient {
  return new ApiClient({ apiUrl: 'http://api:3000', serviceToken: SERVICE_TOKEN, fetch });
}

describe('ApiClient', () => {
  it('sends the service token only to /internal, and the bearer token and client address always', async () => {
    const { calls, fetch } = recordingFetch(ok({ success: true }));
    const api = client(fetch);
    await api.request(
      { accessToken: 'a.b.c', clientIp: '203.0.113.7' },
      {
        method: 'POST',
        path: '/api/v1/auth/logout',
        body: { refreshToken: 'x' },
        schema: SuccessSchema,
      },
    );
    await api.request(
      {},
      { method: 'POST', path: '/internal/web/v1/auth/login', body: {}, schema: SuccessSchema },
    );

    const [client1, internal] = calls.map((call) => new Headers(call.init.headers));
    expect(client1?.get(SERVICE_TOKEN_HEADER)).toBeNull();
    expect(client1?.get('authorization')).toBe('Bearer a.b.c');
    expect(client1?.get('x-forwarded-for')).toBe('203.0.113.7');
    expect(client1?.get('content-type')).toBe('application/json');
    expect(calls[0]?.init.body).toBe('{"refreshToken":"x"}');
    expect(internal?.get(SERVICE_TOKEN_HEADER)).toBe(SERVICE_TOKEN);
    expect(internal?.get('authorization')).toBeNull();
    expect(calls[1]?.url).toBe('http://api:3000/internal/web/v1/auth/login');
    expect(calls[0]?.init.redirect).toBe('error');
    expect(calls[0]?.init.cache).toBe('no-store');
  });

  it('builds the query string and validates the answer with the contract schema', async () => {
    const { calls, fetch } = recordingFetch(ok(DOCUMENT, 'next-page'));
    const result = await client(fetch).request(
      { accessToken: 't.t.t' },
      {
        path: '/api/v1/documents/x1',
        query: { limit: 50, q: 'tax 2026', cursor: undefined },
        schema: DocumentSchema,
      },
    );
    expect(calls[0]?.url).toBe('http://api:3000/api/v1/documents/x1?limit=50&q=tax+2026');
    expect(result).toEqual({ ok: true, status: 200, data: DOCUMENT, nextCursor: 'next-page' });
  });

  it('refuses paths outside the contract before sending anything', async () => {
    const { fetch } = recordingFetch(ok({ success: true }));
    const api = client(fetch);
    for (const path of [
      '/api/v1/../internal/admin/v1/users',
      '/api/v1/documents?x=1',
      '/admin',
      'http://evil/api/v1/me',
      '/api/v1/a%2Fb',
    ]) {
      await expect(api.request({}, { path, schema: SuccessSchema })).rejects.toThrow(
        'outside the contract',
      );
    }
    expect(fetch).not.toHaveBeenCalled();
  });

  it('passes the API error code and message through, with Retry-After', async () => {
    const { fetch } = recordingFetch(
      json(
        {
          data: null,
          error: { code: 'RATE_LIMITED', message: 'Too many attempts. Try again shortly.' },
        },
        429,
        { 'retry-after': '42' },
      ),
    );
    expect(await client(fetch).request({}, { path: '/api/v1/me', schema: SuccessSchema })).toEqual({
      ok: false,
      status: 429,
      code: 'RATE_LIMITED',
      message: 'Too many attempts. Try again shortly.',
      retryAfterSeconds: 42,
    });
  });

  it('reports an answer that does not match the contract as BAD_RESPONSE, never as data', async () => {
    const { fetch } = recordingFetch(ok({ ...DOCUMENT, pageCount: -1 }));
    const result = await client(fetch).request(
      {},
      { path: '/api/v1/documents/x1', schema: DocumentSchema },
    );
    expect(result).toMatchObject({ ok: false, code: 'BAD_RESPONSE', status: 502 });

    const html = recordingFetch(new Response('<html>proxy error</html>', { status: 502 }));
    expect(
      await client(html.fetch).request({}, { path: '/api/v1/me', schema: SuccessSchema }),
    ).toMatchObject({
      ok: false,
      code: 'BAD_RESPONSE',
      status: 502,
    });
  });

  it('turns an unreachable API into NETWORK_ERROR', async () => {
    const { fetch } = recordingFetch(() => Promise.reject(new TypeError('fetch failed')));
    expect(
      await client(fetch).request({}, { path: '/api/v1/me', schema: SuccessSchema }),
    ).toMatchObject({
      ok: false,
      status: 503,
      code: 'NETWORK_ERROR',
    });
  });

  it('streams a download untouched, and parses a failed one', async () => {
    const pdf = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d]);
    const stream = recordingFetch(
      new Response(pdf, { status: 200, headers: { 'content-type': 'application/pdf' } }),
    );
    const response = await client(stream.fetch).stream(
      { accessToken: 'a.b.c' },
      '/api/v1/documents/x1/file',
      {
        query: { variant: 'searchable' },
      },
    );
    expect(response).toBeInstanceOf(Response);
    expect(new Uint8Array(await (response as Response).arrayBuffer())).toEqual(pdf);
    expect(stream.calls[0]?.url).toBe(
      'http://api:3000/api/v1/documents/x1/file?variant=searchable',
    );

    const missing = recordingFetch(
      json({ data: null, error: { code: 'NOT_FOUND', message: 'Not found.' } }, 404),
    );
    expect(await client(missing.fetch).stream({}, '/api/v1/documents/x1/file')).toMatchObject({
      ok: false,
      code: 'NOT_FOUND',
      status: 404,
    });
  });
});
