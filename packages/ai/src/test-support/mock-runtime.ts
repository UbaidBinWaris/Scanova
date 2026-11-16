import { createServer, type IncomingHttpHeaders, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';

/** One request as the mock saw it. */
export interface RecordedRequest {
  readonly method: string;
  readonly path: string;
  readonly headers: IncomingHttpHeaders;
  readonly body: unknown;
}

export interface MockReply {
  readonly status?: number;
  readonly body?: unknown;
  /** Sent as-is instead of JSON-encoding `body`. */
  readonly rawBody?: string;
  readonly headers?: Record<string, string>;
  /** Waits this long before answering (timeouts). */
  readonly delayMs?: number;
}

export type MockHandler = (request: RecordedRequest) => MockReply;

/**
 * A minimal OpenAI-compatible server on 127.0.0.1 for the client's unit tests:
 * it checks the bearer token like llama.cpp's `--api-key-file`, records every
 * request, and answers with whatever the test's handler returns.
 */
export class MockRuntime {
  public readonly requests: RecordedRequest[] = [];
  public handler: MockHandler = () => ({ status: 404, body: { error: 'no handler' } });
  private server: Server | null = null;

  public constructor(private readonly apiKey: string) {}

  public async start(): Promise<string> {
    const server = createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on('data', (chunk: Buffer) => chunks.push(chunk));
      req.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        const request: RecordedRequest = {
          method: req.method ?? '',
          path: req.url ?? '',
          headers: req.headers,
          body: text.length > 0 ? (JSON.parse(text) as unknown) : null,
        };
        this.requests.push(request);
        const reply =
          req.headers.authorization === `Bearer ${this.apiKey}`
            ? this.handler(request)
            : { status: 401, body: { error: { message: 'Invalid API Key' } } };
        const send = (): void => {
          if (res.destroyed) {
            return;
          }
          const payload = reply.rawBody ?? JSON.stringify(reply.body ?? {});
          res.writeHead(reply.status ?? 200, {
            'content-type': 'application/json',
            ...reply.headers,
          });
          res.end(payload);
        };
        if (reply.delayMs === undefined) {
          send();
        } else {
          setTimeout(send, reply.delayMs).unref();
        }
      });
    });
    this.server = server;
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const { port } = server.address() as AddressInfo;
    return `http://127.0.0.1:${port}/v1`;
  }

  public async stop(): Promise<void> {
    const server = this.server;
    this.server = null;
    if (server !== null) {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  }
}

/** A chat-completions answer whose message content is `content`. */
export function chatReply(
  content: string | null,
  options: { readonly finishReason?: string; readonly usage?: boolean } = {},
): MockReply {
  return {
    body: {
      id: 'chatcmpl-test',
      object: 'chat.completion',
      choices: [
        {
          index: 0,
          message: { role: 'assistant', content },
          finish_reason: options.finishReason ?? 'stop',
        },
      ],
      ...(options.usage === false ? {} : { usage: { prompt_tokens: 120, completion_tokens: 30 } }),
    },
  };
}

/** An embeddings answer with the given vectors (in the given index order). */
export function embeddingsReply(
  vectors: readonly (readonly number[])[],
  indexes?: readonly number[],
): MockReply {
  return {
    body: {
      object: 'list',
      data: vectors.map((embedding, position) => ({
        object: 'embedding',
        index: indexes?.[position] ?? position,
        embedding,
      })),
      usage: { prompt_tokens: 10, total_tokens: 10 },
    },
  };
}
