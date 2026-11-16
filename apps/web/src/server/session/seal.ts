import { createCipheriv, createDecipheriv, hkdfSync, randomBytes } from 'node:crypto';
import type { Surface } from '@/lib/surface';
import { SessionPayloadSchema, type SessionPayload } from './payload';

const PREFIX = 'v1.';
const NONCE_BYTES = 12;
const TAG_BYTES = 16;
/** Browsers keep cookies up to 4096 bytes including the name. */
const MAX_SEALED_LENGTH = 3800;

export interface Sealer {
  seal(payload: SessionPayload): string;
  /** Null for anything that is not a valid, untampered cookie of this surface. */
  unseal(value: string, surface: Surface): SessionPayload | null;
}

/**
 * Session cookie encryption: AES-256-GCM with a key derived (HKDF-SHA256) from
 * WEB_COOKIE_SECRET, a random 96-bit nonce per cookie, and the surface in the
 * associated data, so a member cookie can never be replayed on the admin host
 * (or the other way round) even if it were copied there.
 *
 * Format: `v1.` ‖ base64url(nonce(12) ‖ ciphertext ‖ tag(16)).
 */
export function createSealer(secret: string): Sealer {
  const key = Buffer.from(
    hkdfSync(
      'sha256',
      Buffer.from(secret, 'utf8'),
      Buffer.from('scanova/web/v1', 'utf8'),
      Buffer.from('session-cookie', 'utf8'),
      32,
    ),
  );
  const aad = (surface: Surface): Buffer =>
    Buffer.from(`scanova:web-session:v1|${surface}`, 'utf8');

  return {
    seal(payload: SessionPayload): string {
      const nonce = randomBytes(NONCE_BYTES);
      const cipher = createCipheriv('aes-256-gcm', key, nonce, { authTagLength: TAG_BYTES });
      cipher.setAAD(aad(payload.surface));
      const ciphertext = Buffer.concat([
        cipher.update(JSON.stringify(payload), 'utf8'),
        cipher.final(),
      ]);
      const sealed = `${PREFIX}${Buffer.concat([nonce, ciphertext, cipher.getAuthTag()]).toString('base64url')}`;
      if (sealed.length > MAX_SEALED_LENGTH) {
        throw new Error('The session does not fit in a cookie.');
      }
      return sealed;
    },

    unseal(value: string, surface: Surface): SessionPayload | null {
      if (!value.startsWith(PREFIX) || value.length > MAX_SEALED_LENGTH) {
        return null;
      }
      const bytes = Buffer.from(value.slice(PREFIX.length), 'base64url');
      if (bytes.length <= NONCE_BYTES + TAG_BYTES) {
        return null;
      }
      try {
        const decipher = createDecipheriv('aes-256-gcm', key, bytes.subarray(0, NONCE_BYTES), {
          authTagLength: TAG_BYTES,
        });
        decipher.setAAD(aad(surface));
        decipher.setAuthTag(bytes.subarray(bytes.length - TAG_BYTES));
        const plaintext = Buffer.concat([
          decipher.update(bytes.subarray(NONCE_BYTES, bytes.length - TAG_BYTES)),
          decipher.final(),
        ]).toString('utf8');
        const parsed = SessionPayloadSchema.safeParse(JSON.parse(plaintext));
        return parsed.success && parsed.data.surface === surface ? parsed.data : null;
      } catch {
        // Wrong key (secret rotated), tampering, truncation or malformed JSON.
        return null;
      }
    },
  };
}
