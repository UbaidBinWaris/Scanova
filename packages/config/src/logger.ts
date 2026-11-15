import type { LogLevel } from './sections.js';

const SEVERITY: Record<LogLevel, number> = {
  fatal: 60,
  error: 50,
  warn: 40,
  info: 30,
  debug: 20,
  trace: 10,
};

/** Values that may appear in a log line: ids, codes, counts, durations. Never content. */
export type LogFields = Readonly<Record<string, string | number | boolean | null | undefined>>;

/**
 * One JSON object per line on stdout.
 *
 * Logging rule (§10): ids and error codes only. Callers pass an event name and
 * structured fields; there is deliberately no way to log an arbitrary object,
 * so a request body, a title or a token cannot end up here by accident.
 *
 * Shared by the API and the worker. It also has the method set of a NestJS
 * `LoggerService`, so it can be handed to the framework as its logger.
 */
export class AppLogger {
  private readonly threshold: number;

  public constructor(
    level: LogLevel,
    private readonly service: string,
    private readonly write: (line: string) => void = (line): void => {
      process.stdout.write(line);
    },
  ) {
    this.threshold = SEVERITY[level];
  }

  public event(level: LogLevel, event: string, fields: LogFields = {}): void {
    if (SEVERITY[level] < this.threshold) {
      return;
    }
    this.write(
      `${JSON.stringify({ time: new Date().toISOString(), level, service: this.service, event, ...fields })}\n`,
    );
  }

  public info(event: string, fields?: LogFields): void {
    this.event('info', event, fields);
  }

  /**
   * Logs a failure without its message: third-party messages can quote input.
   * The error's name, code and stack frames are enough to find the cause.
   */
  public failure(event: string, error: unknown, fields: LogFields = {}): void {
    const details = describeError(error);
    this.event('error', event, { ...fields, ...details });
  }

  // ── framework logger interface (messages are module and route names) ─────

  public log(message: unknown, context?: string): void {
    this.event('info', 'nest', { message: String(message), context });
  }

  public error(message: unknown, _stack?: string, context?: string): void {
    this.event('error', 'nest', { message: String(message), context });
  }

  public warn(message: unknown, context?: string): void {
    this.event('warn', 'nest', { message: String(message), context });
  }

  public debug(message: unknown, context?: string): void {
    this.event('debug', 'nest', { message: String(message), context });
  }

  public verbose(message: unknown, context?: string): void {
    this.event('trace', 'nest', { message: String(message), context });
  }

  public fatal(message: unknown, context?: string): void {
    this.event('fatal', 'nest', { message: String(message), context });
  }
}

/** Name, code and stack frames of an error and of its cause — never the message. */
export function describeError(error: unknown): LogFields {
  if (!(error instanceof Error)) {
    return { errorName: typeof error };
  }
  const code = (error as { code?: unknown }).code;
  const frames = (error.stack ?? '')
    .split('\n')
    .filter((line) => line.trimStart().startsWith('at '))
    .slice(0, 6)
    .map((line) => line.trim())
    .join(' | ');
  const cause = (error as { cause?: unknown }).cause;
  const causeCode = cause instanceof Error ? (cause as { code?: unknown }).code : undefined;
  return {
    errorName: error.name,
    errorCode: typeof code === 'string' || typeof code === 'number' ? code : undefined,
    causeName: cause instanceof Error ? cause.name : undefined,
    causeCode:
      typeof causeCode === 'string' || typeof causeCode === 'number' ? causeCode : undefined,
    stack: frames,
  };
}
