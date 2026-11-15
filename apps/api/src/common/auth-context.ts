import type { ClientType, UserType } from '@scanova/contracts/enums';

/** Who is making the request, established by the global auth guard. */
export interface AuthContext {
  /** Internal database id of the user. Never leaves the API. */
  readonly userId: number;
  /** Public id (`sub` of the access token). */
  readonly userPublicId: string;
  readonly userType: UserType;
  readonly client: ClientType;
  readonly sessionId: number;
  readonly sessionPublicId: string;
}

declare module 'fastify' {
  interface FastifyRequest {
    auth?: AuthContext;
  }
}
