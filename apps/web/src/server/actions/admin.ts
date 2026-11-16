'use server';

import {
  CreateRotationRequestSchema,
  type AdminCreateUserResponse,
  type AdminResetPasswordResponse,
  type RotationRun,
} from '@scanova/contracts/admin';
import type { User } from '@scanova/contracts/auth';
import type { ActionResult } from '@/lib/action-result';
import { CreateUserSchema, UpdateUserSchema, UserIdSchema } from '@/lib/validation';
import { getApi } from '../api/instance';
import { requireSession } from '../session/server';
import { fromApiFailure, invalid, ok } from './support';

/*
 * Admin console actions. Each one requires the admin host and an admin
 * session here, and the API requires the service token, typ = ADMIN and
 * cli = web again (§7.4). Nothing here can read a member's documents.
 */

export async function createUser(input: unknown): Promise<ActionResult<AdminCreateUserResponse>> {
  const parsed = CreateUserSchema.safeParse(input);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  const { context } = await requireSession('admin');
  const result = await getApi().admin.createUser(context, parsed.data);
  if (!result.ok && result.code === 'ID_CONFLICT') {
    return {
      ok: false,
      code: result.code,
      message: 'An account with this e-mail address already exists.',
      fieldErrors: { email: 'An account with this e-mail address already exists.' },
    };
  }
  return result.ok ? ok(result.data) : fromApiFailure(result, 'admin', 'create-user');
}

export async function updateUser(input: unknown): Promise<ActionResult<User>> {
  const parsed = UpdateUserSchema.safeParse(input);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  const { id, ...changes } = parsed.data;
  const body = Object.fromEntries(
    Object.entries(changes).filter(([, value]) => value !== undefined),
  ) as typeof changes;
  if (Object.keys(body).length === 0) {
    return { ok: false, code: 'VALIDATION_FAILED', message: 'Nothing to change.' };
  }
  const { context } = await requireSession('admin');
  const result = await getApi().admin.updateUser(context, id, body);
  return result.ok ? ok(result.data) : fromApiFailure(result, 'admin', 'update-user');
}

export async function resetUserPassword(
  input: unknown,
): Promise<ActionResult<AdminResetPasswordResponse>> {
  const parsed = UserIdSchema.safeParse(input);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  const { context } = await requireSession('admin');
  const result = await getApi().admin.resetPassword(context, parsed.data.id);
  return result.ok ? ok(result.data) : fromApiFailure(result, 'admin', 'reset-password');
}

export async function revokeUserSessions(input: unknown): Promise<ActionResult<null>> {
  const parsed = UserIdSchema.safeParse(input);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  const { context } = await requireSession('admin');
  const result = await getApi().admin.revokeSessions(context, parsed.data.id);
  return result.ok ? ok(null) : fromApiFailure(result, 'admin', 'revoke-user-sessions');
}

/** Crypto-shreds the account: its keys are destroyed at once, its data purged after. */
export async function deleteUser(input: unknown): Promise<ActionResult<null>> {
  const parsed = UserIdSchema.safeParse(input);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  const { context } = await requireSession('admin');
  const result = await getApi().admin.deleteUser(context, parsed.data.id);
  return result.ok ? ok(null) : fromApiFailure(result, 'admin', 'delete-user');
}

export async function startRotation(input: unknown): Promise<ActionResult<RotationRun>> {
  const parsed = CreateRotationRequestSchema.safeParse(input);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  const { context } = await requireSession('admin');
  const result = await getApi().admin.requestRotation(context, parsed.data);
  return result.ok ? ok(result.data) : fromApiFailure(result, 'admin', 'start-rotation');
}
