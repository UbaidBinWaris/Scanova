import { z } from 'zod';

export const API_VERSION = '1';
export const SERVER_NAME = 'Scanova';

export const HealthSchema = z.object({ status: z.literal('ok') });
export type Health = z.infer<typeof HealthSchema>;

export const DependencyStateSchema = z.enum(['ok', 'unavailable']);
export type DependencyState = z.infer<typeof DependencyStateSchema>;

export const ReadinessChecksSchema = z.object({
  database: DependencyStateSchema,
  storage: DependencyStateSchema,
  kms: DependencyStateSchema,
});
export type ReadinessChecks = z.infer<typeof ReadinessChecksSchema>;

/** `GET /health/ready`. When a dependency is down the answer is a 503 error whose `details` carry `checks`. */
export const ReadinessSchema = z.object({
  status: z.literal('ok'),
  checks: ReadinessChecksSchema,
});
export type Readiness = z.infer<typeof ReadinessSchema>;

export const ServerInfoSchema = z.object({
  name: z.string(),
  apiVersion: z.literal(API_VERSION),
  serverVersion: z.string(),
  features: z.object({
    serverOcr: z.boolean(),
    ai: z.boolean(),
  }),
});
export type ServerInfo = z.infer<typeof ServerInfoSchema>;
