/** One problem with one environment variable. Never carries the value. */
export interface ConfigIssue {
  readonly variable: string;
  readonly message: string;
}

/** Thrown at start-up when the environment does not satisfy a process's schema. */
export class ConfigError extends Error {
  public readonly issues: readonly ConfigIssue[];

  public constructor(scope: string, issues: readonly ConfigIssue[]) {
    const lines = issues.map((issue) => `  - ${issue.variable}: ${issue.message}`);
    super(`Invalid ${scope} configuration:\n${lines.join('\n')}`);
    this.name = 'ConfigError';
    this.issues = issues;
  }
}
