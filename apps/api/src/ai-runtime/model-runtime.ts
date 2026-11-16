import { z } from 'zod';

/**
 * The model runtime as the API controls it: llama.cpp `llama-server` in router
 * mode (§11.8). The router itself holds no model; `POST /models/load` starts a
 * child process with the model, `POST /models/unload` ends it, and its memory
 * goes back to the server. Every endpoint except `/health` needs the API key.
 */

export type ModelState = 'unloaded' | 'loading' | 'loaded' | 'sleeping' | 'failed' | 'other';

export interface RuntimeModel {
  readonly name: string;
  readonly state: ModelState;
  /** Host memory the model needs at its configured context, when the router has measured it. */
  readonly neededBytes: number | null;
}

export interface ModelRuntime {
  list(): Promise<RuntimeModel[]>;
  load(name: string): Promise<void>;
  unload(name: string): Promise<void>;
}

/** The runtime did not answer, or answered something that is not a llama.cpp router. */
export class RuntimeUnavailableError extends Error {
  public constructor(reason: string) {
    super(`AI runtime unavailable: ${reason}`);
    this.name = 'RuntimeUnavailableError';
  }
}

// Only the fields the API uses. The router also lists file paths and arguments: never passed on.
const DeviceSchema = z.object({
  model: z.number().nonnegative().optional(),
  context: z.number().nonnegative().optional(),
  compute: z.number().nonnegative().optional(),
});
const RouterModelSchema = z.object({
  id: z.string(),
  status: z.object({ value: z.string(), failed: z.boolean().optional() }),
  memory: z
    .object({
      status: z.string().optional(),
      min: z.object({ devices: z.array(DeviceSchema) }).nullish(),
    })
    .optional(),
});
const RouterModelsSchema = z.object({ data: z.array(RouterModelSchema) });

function stateOf(status: { value: string; failed?: boolean | undefined }): ModelState {
  if (status.value === 'unloaded') {
    return status.failed === true ? 'failed' : 'unloaded';
  }
  if (status.value === 'loading' || status.value === 'loaded' || status.value === 'sleeping') {
    return status.value;
  }
  return 'other';
}

function neededBytesOf(memory: z.infer<typeof RouterModelSchema>['memory']): number | null {
  if (memory?.status !== 'measured' || !memory.min) {
    return null;
  }
  return memory.min.devices.reduce(
    (sum, device) => sum + (device.model ?? 0) + (device.context ?? 0) + (device.compute ?? 0),
    0,
  );
}

/** A llama.cpp router at the origin of an OpenAI-style base URL (`http://ai-chat:8080/v1`). */
export class LlamaRouterRuntime implements ModelRuntime {
  private readonly origin: string;

  public constructor(
    baseUrl: string,
    private readonly apiKey: string,
    private readonly timeoutMs = 5_000,
  ) {
    this.origin = new URL(baseUrl).origin;
  }

  public async list(): Promise<RuntimeModel[]> {
    const response = await this.request('GET', '/models');
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      throw new RuntimeUnavailableError('the model list is not JSON');
    }
    const parsed = RouterModelsSchema.safeParse(body);
    if (!parsed.success) {
      throw new RuntimeUnavailableError('the model list is not a llama.cpp router answer');
    }
    return parsed.data.data.map((model) => ({
      name: model.id,
      state: stateOf(model.status),
      neededBytes: neededBytesOf(model.memory),
    }));
  }

  public async load(name: string): Promise<void> {
    await this.command('/models/load', name, ['loading', 'loaded', 'sleeping']);
  }

  public async unload(name: string): Promise<void> {
    await this.command('/models/unload', name, ['unloaded', 'failed']);
  }

  /**
   * Sends a load or unload. A refusal (for example "already running") is not
   * read: error bodies can echo input. Instead the list says whether the model
   * is where it should be now.
   */
  private async command(path: string, name: string, done: readonly ModelState[]): Promise<void> {
    const response = await this.request('POST', path, { model: name });
    await response.body?.cancel();
    if (response.ok) {
      return;
    }
    const model = (await this.list()).find((entry) => entry.name === name);
    if (model === undefined || !done.includes(model.state)) {
      throw new RuntimeUnavailableError(`${path} answered ${response.status}`);
    }
  }

  private async request(method: 'GET' | 'POST', path: string, body?: object): Promise<Response> {
    let response: Response;
    try {
      response = await fetch(`${this.origin}${path}`, {
        method,
        headers: {
          authorization: `Bearer ${this.apiKey}`,
          ...(body === undefined ? {} : { 'content-type': 'application/json' }),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(this.timeoutMs),
        redirect: 'error',
      });
    } catch (error) {
      throw new RuntimeUnavailableError(error instanceof Error ? error.name : 'request failed');
    }
    if (method === 'GET' && !response.ok) {
      await response.body?.cancel();
      throw new RuntimeUnavailableError(`${path} answered ${response.status}`);
    }
    return response;
  }
}
