'use server';

import { redirect } from 'next/navigation';
import type { ActionResult } from '@/lib/action-result';
import { PATHS, safeReturnPath, userTypeFits } from '@/lib/surface';
import { describeBrowser } from '@/lib/user-agent';
import { ChangePasswordSchema, SignInSchema } from '@/lib/validation';
import { getWebConfig } from '../config';
import { getApi } from '../api/instance';
import { requestInfo } from '../request';
import { payloadFromTokens } from '../session/payload';
import { clearSession, readSession, requireSession, writeSession } from '../session/server';
import { fromApiFailure, invalid } from './support';

/**
 * Sign-in for members and admins, on the same form. The password goes from
 * this server to the internal web login (service token); the browser only ever
 * receives the encrypted session cookie. Each host accepts its own user type:
 * an admin is sent to the admin console, a member to the member portal, and
 * the session that was just opened is closed again.
 */
export async function signIn(input: unknown): Promise<ActionResult<never>> {
  const parsed = SignInSchema.safeParse(input);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  const info = await requestInfo();
  const api = getApi();
  const deviceName =
    info.surface === 'admin'
      ? `Admin console · ${describeBrowser(info.userAgent)}`
      : describeBrowser(info.userAgent);
  const result = await api.auth.webLogin(
    { clientIp: info.clientIp },
    {
      email: parsed.data.email,
      password: parsed.data.password,
      device: { name: deviceName.slice(0, 100) },
    },
  );
  if (!result.ok) {
    return fromApiFailure(result, info.surface, 'sign-in');
  }
  const tokens = result.data;
  if (!userTypeFits(tokens.user.userType, info.surface)) {
    await api.auth.logout({ clientIp: info.clientIp }, tokens.refreshToken);
    const config = getWebConfig();
    return {
      ok: false,
      code: 'WRONG_CONSOLE',
      message:
        tokens.user.userType === 'ADMIN'
          ? config.adminOrigin === null
            ? 'Administrators sign in on the admin console, which this server does not offer on the web.'
            : `Administrators sign in on the admin console at ${config.adminOrigin}.`
          : `This console is for administrators. Sign in to your documents at ${config.siteOrigin}${PATHS.site.signIn}.`,
    };
  }
  const previous = await readSession(info);
  if (previous !== null) {
    // Signing in again replaces the session in this browser; the old one ends too.
    await api.auth.logout({ clientIp: info.clientIp }, previous.refreshToken);
  }
  await writeSession(payloadFromTokens(tokens, info.surface, Date.now()), info);
  const paths = PATHS[info.surface];
  redirect(
    tokens.user.mustChangePassword
      ? paths.password
      : (safeReturnPath(parsed.data.next, info.surface) ?? paths.home),
  );
}

/** Ends the session at the API (revokes it) and removes the cookie. */
export async function signOut(): Promise<void> {
  const info = await requestInfo();
  const session = await readSession(info);
  if (session !== null) {
    await getApi().auth.logout({ clientIp: info.clientIp }, session.refreshToken);
  }
  await clearSession(info);
  redirect(PATHS[info.surface].signIn);
}

/**
 * Changes the password (also the forced change after a temporary one). The API
 * signs out every other session and answers with new tokens for this one.
 */
export async function changePassword(input: unknown): Promise<ActionResult<null>> {
  const parsed = ChangePasswordSchema.safeParse(input);
  if (!parsed.success) {
    return invalid(parsed.error);
  }
  const surface = (await requestInfo()).surface;
  const { info, session, context } = await requireSession(surface, { allowPasswordChange: true });
  const result = await getApi().me.changePassword(context, {
    currentPassword: parsed.data.currentPassword,
    newPassword: parsed.data.newPassword,
  });
  if (!result.ok) {
    if (result.code === 'INVALID_CREDENTIALS') {
      return {
        ok: false,
        code: result.code,
        message: 'The current password is incorrect.',
        fieldErrors: { currentPassword: 'The current password is incorrect.' },
      };
    }
    return fromApiFailure(result, surface, 'change-password');
  }
  await writeSession(payloadFromTokens(result.data, surface, session.issuedAt), info);
  return { ok: true, data: null };
}
