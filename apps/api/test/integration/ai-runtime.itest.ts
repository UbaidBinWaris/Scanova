import { createServer, type IncomingMessage, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import type { AiRuntimeStatus } from '@scanova/contracts/ai-runtime';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { UsersService } from '../../src/users/users.service.js';
import {
  closeAllApps,
  closeInspectors,
  createAccount,
  createMember,
  mainDb,
  startApp,
  type ApiResponse,
  type TestApp,
  type TestUser,
} from './harness.js';

const KEY = 'integration-ai-key-0123456789abcdef0123456789'; // gitleaks:allow -- not a secret (test fixture, alphabet or config pass-through)

/**
 * A stand-in for one llama.cpp router (llama-server without a model): the
 * three endpoints the API uses, with the same shapes and the API-key check.
 */
class FakeRouter {
  public state: 'unloaded' | 'loading' | 'loaded' = 'unloaded';
  public readonly calls: string[] = [];
  private server: Server | null = null;

  public constructor(private readonly model: string) {}

  public async start(): Promise<string> {
    this.server = createServer((request, response) => {
      void this.handle(request).then(([status, body]) => {
        response.writeHead(status, { 'content-type': 'application/json' });
        response.end(JSON.stringify(body));
      });
    });
    await new Promise<void>((resolve) => this.server?.listen(0, '127.0.0.1', resolve));
    return `http://127.0.0.1:${(this.server.address() as AddressInfo).port}/v1`;
  }

  public async stop(): Promise<void> {
    await new Promise<void>((resolve) => this.server?.close(() => resolve()));
  }

  private async handle(request: IncomingMessage): Promise<[number, unknown]> {
    if (request.headers.authorization !== `Bearer ${KEY}`) {
      return [401, { error: { message: 'Invalid API Key' } }];
    }
    let raw = '';
    for await (const chunk of request) {
      raw += String(chunk);
    }
    this.calls.push(`${request.method ?? ''} ${request.url ?? ''} ${raw}`.trim());
    if (request.method === 'GET' && request.url === '/models') {
      return [
        200,
        {
          data: [
            {
              id: this.model,
              path: '/models/secret/path.gguf', // never passed on by the API
              status: { value: this.state, args: ['llama-server'] },
              memory: {
                status: 'measured',
                min: { devices: [{ kind: 'host', model: 1_000_000, context: 0, compute: 0 }] },
              },
            },
          ],
        },
      ];
    }
    if (request.method === 'POST' && request.url === '/models/load') {
      this.state = 'loading';
      return [200, { success: true }];
    }
    if (request.method === 'POST' && request.url === '/models/unload') {
      this.state = 'unloaded';
      return [200, { success: true }];
    }
    return [404, { error: { message: 'not found' } }];
  }
}

/**
 * A stand-in for the image AI runtime (apps/ai-image): the router endpoints
 * plus page clean-up, which answers with "CLEAN:" and the page it was sent.
 */
class FakeImageRuntime {
  public state: 'unloaded' | 'loading' | 'loaded' = 'unloaded';
  public readonly pages: Buffer[] = [];
  private server: Server | null = null;

  public async start(): Promise<string> {
    this.server = createServer((request, response) => {
      void (async () => {
        const chunks: Buffer[] = [];
        for await (const chunk of request) {
          chunks.push(chunk as Buffer);
        }
        const body = Buffer.concat(chunks);
        if (request.headers.authorization !== `Bearer ${KEY}`) {
          response.writeHead(401).end();
          return;
        }
        const json = (status: number, value: unknown): void => {
          response
            .writeHead(status, { 'content-type': 'application/json' })
            .end(JSON.stringify(value));
        };
        if (request.method === 'GET' && request.url === '/models') {
          // A measured size, as the text fakes report: test machines have little RAM to spare.
          const memory = { status: 'measured', min: { devices: [{ model: 1_000_000 }] } };
          json(200, {
            data: [{ id: 'gcdrnet', status: { value: this.state, failed: false }, memory }],
          });
        } else if (request.method === 'POST' && request.url === '/models/load') {
          this.state = 'loaded';
          json(200, { success: true });
        } else if (request.method === 'POST' && request.url === '/models/unload') {
          this.state = 'unloaded';
          json(200, { success: true });
        } else if (request.method === 'POST' && request.url === '/v1/images/enhance') {
          if (this.state !== 'loaded') {
            json(409, { error: 'not_loaded' });
            return;
          }
          this.pages.push(body);
          const cleaned = Buffer.concat([Buffer.from('CLEAN:'), body]);
          response
            .writeHead(200, { 'content-type': 'image/jpeg', 'content-length': cleaned.length })
            .end(cleaned);
        } else {
          json(404, { error: 'not_found' });
        }
      })();
    });
    await new Promise<void>((resolve) => this.server?.listen(0, '127.0.0.1', resolve));
    return `http://127.0.0.1:${(this.server.address() as AddressInfo).port}`;
  }

  public async stop(): Promise<void> {
    await new Promise<void>((resolve) => this.server?.close(() => resolve()));
  }
}

const chat = new FakeRouter('chat-model');
const embed = new FakeRouter('embed-model');
const image = new FakeImageRuntime();
let api: TestApp;
let owner: TestUser;
let member: TestUser;

beforeAll(async () => {
  const [chatUrl, embedUrl, imageUrl] = await Promise.all([
    chat.start(),
    embed.start(),
    image.start(),
  ]);
  api = await startApp({
    features: { serverOcr: true, ai: true, signup: true },
    ai: {
      provider: 'openai-compatible',
      chat: { baseUrl: chatUrl, model: 'chat-model' },
      embeddings: { baseUrl: embedUrl, model: 'embed-model', dimensions: 256 },
      apiKey: KEY,
    },
    aiImage: { provider: 'gcdrnet', baseUrl: imageUrl, model: 'gcdrnet', apiKey: KEY },
    // Integration machines have little RAM to spare; this test is not about the memory check.
    aiControl: { keepFreeBytes: 0 },
  });
  owner = await createMember(api);
  member = await createMember(api);
  const users = api.app.get(UsersService);
  const user = await users.findByPublicId(owner.id);
  if (user === null) {
    throw new Error('owner not found');
  }
  await users.setOwner(user, true);
});

afterAll(async () => {
  await closeAllApps();
  await closeInspectors();
  await Promise.all([chat.stop(), embed.stop(), image.stop()]);
});

describe('server AI switch', () => {
  it('refuses members who are not owners', async () => {
    for (const request of [
      member.client.get('/api/v1/ai/runtime'),
      member.client.put('/api/v1/ai/runtime', { on: true }),
      member.client.post('/api/v1/ai/runtime/heartbeat', {}),
    ]) {
      const response = await request;
      expect(response.status).toBe(403);
      expect(response.body?.error?.code).toBe('FORBIDDEN');
    }
    expect(chat.calls.some((call) => call.startsWith('POST'))).toBe(false);
  });

  it('shows an owner that it is off, without file paths', async () => {
    const response = await owner.client.get<AiRuntimeStatus>('/api/v1/ai/runtime');
    expect(response.status).toBe(200);
    expect(response.body?.data).toMatchObject({
      state: 'OFF',
      on: false,
      autoOffAt: null,
      autoOffMinutes: 15,
      heartbeatSeconds: 60,
      models: [
        { role: 'chat', name: 'chat-model', state: 'OFF' },
        { role: 'embed', name: 'embed-model', state: 'OFF' },
      ],
    });
    expect(JSON.stringify(response.body)).not.toContain('/models/secret');
  });

  it('loads both models for an owner, with the API key, and reports READY once loaded', async () => {
    const on = await owner.client.put<AiRuntimeStatus>('/api/v1/ai/runtime', { on: true });
    expect(on.status).toBe(200);
    expect(on.body?.data).toMatchObject({ state: 'LOADING', on: true });
    expect(chat.calls).toContain('POST /models/load {"model":"chat-model"}');
    expect(embed.calls).toContain('POST /models/load {"model":"embed-model"}');

    chat.state = 'loaded';
    embed.state = 'loaded';
    const ready = await owner.client.post<AiRuntimeStatus>('/api/v1/ai/runtime/heartbeat', {});
    expect(ready.status).toBe(200);
    expect(ready.body?.data.state).toBe('READY');
    expect(ready.body?.data.autoOffAt).not.toBeNull();
  });

  it('unloads both when the owner turns it off, and records both actions', async () => {
    const off = await owner.client.put<AiRuntimeStatus>('/api/v1/ai/runtime', { on: false });
    expect(off.body?.data).toMatchObject({ state: 'OFF', on: false, autoOffAt: null });
    expect(chat.calls).toContain('POST /models/unload {"model":"chat-model"}');
    expect(embed.calls).toContain('POST /models/unload {"model":"embed-model"}');

    const actions = await mainDb().auditEvent.findMany({
      where: { action: { startsWith: 'AI_RUNTIME_' } },
      orderBy: { createdAt: 'asc' },
      select: { action: true },
    });
    expect(actions.map((event) => event.action)).toEqual(['AI_RUNTIME_ON', 'AI_RUNTIME_OFF']);
  });

  it('never writes the runtime key to the logs', () => {
    expect(api.logs.join('\n')).not.toContain(KEY);
  });

  it('grants owner access to members only', async () => {
    const admin = await createAccount(api, 'ADMIN');
    const users = api.app.get(UsersService);
    const user = await users.findByPublicId(admin.id);
    if (user === null) {
      throw new Error('admin not found');
    }
    await expect(users.setOwner(user, true)).rejects.toMatchObject({ code: 'VALIDATION_FAILED' });
  });
});

describe('page clean-up switch', () => {
  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4]);
  const enhance = (
    client: TestUser['client'],
    body: Buffer,
    contentType = 'image/jpeg',
  ): Promise<ApiResponse> =>
    client.request('POST', '/api/v1/ai/images/enhance', {
      body,
      headers: { 'content-type': contentType },
    });

  it('is a switch of its own: the text models stay as they are', async () => {
    const off = await owner.client.get<AiRuntimeStatus>('/api/v1/ai/runtime?group=image');
    expect(off.body!.data).toMatchObject({ group: 'image', state: 'OFF', on: false });
    expect(off.body!.data.models).toEqual([{ role: 'image', name: 'gcdrnet', state: 'OFF' }]);
    const textBefore = (await owner.client.get<AiRuntimeStatus>('/api/v1/ai/runtime')).body!.data;
    expect(textBefore.group).toBe('text');

    const on = await owner.client.put<AiRuntimeStatus>('/api/v1/ai/runtime?group=image', {
      on: true,
    });
    expect(on.status).toBe(200);
    expect(on.body!.data).toMatchObject({ group: 'image', state: 'READY', on: true });
    expect((await owner.client.get<AiRuntimeStatus>('/api/v1/ai/runtime')).body!.data.on).toBe(
      textBefore.on,
    );
    expect((await owner.client.get('/api/v1/ai/runtime?group=video')).status).toBe(400);
  });

  it('cleans a page for the owner, passing it through untouched and unstored', async () => {
    const cleaned = await enhance(owner.client, jpeg);
    expect(cleaned.status).toBe(200);
    expect(cleaned.headers.get('content-type')).toBe('image/jpeg');
    expect(cleaned.headers.get('cache-control')).toBe('no-store');
    expect(cleaned.raw.equals(Buffer.concat([Buffer.from('CLEAN:'), jpeg]))).toBe(true);
    expect(image.pages.at(-1)?.equals(jpeg)).toBe(true);
  });

  it('refuses members, other media types, oversized pages, and pages while it is off', async () => {
    const before = image.pages.length;
    const refused = await enhance(member.client, jpeg);
    expect([refused.status, refused.body?.error?.code]).toEqual([403, 'FORBIDDEN']);
    expect((await enhance(owner.client, jpeg, 'image/png')).body?.error?.code).toBe(
      'UNSUPPORTED_MEDIA_TYPE',
    );
    const huge = await enhance(owner.client, Buffer.alloc(16 * 1024 * 1024 + 1));
    expect(huge.body?.error?.code).toBe('FILE_TOO_LARGE');

    await owner.client.put('/api/v1/ai/runtime?group=image', { on: false });
    expect(image.state).toBe('unloaded');
    const off = await enhance(owner.client, jpeg);
    expect([off.status, off.body?.error?.code]).toEqual([409, 'AI_NOT_READY']);
    expect(image.pages.length).toBe(before);
  });
});
