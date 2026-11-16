import {
  AdminCreateUserResponseSchema,
  AdminResetPasswordResponseSchema,
  AuditEventListSchema,
  KeyStatusSchema,
  RotationRunListSchema,
  RotationRunSchema,
  UserListSchema,
  type AdminCreateUserRequest,
  type AdminCreateUserResponse,
  type AdminResetPasswordResponse,
  type AdminUpdateUserRequest,
  type AuditEvent,
  type CreateRotationRequest,
  type KeyStatus,
  type RotationRun,
} from '@scanova/contracts/admin';
import { TokensSchema, UserSchema, type Tokens, type User } from '@scanova/contracts/auth';
import { SuccessSchema, type Success } from '@scanova/contracts/common';
import {
  DocumentListSchema,
  DocumentPagesSchema,
  DocumentSchema,
  DocumentTextSchema,
  type Document,
  type DocumentPages,
  type DocumentText,
  type UpdateDocumentRequest,
} from '@scanova/contracts/documents';
import { SessionListSchema, type Session } from '@scanova/contracts/me';
import type { ApiClient, ApiFailure, ApiResult, CallContext } from './client';

const CLIENT = '/api/v1';
const WEB = '/internal/web/v1';
const ADMIN = '/internal/admin/v1';

export interface PageOptions {
  readonly limit?: number;
  readonly cursor?: string | undefined;
}

export interface AuditFilter extends PageOptions {
  readonly action?: string | undefined;
  readonly actorId?: string | undefined;
}

/** Every API operation the web app uses, typed by the contract schemas (§7). */
// The return type is the object below; spelling it out would repeat every signature.
// eslint-disable-next-line @typescript-eslint/explicit-function-return-type, @typescript-eslint/explicit-module-boundary-types
export function scanovaApi(client: ApiClient) {
  return {
    auth: {
      /** `cli: "web"` tokens; only the web app can call this (service token). */
      webLogin(
        context: CallContext,
        body: { email: string; password: string; device: { name: string } },
      ): Promise<ApiResult<Tokens>> {
        return client.request(context, {
          method: 'POST',
          path: `${WEB}/auth/login`,
          body,
          schema: TokensSchema,
        });
      },
      refresh(context: CallContext, refreshToken: string): Promise<ApiResult<Tokens>> {
        return client.request(context, {
          method: 'POST',
          path: `${CLIENT}/auth/refresh`,
          body: { refreshToken },
          schema: TokensSchema,
        });
      },
      logout(context: CallContext, refreshToken: string): Promise<ApiResult<Success>> {
        return client.request(context, {
          method: 'POST',
          path: `${CLIENT}/auth/logout`,
          body: { refreshToken },
          schema: SuccessSchema,
        });
      },
    },

    me: {
      get(context: CallContext): Promise<ApiResult<User>> {
        return client.request(context, { path: `${CLIENT}/me`, schema: UserSchema });
      },
      updateName(context: CallContext, name: string): Promise<ApiResult<User>> {
        return client.request(context, {
          method: 'PATCH',
          path: `${CLIENT}/me`,
          body: { name },
          schema: UserSchema,
        });
      },
      changePassword(
        context: CallContext,
        body: { currentPassword: string; newPassword: string },
      ): Promise<ApiResult<Tokens>> {
        return client.request(context, {
          method: 'POST',
          path: `${CLIENT}/me/password`,
          body,
          schema: TokensSchema,
        });
      },
      sessions(context: CallContext): Promise<ApiResult<Session[]>> {
        return client.request(context, {
          path: `${CLIENT}/me/sessions`,
          schema: SessionListSchema,
        });
      },
      revokeSession(context: CallContext, sessionId: string): Promise<ApiResult<Success>> {
        return client.request(context, {
          method: 'DELETE',
          path: `${CLIENT}/me/sessions/${sessionId}`,
          schema: SuccessSchema,
        });
      },
    },

    documents: {
      list(
        context: CallContext,
        options: PageOptions & { q?: string | undefined },
      ): Promise<ApiResult<Document[]>> {
        return client.request(context, {
          path: `${CLIENT}/documents`,
          query: { limit: options.limit, cursor: options.cursor, q: options.q },
          schema: DocumentListSchema,
        });
      },
      get(context: CallContext, id: string): Promise<ApiResult<Document>> {
        return client.request(context, {
          path: `${CLIENT}/documents/${id}`,
          schema: DocumentSchema,
        });
      },
      update(
        context: CallContext,
        id: string,
        body: UpdateDocumentRequest,
      ): Promise<ApiResult<Document>> {
        return client.request(context, {
          method: 'PATCH',
          path: `${CLIENT}/documents/${id}`,
          body,
          schema: DocumentSchema,
        });
      },
      remove(context: CallContext, id: string): Promise<ApiResult<Success>> {
        return client.request(context, {
          method: 'DELETE',
          path: `${CLIENT}/documents/${id}`,
          schema: SuccessSchema,
        });
      },
      text(context: CallContext, id: string): Promise<ApiResult<DocumentText>> {
        return client.request(context, {
          path: `${CLIENT}/documents/${id}/text`,
          schema: DocumentTextSchema,
        });
      },
      pages(context: CallContext, id: string): Promise<ApiResult<DocumentPages>> {
        return client.request(context, {
          path: `${CLIENT}/documents/${id}/pages`,
          schema: DocumentPagesSchema,
        });
      },
      file(
        context: CallContext,
        id: string,
        variant: 'original' | 'searchable',
        signal?: AbortSignal,
      ): Promise<Response | ApiFailure> {
        return client.stream(context, `${CLIENT}/documents/${id}/file`, {
          query: { variant },
          ...(signal === undefined ? {} : { signal }),
        });
      },
      pageOriginal(
        context: CallContext,
        id: string,
        pageId: string,
        signal?: AbortSignal,
      ): Promise<Response | ApiFailure> {
        return client.stream(context, `${CLIENT}/documents/${id}/pages/${pageId}/original`, {
          ...(signal === undefined ? {} : { signal }),
        });
      },
    },

    admin: {
      users(context: CallContext, options: PageOptions): Promise<ApiResult<User[]>> {
        return client.request(context, {
          path: `${ADMIN}/users`,
          query: { limit: options.limit, cursor: options.cursor },
          schema: UserListSchema,
        });
      },
      createUser(
        context: CallContext,
        body: AdminCreateUserRequest,
      ): Promise<ApiResult<AdminCreateUserResponse>> {
        return client.request(context, {
          method: 'POST',
          path: `${ADMIN}/users`,
          body,
          schema: AdminCreateUserResponseSchema,
        });
      },
      updateUser(
        context: CallContext,
        id: string,
        body: AdminUpdateUserRequest,
      ): Promise<ApiResult<User>> {
        return client.request(context, {
          method: 'PATCH',
          path: `${ADMIN}/users/${id}`,
          body,
          schema: UserSchema,
        });
      },
      resetPassword(
        context: CallContext,
        id: string,
      ): Promise<ApiResult<AdminResetPasswordResponse>> {
        return client.request(context, {
          method: 'POST',
          path: `${ADMIN}/users/${id}/reset-password`,
          schema: AdminResetPasswordResponseSchema,
        });
      },
      revokeSessions(context: CallContext, id: string): Promise<ApiResult<Success>> {
        return client.request(context, {
          method: 'POST',
          path: `${ADMIN}/users/${id}/revoke-sessions`,
          schema: SuccessSchema,
        });
      },
      deleteUser(context: CallContext, id: string): Promise<ApiResult<Success>> {
        return client.request(context, {
          method: 'DELETE',
          path: `${ADMIN}/users/${id}`,
          schema: SuccessSchema,
        });
      },
      keys(context: CallContext): Promise<ApiResult<KeyStatus>> {
        return client.request(context, { path: `${ADMIN}/keys`, schema: KeyStatusSchema });
      },
      requestRotation(
        context: CallContext,
        body: CreateRotationRequest,
      ): Promise<ApiResult<RotationRun>> {
        return client.request(context, {
          method: 'POST',
          path: `${ADMIN}/keys/rotations`,
          body,
          schema: RotationRunSchema,
        });
      },
      rotations(context: CallContext, options: PageOptions): Promise<ApiResult<RotationRun[]>> {
        return client.request(context, {
          path: `${ADMIN}/keys/rotations`,
          query: { limit: options.limit, cursor: options.cursor },
          schema: RotationRunListSchema,
        });
      },
      auditEvents(context: CallContext, filter: AuditFilter): Promise<ApiResult<AuditEvent[]>> {
        return client.request(context, {
          path: `${ADMIN}/audit-events`,
          query: {
            limit: filter.limit,
            cursor: filter.cursor,
            action: filter.action,
            actorId: filter.actorId,
          },
          schema: AuditEventListSchema,
        });
      },
    },
  };
}

export type ScanovaApi = ReturnType<typeof scanovaApi>;
