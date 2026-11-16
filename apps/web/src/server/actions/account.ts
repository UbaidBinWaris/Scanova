'use server';

import { UuidSchema } from '@scanova/contracts/common';
import type { User } from '@scanova/contracts/auth';
import type { ActionResult } from '@/lib/action-result';
import { UpdateNameSchema } from '@/lib/validation';
import { getApi } from '../api/instance';
import { requestInfo } from '../request';
import { requireSession } from '../session/server';
import { fromApiFailure, invalid, ok } from './support';
import { signOut } from './auth';

export async function updateName(input: unknown): Promise<ActionResult<User>> {
  const parsed = UpdateNameSchema.safeParse(input);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  const surface = (await requestInfo()).surface;
  const { context } = await requireSession(surface);
  const result = await getApi().me.updateName(context, parsed.data.name);
  return result.ok ? ok(result.data) : fromApiFailure(result, surface, 'update-name');
}

/** Signs out one of this account's sessions; the current one means signing out here. */
export async function revokeSession(
  sessionId: unknown,
  current: boolean,
): Promise<ActionResult<null>> {
  const parsed = UuidSchema.safeParse(sessionId);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  if (current) {
    await signOut();
  }
  const surface = (await requestInfo()).surface;
  const { context } = await requireSession(surface);
  const result = await getApi().me.revokeSession(context, parsed.data);
  return result.ok ? ok(null) : fromApiFailure(result, surface, 'revoke-session');
}
