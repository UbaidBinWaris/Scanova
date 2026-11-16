import { Inject, Injectable, type OnModuleDestroy } from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import { AppLogger } from '@scanova/config/logger';
import {
  AI_AUTO_OFF_MINUTES,
  AI_HEARTBEAT_SECONDS,
  type AiModelRole,
  type AiModelStatus,
  type AiRuntimeState,
  type AiRuntimeStatus,
} from '@scanova/contracts/ai-runtime';
import { AuditService } from '../audit/audit.service.js';
import { AppError } from '../common/app-error.js';
import type { AuthContext } from '../common/auth-context.js';
import { API_CONFIG, CLOCK } from '../platform/tokens.js';
import { UsersService } from '../users/users.service.js';
import { type ModelRuntime, type RuntimeModel } from './model-runtime.js';
import type { ServerMemory } from './server-memory.js';

export const AI_MODEL_RUNTIMES = Symbol('AI_MODEL_RUNTIMES');
export const SERVER_MEMORY = Symbol('SERVER_MEMORY');

/** One model the API controls: the chat model on ai-chat, the embedding model on ai-embed. */
export interface ControlledModel {
  readonly role: AiModelRole;
  readonly name: string;
  readonly runtime: ModelRuntime;
}

/**
 * What a model needs when the router has not measured it yet: the container's
 * memory cap (§8), which it can never exceed.
 */
const FALLBACK_NEED_BYTES: Readonly<Record<AiModelRole, number>> = {
  chat: 4608 * 1024 * 1024,
  embed: 1536 * 1024 * 1024,
};

/** The router's measurement leaves out the vision projector and runtime overhead. */
const MEASURED_MARGIN = 1.15;

const AUTO_OFF_MS = AI_AUTO_OFF_MINUTES * 60_000;
const WATCH_INTERVAL_MS = 30_000;

type Clock = () => Date;

interface Observed {
  readonly model: ControlledModel;
  /** Null when the runtime did not answer. */
  readonly listed: RuntimeModel | null | 'missing';
}

/**
 * Loads and unloads the AI models for an owner (§11.8), and only while one
 * wants them: a model in memory costs the shared server gigabytes.
 *
 * - On: checks there is room (the models' size plus `AI_KEEP_FREE_MB` for the
 *   other services), then asks the routers to load. Each answer and each
 *   check-in from an open app moves the automatic turn-off 15 minutes ahead.
 * - Off: unloads at once. Nobody checking in for 15 minutes does the same.
 *
 * The routers' model lists are the truth about what is loaded; this service
 * only remembers whether an owner wants it on and until when. After a restart
 * it finds loaded models in those lists and gives them 15 minutes again.
 */
@Injectable()
export class AiRuntimeService implements OnModuleDestroy {
  private on = false;
  private autoOffAt: Date | null = null;
  private watch: NodeJS.Timeout | null = null;
  /** Switching is serialised: a quick off-on-off ends in the last state asked for. */
  private queue: Promise<unknown> = Promise.resolve();

  public constructor(
    @Inject(API_CONFIG) private readonly config: ApiConfig,
    @Inject(CLOCK) private readonly clock: Clock,
    @Inject(AI_MODEL_RUNTIMES) private readonly models: readonly ControlledModel[],
    @Inject(SERVER_MEMORY) private readonly memory: ServerMemory,
    private readonly users: UsersService,
    private readonly audit: AuditService,
    private readonly logger: AppLogger,
  ) {}

  // ── Owner actions ──────────────────────────────────────────────────────

  public async status(auth: AuthContext): Promise<AiRuntimeStatus> {
    await this.requireOwner(auth);
    return this.describe(await this.observe());
  }

  /** A check-in from an open app: keeps the models loaded for another 15 minutes. */
  public async heartbeat(auth: AuthContext): Promise<AiRuntimeStatus> {
    await this.requireOwner(auth);
    if (this.on) {
      this.autoOffAt = new Date(this.clock().getTime() + AUTO_OFF_MS);
    }
    return this.describe(await this.observe());
  }

  public async setOn(auth: AuthContext, on: boolean): Promise<AiRuntimeStatus> {
    const owner = await this.requireOwner(auth);
    return this.serialised(async () => {
      if (on) {
        await this.turnOn(owner.id);
      } else {
        await this.turnOff('AI_RUNTIME_OFF', owner.id);
      }
      return this.describe(await this.observe());
    });
  }

  // ── Automatic turn-off ─────────────────────────────────────────────────

  /**
   * Started by the HTTP server only (main.ts), not by the operator CLI, which
   * builds the same module. Adopts models already loaded, then checks every 30 s.
   */
  public startWatching(): void {
    if (this.watch !== null || this.models.length === 0) {
      return;
    }
    void this.serialised(() => this.adopt());
    this.watch = setInterval(() => {
      void this.serialised(() => this.tick());
    }, WATCH_INTERVAL_MS);
    this.watch.unref();
  }

  public onModuleDestroy(): void {
    if (this.watch !== null) {
      clearInterval(this.watch);
      this.watch = null;
    }
  }

  /** One check: turn off when nobody checked in, reload a model that went away while on. */
  public async tick(): Promise<void> {
    if (!this.on) {
      return;
    }
    if (this.autoOffAt !== null && this.clock().getTime() >= this.autoOffAt.getTime()) {
      this.logger.info('ai_runtime.auto_off', { minutes: AI_AUTO_OFF_MINUTES });
      await this.turnOff('AI_RUNTIME_AUTO_OFF', null);
      return;
    }
    // A runtime restarted while on comes back empty: load it again (not after a failure).
    for (const { model, listed } of await this.observe()) {
      if (listed !== null && listed !== 'missing' && listed.state === 'unloaded') {
        await this.attempt('ai_runtime.reload_failed', model, () => model.runtime.load(model.name));
      }
    }
  }

  /** After a restart: models still in memory get 15 minutes, as if just turned on. */
  public async adopt(): Promise<void> {
    const loaded = (await this.observe()).some(
      ({ listed }) => listed !== null && listed !== 'missing' && isInMemory(listed.state),
    );
    if (loaded && !this.on) {
      this.on = true;
      this.autoOffAt = new Date(this.clock().getTime() + AUTO_OFF_MS);
      this.logger.info('ai_runtime.adopted', { minutes: AI_AUTO_OFF_MINUTES });
    }
  }

  // ── Internals ──────────────────────────────────────────────────────────

  private async turnOn(actorId: number): Promise<void> {
    const observed = await this.observe();
    const blocked = observed.find(({ listed }) => listed === null || listed === 'missing');
    if (this.config.ai.provider === 'none' || blocked !== undefined) {
      // Nothing to load: the status says why (disabled, not installed, unavailable).
      return;
    }
    const { availableBytes, neededBytes } = await this.memoryCheck(observed);
    if (availableBytes !== null && neededBytes !== null && availableBytes < neededBytes) {
      throw new AppError('NOT_ENOUGH_MEMORY', { details: { availableBytes, neededBytes } });
    }
    this.on = true;
    this.autoOffAt = new Date(this.clock().getTime() + AUTO_OFF_MS);
    for (const { model, listed } of observed) {
      if (listed !== null && listed !== 'missing' && !isInMemory(listed.state)) {
        await this.attempt('ai_runtime.load_failed', model, () => model.runtime.load(model.name));
      }
    }
    await this.audit.record('AI_RUNTIME_ON', actorId, {
      models: this.models.map((model) => model.name).join(','),
    });
  }

  private async turnOff(
    action: 'AI_RUNTIME_OFF' | 'AI_RUNTIME_AUTO_OFF',
    actorId: number | null,
  ): Promise<void> {
    const wasOn = this.on;
    this.on = false;
    this.autoOffAt = null;
    for (const { model, listed } of await this.observe()) {
      if (listed !== null && listed !== 'missing' && isInMemory(listed.state)) {
        await this.attempt('ai_runtime.unload_failed', model, () =>
          model.runtime.unload(model.name),
        );
      }
    }
    if (wasOn) {
      await this.audit.record(action, actorId, {
        models: this.models.map((model) => model.name).join(','),
      });
    }
  }

  private async attempt(
    event: string,
    model: ControlledModel,
    action: () => Promise<void>,
  ): Promise<void> {
    try {
      await action();
    } catch (error) {
      // The status shows the model as failed or unavailable; the owner can retry.
      this.logger.failure(event, error, { role: model.role });
    }
  }

  private async observe(): Promise<Observed[]> {
    return Promise.all(
      this.models.map(async (model): Promise<Observed> => {
        try {
          const listed = (await model.runtime.list()).find((entry) => entry.name === model.name);
          return { model, listed: listed ?? 'missing' };
        } catch {
          return { model, listed: null };
        }
      }),
    );
  }

  private async memoryCheck(
    observed: readonly Observed[],
  ): Promise<{ availableBytes: number | null; neededBytes: number | null }> {
    const availableBytes = await this.memory.availableBytes();
    let neededBytes = this.config.aiControl.keepFreeBytes;
    for (const { model, listed } of observed) {
      if (listed === null || listed === 'missing' || isInMemory(listed.state)) {
        continue; // already counted in the free memory, or nothing to load
      }
      neededBytes +=
        listed.neededBytes === null
          ? FALLBACK_NEED_BYTES[model.role]
          : Math.ceil(listed.neededBytes * MEASURED_MARGIN);
    }
    return { availableBytes, neededBytes: this.models.length === 0 ? null : neededBytes };
  }

  private async describe(observed: readonly Observed[]): Promise<AiRuntimeStatus> {
    const models: AiModelStatus[] = observed.map(({ model, listed }) => ({
      role: model.role,
      name: model.name,
      state: this.modelState(listed),
    }));
    return {
      state: this.overall(models),
      on: this.on,
      models,
      autoOffAt: this.on && this.autoOffAt !== null ? this.autoOffAt.toISOString() : null,
      autoOffMinutes: AI_AUTO_OFF_MINUTES,
      heartbeatSeconds: AI_HEARTBEAT_SECONDS,
      memory: await this.memoryCheck(observed),
    };
  }

  private modelState(listed: Observed['listed']): AiRuntimeState {
    if (listed === null) {
      return 'UNAVAILABLE';
    }
    if (listed === 'missing') {
      return 'NOT_INSTALLED';
    }
    switch (listed.state) {
      case 'failed':
        return 'FAILED';
      case 'loading':
        return this.on ? 'LOADING' : 'UNLOADING';
      case 'loaded':
      case 'sleeping':
        return this.on ? 'READY' : 'UNLOADING';
      case 'unloaded':
        // Asked for and not started yet: the watch loads it again if it went away.
        return this.on ? 'LOADING' : 'OFF';
      case 'other':
        return 'UNAVAILABLE';
    }
  }

  private overall(models: readonly AiModelStatus[]): AiRuntimeState {
    if (this.config.ai.provider === 'none' || models.length === 0) {
      return 'DISABLED';
    }
    for (const state of [
      'UNAVAILABLE',
      'NOT_INSTALLED',
      'FAILED',
      'UNLOADING',
      'LOADING',
    ] as const) {
      if (models.some((model) => model.state === state)) {
        return state;
      }
    }
    return models.every((model) => model.state === 'READY') ? 'READY' : 'OFF';
  }

  private async requireOwner(auth: AuthContext): Promise<{ id: number }> {
    const user = await this.users.getByPublicId(auth.userPublicId);
    if (!user.isOwner || user.userType !== 'MEMBER') {
      throw new AppError('FORBIDDEN', { message: 'Only the server owner can do this.' });
    }
    return { id: user.id };
  }

  private serialised<T>(work: () => Promise<T>): Promise<T> {
    const next = this.queue.then(work, work);
    this.queue = next.catch(() => undefined);
    return next;
  }
}

function isInMemory(state: RuntimeModel['state']): boolean {
  return state === 'loaded' || state === 'loading' || state === 'sleeping';
}
