import { randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import type { AuditEvent as AuditEventDto } from '@scanova/contracts/admin';
import { FIELDS } from '@scanova/crypto/bindings';
import { decryptFieldToString, encryptField } from '@scanova/crypto/field';
import type { DatabaseClient } from '@scanova/database/client';
import type { Keyring } from '@scanova/keyring/keyring';
import { z } from 'zod';
import { decodeCursor, encodeCursor } from '../common/cursor.js';
import { Page } from '../common/page.js';
import { DATABASE, KEYRING } from '../platform/tokens.js';

/** Every security-relevant event (§5.5). Names are stable and not sensitive. */
export type AuditAction =
  | 'LOGIN_SUCCEEDED'
  | 'SIGNUP'
  | 'LOGIN_FAILED'
  | 'LOGIN_REFUSED_ADMIN_MOBILE'
  | 'LOGIN_REFUSED_DISABLED'
  | 'REFRESH_TOKEN_REUSED'
  | 'LOGOUT'
  | 'PASSWORD_CHANGED'
  | 'SESSION_REVOKED'
  | 'USER_CREATED'
  | 'USER_UPDATED'
  | 'USER_PASSWORD_RESET'
  | 'USER_SESSIONS_REVOKED'
  | 'USER_DELETED'
  | 'KEY_ROTATION_REQUESTED'
  | 'OWNER_GRANTED'
  | 'OWNER_REVOKED'
  | 'AI_RUNTIME_ON'
  | 'AI_RUNTIME_OFF'
  | 'AI_RUNTIME_AUTO_OFF';

/** Ids, addresses and enum values. Never e-mail addresses, names, titles or text. */
export type AuditPayload = Readonly<Record<string, string | number | boolean | null>>;

const CursorSchema = z.object({ c: z.iso.datetime(), i: z.uuid() });

/**
 * The audit log. Payloads are encrypted under the system AUDIT key rather than
 * a user key, so the log survives the deletion of the users it mentions.
 */
@Injectable()
export class AuditService {
  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    @Inject(KEYRING) private readonly keyring: Keyring,
  ) {}

  public async record(
    action: AuditAction,
    actorId: number | null,
    payload: AuditPayload = {},
  ): Promise<void> {
    const publicId = randomUUID();
    const payloadEnc = await this.keyring.useSystemKey('AUDIT', (key) =>
      encryptField(key, FIELDS.auditPayload(publicId), JSON.stringify(payload)),
    );
    await this.database.auditEvent.create({ data: { publicId, actorId, action, payloadEnc } });
  }

  /** Newest first; optionally only one action and/or one actor (by public id). */
  public async list(options: {
    limit: number;
    cursor?: string | undefined;
    action?: string | undefined;
    actorId?: string | undefined;
  }): Promise<Page<AuditEventDto>> {
    const after = options.cursor === undefined ? null : decodeCursor(options.cursor, CursorSchema);
    const rows = await this.database.auditEvent.findMany({
      where: {
        ...(options.action === undefined ? {} : { action: options.action }),
        ...(options.actorId === undefined ? {} : { actor: { publicId: options.actorId } }),
        ...(after === null
          ? {}
          : {
              OR: [
                { createdAt: { lt: new Date(after.c) } },
                { createdAt: new Date(after.c), publicId: { lt: after.i } },
              ],
            }),
      },
      orderBy: [{ createdAt: 'desc' }, { publicId: 'desc' }],
      take: options.limit + 1,
      include: { actor: { select: { publicId: true } } },
    });
    const page = rows.slice(0, options.limit);
    const events = await this.keyring.useSystemKey('AUDIT', (key) =>
      page.map((row) => ({
        id: row.publicId,
        actorId: row.actor?.publicId ?? null,
        action: row.action,
        payload: JSON.parse(
          decryptFieldToString(key, FIELDS.auditPayload(row.publicId), row.payloadEnc),
        ) as Record<string, unknown>,
        createdAt: row.createdAt.toISOString(),
      })),
    );
    const last = page.at(-1);
    return new Page(
      events,
      rows.length > options.limit && last !== undefined
        ? encodeCursor({ c: last.createdAt.toISOString(), i: last.publicId })
        : null,
    );
  }
}
