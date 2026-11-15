import type { AuthContext } from '../../common/auth-context.js';

/** Who performs an administrative action: a signed-in admin on the web console, or the operator CLI. */
export interface Actor {
  /** Internal id of the acting admin; null for the operator CLI. */
  readonly userId: number | null;
  readonly userPublicId: string | null;
  readonly via: 'web' | 'cli';
  readonly ip: string | null;
}

export function webActor(auth: AuthContext, ip: string): Actor {
  return { userId: auth.userId, userPublicId: auth.userPublicId, via: 'web', ip };
}

export const CLI_ACTOR: Actor = { userId: null, userPublicId: null, via: 'cli', ip: null };
