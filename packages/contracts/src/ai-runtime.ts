import { z } from 'zod';
import { IsoDateTimeSchema } from './common.js';

/**
 * The server's AI runtime, switched on and off by an owner (§11.8). A model is
 * only in memory while it is on: turning it off ends the model's process, so
 * the server gets all of that RAM back.
 */

/** Minutes the server keeps the AI loaded after the owner's app stops checking in. */
export const AI_AUTO_OFF_MINUTES = 15;

/** How often an open app checks in while the AI is on. */
export const AI_HEARTBEAT_SECONDS = 60;

/**
 * - `OFF`: no model in memory.
 * - `LOADING`: being read into memory (seconds to a couple of minutes).
 * - `READY`: every model is loaded and answering.
 * - `UNLOADING`: being taken out of memory.
 * - `FAILED`: a model did not start; turn it off and on to try again.
 * - `NOT_INSTALLED`: no model has been fetched and chosen on the server yet.
 * - `UNAVAILABLE`: the runtime does not answer.
 * - `DISABLED`: AI is off in the server configuration (`AI_PROVIDER=none`).
 */
export const AiRuntimeStateSchema = z.enum([
  'OFF',
  'LOADING',
  'READY',
  'UNLOADING',
  'FAILED',
  'NOT_INSTALLED',
  'UNAVAILABLE',
  'DISABLED',
]);
export type AiRuntimeState = z.infer<typeof AiRuntimeStateSchema>;

export const AiModelRoleSchema = z.enum(['chat', 'embed']);
export type AiModelRole = z.infer<typeof AiModelRoleSchema>;

export const AiModelStatusSchema = z.object({
  role: AiModelRoleSchema,
  /** The model's name in the server configuration (`AI_CHAT_MODEL`, `AI_EMBED_MODEL`). */
  name: z.string(),
  state: AiRuntimeStateSchema,
});
export type AiModelStatus = z.infer<typeof AiModelStatusSchema>;

/** `GET /api/v1/ai/runtime`, and the answer to turning it on or off and to a check-in. */
export const AiRuntimeStatusSchema = z.object({
  /** The two models together: `READY` only when both are. */
  state: AiRuntimeStateSchema,
  /** An owner asked for it to be on (the models may still be loading). */
  on: z.boolean(),
  models: z.array(AiModelStatusSchema),
  /** When it turns itself off unless an open app checks in first. Null while off. */
  autoOffAt: IsoDateTimeSchema.nullable(),
  autoOffMinutes: z.number().int().positive(),
  heartbeatSeconds: z.number().int().positive(),
  /** Server memory, so the app can show why it could not be turned on. */
  memory: z.object({
    availableBytes: z.number().int().nonnegative().nullable(),
    /** What loading needs: the models' measured size plus the room kept free for other services. */
    neededBytes: z.number().int().nonnegative().nullable(),
  }),
});
export type AiRuntimeStatus = z.infer<typeof AiRuntimeStatusSchema>;

/** `PUT /api/v1/ai/runtime`. */
export const SetAiRuntimeRequestSchema = z.object({ on: z.boolean() });
export type SetAiRuntimeRequest = z.infer<typeof SetAiRuntimeRequestSchema>;
