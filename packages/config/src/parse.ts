import { z } from 'zod';
import { ConfigError, type ConfigIssue } from './errors.js';
import type { EnvSource } from './env-source.js';

/** Integer from an environment string, within bounds. */
export function envInt(min: number, max: number = Number.MAX_SAFE_INTEGER): z.ZodType<number> {
  return z
    .string()
    .regex(/^-?\d+$/u, 'must be an integer')
    .transform((value) => Number(value))
    .pipe(z.number().int().min(min).max(max));
}

export function envBoolean(): z.ZodType<boolean> {
  return z.enum(['true', 'false']).transform((value) => value === 'true');
}

export const envString = z.string().min(1);

/** A secret that must be long enough to be a generated value, not a placeholder. */
export function envSecret(minLength: number): z.ZodType<string> {
  return z.string().min(minLength, `must be at least ${minLength} characters`);
}

export const envPort = envInt(1, 65_535);

/**
 * Validates the variables a process needs and reports every problem at once.
 * Messages name variables, never values.
 */
export function parseEnvWith<Shape extends z.ZodRawShape>(
  scope: string,
  shape: Shape,
  source: EnvSource,
): z.infer<z.ZodObject<Shape>> {
  const result = z.object(shape).safeParse(source.values);
  if (result.success) {
    return result.data;
  }
  const issues: ConfigIssue[] = result.error.issues.map((issue) => ({
    variable: issue.path.map(String).join('.') || '(root)',
    message:
      issue.code === 'invalid_type' && source.values[String(issue.path[0])] === undefined
        ? 'is required but missing or empty'
        : issue.message,
  }));
  throw new ConfigError(scope, issues);
}
