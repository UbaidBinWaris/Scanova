import { AppLogger } from '@scanova/config/logger';
import type { ApiConfig } from '@scanova/config/sections';
import { beforeEach, describe, expect, it } from 'vitest';
import type { AuditService } from '../audit/audit.service.js';
import { AppError } from '../common/app-error.js';
import type { AuthContext } from '../common/auth-context.js';
import type { UsersService } from '../users/users.service.js';
import { AiRuntimeService, type ControlledModel } from './ai-runtime.service.js';
import { RuntimeUnavailableError, type ModelRuntime, type RuntimeModel } from './model-runtime.js';

const GIB = 1024 * 1024 * 1024;
const KEEP_FREE = 2 * GIB;

/** A llama.cpp router stand-in: one model, loads and unloads when asked. */
class FakeRouter implements ModelRuntime {
  public readonly calls: string[] = [];
  public down = false;

  public constructor(public model: RuntimeModel | null) {}

  public list(): Promise<RuntimeModel[]> {
    if (this.down) {
      return Promise.reject(new RuntimeUnavailableError('down'));
    }
    return Promise.resolve(this.model === null ? [] : [this.model]);
  }

  public load(name: string): Promise<void> {
    this.calls.push(`load ${name}`);
    if (this.model !== null) {
      this.model = { ...this.model, state: 'loading' };
    }
    return Promise.resolve();
  }

  public unload(name: string): Promise<void> {
    this.calls.push(`unload ${name}`);
    if (this.model !== null) {
      this.model = { ...this.model, state: 'unloaded' };
    }
    return Promise.resolve();
  }

  public finishLoading(): void {
    if (this.model !== null) {
      this.model = { ...this.model, state: 'loaded' };
    }
  }
}

const OWNER: AuthContext = {
  userId: 1,
  userPublicId: 'owner',
  userType: 'MEMBER',
  client: 'mobile',
  sessionId: 1,
  sessionPublicId: 's1',
};
const MEMBER: AuthContext = { ...OWNER, userId: 2, userPublicId: 'member' };

function model(
  name: string,
  state: RuntimeModel['state'],
  neededBytes: number | null = null,
): RuntimeModel {
  return { name, state, neededBytes };
}

describe('AiRuntimeService', () => {
  let now: Date;
  let chat: FakeRouter;
  let embed: FakeRouter;
  let available: number | null;
  let audits: string[];
  let provider: 'none' | 'openai-compatible';

  function service(): AiRuntimeService {
    const config = {
      ai:
        provider === 'none'
          ? { provider: 'none' }
          : { provider, chat: { model: 'chat-m' }, embeddings: { model: 'embed-m' } },
      aiControl: { keepFreeBytes: KEEP_FREE },
    } as unknown as ApiConfig;
    const models: ControlledModel[] =
      provider === 'none'
        ? []
        : [
            { role: 'chat', name: 'chat-m', runtime: chat },
            { role: 'embed', name: 'embed-m', runtime: embed },
          ];
    const users = {
      getByPublicId: (id: string) =>
        Promise.resolve({
          id: id === 'owner' ? 1 : 2,
          isOwner: id === 'owner',
          userType: 'MEMBER',
        }),
    } as unknown as UsersService;
    const audit = {
      record: (action: string) => {
        audits.push(action);
        return Promise.resolve();
      },
    } as unknown as AuditService;
    return new AiRuntimeService(
      config,
      () => now,
      models,
      { availableBytes: () => Promise.resolve(available) },
      users,
      audit,
      new AppLogger('fatal', 'test', () => undefined),
    );
  }

  beforeEach(() => {
    now = new Date('2026-11-16T10:00:00.000Z');
    chat = new FakeRouter(model('chat-m', 'unloaded', 3 * GIB));
    embed = new FakeRouter(model('embed-m', 'unloaded', 1 * GIB));
    available = 12 * GIB;
    audits = [];
    provider = 'openai-compatible';
  });

  it('answers only owners', async () => {
    const ai = service();
    await expect(ai.status(MEMBER)).rejects.toMatchObject({ code: 'FORBIDDEN' });
    await expect(ai.setOn(MEMBER, true)).rejects.toMatchObject({ code: 'FORBIDDEN' });
    await expect(ai.heartbeat(MEMBER)).rejects.toMatchObject({ code: 'FORBIDDEN' });
    expect(chat.calls).toEqual([]);
  });

  it('is off and uses no memory until an owner turns it on', async () => {
    const status = await service().status(OWNER);
    expect(status).toMatchObject({ state: 'OFF', on: false, autoOffAt: null });
    expect(status.models.map((entry) => entry.state)).toEqual(['OFF', 'OFF']);
  });

  it('loads both models, reports LOADING, then READY', async () => {
    const ai = service();
    const loading = await ai.setOn(OWNER, true);
    expect(chat.calls).toEqual(['load chat-m']);
    expect(embed.calls).toEqual(['load embed-m']);
    expect(loading).toMatchObject({ state: 'LOADING', on: true });
    expect(loading.autoOffAt).toBe('2026-11-16T10:15:00.000Z');
    expect(audits).toEqual(['AI_RUNTIME_ON']);

    chat.finishLoading();
    expect((await ai.status(OWNER)).state).toBe('LOADING'); // READY only when both are
    embed.finishLoading();
    expect((await ai.status(OWNER)).state).toBe('READY');
  });

  it('refuses to load when the server would have less than AI_KEEP_FREE_MB left', async () => {
    // Needs 2 GiB free + (3 + 1) GiB measured × 1.15 = 6.6 GiB.
    available = 6 * GIB;
    const error: unknown = await service()
      .setOn(OWNER, true)
      .catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(AppError);
    expect(error).toMatchObject({ code: 'NOT_ENOUGH_MEMORY' });
    const details = (error as AppError).details as { availableBytes: number; neededBytes: number };
    expect(details.availableBytes).toBe(6 * GIB);
    expect(details.neededBytes).toBe(KEEP_FREE + Math.ceil(3 * GIB * 1.15) + Math.ceil(GIB * 1.15));
    expect(chat.calls).toEqual([]);
    expect(audits).toEqual([]);
  });

  it('assumes the container cap for a model the router has not measured', async () => {
    chat.model = model('chat-m', 'unloaded', null);
    const status = await service().status(OWNER);
    expect(status.memory.neededBytes).toBe(KEEP_FREE + 4608 * 1024 * 1024 + Math.ceil(GIB * 1.15));
  });

  it('turns off 15 minutes after the last check-in, and not before', async () => {
    const ai = service();
    await ai.setOn(OWNER, true);
    chat.finishLoading();
    embed.finishLoading();

    now = new Date('2026-11-16T10:10:00.000Z');
    expect((await ai.heartbeat(OWNER)).autoOffAt).toBe('2026-11-16T10:25:00.000Z');

    now = new Date('2026-11-16T10:24:59.000Z');
    await ai.tick();
    expect(chat.calls).toEqual(['load chat-m']);

    now = new Date('2026-11-16T10:25:00.000Z');
    await ai.tick();
    expect(chat.calls).toEqual(['load chat-m', 'unload chat-m']);
    expect(embed.calls).toEqual(['load embed-m', 'unload embed-m']);
    expect(audits).toEqual(['AI_RUNTIME_ON', 'AI_RUNTIME_AUTO_OFF']);
    expect(await ai.status(OWNER)).toMatchObject({ state: 'OFF', on: false, autoOffAt: null });
  });

  it('does not move the turn-off time while it is off', async () => {
    const status = await service().heartbeat(OWNER);
    expect(status).toMatchObject({ on: false, autoOffAt: null });
  });

  it('unloads at once when turned off', async () => {
    const ai = service();
    await ai.setOn(OWNER, true);
    chat.finishLoading();
    embed.finishLoading();
    const status = await ai.setOn(OWNER, false);
    expect(chat.calls.at(-1)).toBe('unload chat-m');
    expect(embed.calls.at(-1)).toBe('unload embed-m');
    expect(status).toMatchObject({ state: 'OFF', on: false });
    expect(audits).toEqual(['AI_RUNTIME_ON', 'AI_RUNTIME_OFF']);
  });

  it('says NOT_INSTALLED and loads nothing when a model was never fetched', async () => {
    embed.model = null;
    const status = await service().setOn(OWNER, true);
    expect(status).toMatchObject({ state: 'NOT_INSTALLED', on: false });
    expect(chat.calls).toEqual([]);
  });

  it('says UNAVAILABLE when a runtime does not answer', async () => {
    chat.down = true;
    expect((await service().status(OWNER)).state).toBe('UNAVAILABLE');
  });

  it('says FAILED when a model did not start', async () => {
    const ai = service();
    await ai.setOn(OWNER, true);
    chat.model = model('chat-m', 'failed');
    expect((await ai.status(OWNER)).state).toBe('FAILED');
  });

  it('says DISABLED when AI is off in the configuration', async () => {
    provider = 'none';
    const ai = service();
    expect((await ai.status(OWNER)).state).toBe('DISABLED');
    expect((await ai.setOn(OWNER, true)).on).toBe(false);
  });

  it('after a restart, gives models still in memory 15 minutes', async () => {
    chat.model = model('chat-m', 'loaded');
    embed.model = model('embed-m', 'loaded');
    const ai = service();
    await ai.adopt();
    expect(await ai.status(OWNER)).toMatchObject({
      state: 'READY',
      on: true,
      autoOffAt: '2026-11-16T10:15:00.000Z',
    });
  });

  it('loads a model again when its runtime restarted while on', async () => {
    const ai = service();
    await ai.setOn(OWNER, true);
    chat.finishLoading();
    chat.model = model('chat-m', 'unloaded'); // ai-chat container restarted
    await ai.tick();
    expect(chat.calls).toEqual(['load chat-m', 'load chat-m']);
  });
});
