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
  type TestApp,
  type TestUser,
} from './harness.js';

const KEY = 'integration-ai-key-0123456789abcdef0123456789';

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

const chat = new FakeRouter('chat-model');
const embed = new FakeRouter('embed-model');
let api: TestApp;
let owner: TestUser;
let member: TestUser;

beforeAll(async () => {
  const [chatUrl, embedUrl] = await Promise.all([chat.start(), embed.start()]);
  api = await startApp({
    features: { serverOcr: true, ai: true, signup: true },
    ai: {
      provider: 'openai-compatible',
      chat: { baseUrl: chatUrl, model: 'chat-model' },
      embeddings: { baseUrl: embedUrl, model: 'embed-model', dimensions: 256 },
      apiKey: KEY,
    },
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
  await Promise.all([chat.stop(), embed.stop()]);
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
