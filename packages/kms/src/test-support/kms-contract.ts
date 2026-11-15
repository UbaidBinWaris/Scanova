import { createPublicKey, randomBytes, verify } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { KmsError } from '../errors.js';
import { ciphertextKeyVersion, type Kms } from '../kms.js';

export interface KmsContractHarness {
  /** Client under the API policy: use keys, read metadata. */
  readonly api: Kms;
  /** Client under the worker policy: additionally rotate and raise `min_decryption_version`. */
  readonly worker: Kms;
  /** Fresh keys for this run. `derived` is a derived AES key, `plain` a plain one, `signing` Ed25519. */
  readonly keys: { readonly derived: string; readonly plain: string; readonly signing: string };
  readonly cleanup?: () => Promise<void>;
}

async function codeOf(action: Promise<unknown>): Promise<string> {
  try {
    await action;
  } catch (error) {
    expect(error).toBeInstanceOf(KmsError);
    return (error as KmsError).code;
  }
  return 'NO_ERROR';
}

/**
 * The behaviour every `Kms` must have. It runs against the in-memory fake in
 * unit tests and against a real OpenBao in integration tests, which is what
 * keeps the fake honest.
 */
export function describeKmsContract(name: string, setup: () => Promise<KmsContractHarness>): void {
  describe(`Kms contract: ${name}`, () => {
    const USER_A = '0b0e7a52-5c1d-4c53-9f0a-3d2e1f4a5b6c';
    const USER_B = '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f';

    it('wraps and unwraps under a derived key bound to its context', async () => {
      const harness = await setup();
      try {
        const secret = randomBytes(32);
        const wrapped = await harness.api.encrypt(harness.keys.derived, secret, USER_A);
        expect(wrapped).toMatch(/^vault:v1:/u);
        expect(ciphertextKeyVersion(wrapped)).toBe(1);
        expect(
          (await harness.api.decrypt(harness.keys.derived, wrapped, USER_A)).equals(secret),
        ).toBe(true);

        // A wrapped key copied onto another user's row cannot be unwrapped.
        expect(await codeOf(harness.api.decrypt(harness.keys.derived, wrapped, USER_B))).toBe(
          'REJECTED',
        );
        expect(await codeOf(harness.api.decrypt(harness.keys.derived, wrapped))).toBe('REJECTED');
        expect(await codeOf(harness.api.encrypt(harness.keys.derived, secret))).toBe('REJECTED');
      } finally {
        await harness.cleanup?.();
      }
    });

    it('wraps without a context under a plain key and rejects tampering', async () => {
      const harness = await setup();
      try {
        const secret = randomBytes(32);
        const wrapped = await harness.api.encrypt(harness.keys.plain, secret);
        expect((await harness.api.decrypt(harness.keys.plain, wrapped)).equals(secret)).toBe(true);

        const body = Buffer.from(wrapped.slice(wrapped.lastIndexOf(':') + 1), 'base64');
        body[body.length - 1] = body[body.length - 1]! ^ 0x01;
        const tampered = `vault:v1:${body.toString('base64')}`;
        expect(await codeOf(harness.api.decrypt(harness.keys.plain, tampered))).toBe('REJECTED');
      } finally {
        await harness.cleanup?.();
      }
    });

    it('rotates, re-wraps in the vault, then retires the old version', async () => {
      const harness = await setup();
      try {
        const secretA = randomBytes(32);
        const secretB = randomBytes(32);
        const oldA = await harness.api.encrypt(harness.keys.derived, secretA, USER_A);
        const oldB = await harness.api.encrypt(harness.keys.derived, secretB, USER_B);

        const rotated = await harness.worker.rotateKey(harness.keys.derived);
        expect(rotated.latestVersion).toBe(2);
        expect(rotated.minDecryptionVersion).toBe(1);

        // New writes use the newest version; old ciphertext still opens.
        const fresh = await harness.api.encrypt(harness.keys.derived, secretA, USER_A);
        expect(ciphertextKeyVersion(fresh)).toBe(2);
        expect(
          (await harness.api.decrypt(harness.keys.derived, oldA, USER_A)).equals(secretA),
        ).toBe(true);

        const [newA, newB] = await harness.api.rewrap(harness.keys.derived, [
          { ciphertext: oldA, context: USER_A },
          { ciphertext: oldB, context: USER_B },
        ]);
        expect(ciphertextKeyVersion(newA!)).toBe(2);
        expect(ciphertextKeyVersion(newB!)).toBe(2);
        expect(
          (await harness.api.decrypt(harness.keys.derived, newA!, USER_A)).equals(secretA),
        ).toBe(true);
        expect(
          (await harness.api.decrypt(harness.keys.derived, newB!, USER_B)).equals(secretB),
        ).toBe(true);
        expect(await harness.api.rewrap(harness.keys.derived, [])).toEqual([]);

        await harness.worker.setMinDecryptionVersion(harness.keys.derived, 2);
        expect((await harness.api.getKeyInfo(harness.keys.derived)).minDecryptionVersion).toBe(2);
        // The old version no longer decrypts.
        expect(await codeOf(harness.api.decrypt(harness.keys.derived, oldA, USER_A))).toBe(
          'REJECTED',
        );
        expect(
          await codeOf(
            harness.api.rewrap(harness.keys.derived, [{ ciphertext: oldA, context: USER_A }]),
          ),
        ).toBe('REJECTED');
        expect(
          (await harness.api.decrypt(harness.keys.derived, newA!, USER_A)).equals(secretA),
        ).toBe(true);
      } finally {
        await harness.cleanup?.();
      }
    });

    it('signs with Ed25519 and exposes public keys per version', async () => {
      const harness = await setup();
      try {
        const message = Buffer.from('header.payload');
        const first = await harness.api.sign(harness.keys.signing, message);
        expect(first.keyVersion).toBe(1);
        expect(first.signature).toHaveLength(64);

        await harness.worker.rotateKey(harness.keys.signing);
        const second = await harness.api.sign(harness.keys.signing, message);
        expect(second.keyVersion).toBe(2);
        const pinned = await harness.api.sign(harness.keys.signing, message, 1);
        expect(pinned.keyVersion).toBe(1);

        const info = await harness.api.getKeyInfo(harness.keys.signing);
        expect(info.type).toBe('ed25519');
        expect(info.latestVersion).toBe(2);
        const publicKeyOf = (version: number): ReturnType<typeof createPublicKey> => {
          const raw = info.versions.find((entry) => entry.version === version)?.publicKey;
          expect(raw).toHaveLength(32);
          return createPublicKey({
            key: { kty: 'OKP', crv: 'Ed25519', x: raw!.toString('base64url') },
            format: 'jwk',
          });
        };
        expect(verify(null, message, publicKeyOf(1), first.signature)).toBe(true);
        expect(verify(null, message, publicKeyOf(2), second.signature)).toBe(true);
        expect(verify(null, message, publicKeyOf(1), pinned.signature)).toBe(true);
        expect(verify(null, message, publicKeyOf(1), second.signature)).toBe(false);
      } finally {
        await harness.cleanup?.();
      }
    });

    it('reports key metadata and never an exportable or deletable key', async () => {
      const harness = await setup();
      try {
        const info = await harness.api.getKeyInfo(harness.keys.derived);
        expect(info.name).toBe(harness.keys.derived);
        expect(info.type).toBe('aes256-gcm96');
        expect(info.derived).toBe(true);
        expect(info.exportable).toBe(false);
        expect(info.deletionAllowed).toBe(false);
        expect(info.latestVersion).toBe(1);
        expect(info.versions).toHaveLength(1);
        expect(info.versions[0]!.publicKey).toBeNull();
        expect(Math.abs(info.versions[0]!.createdAt.getTime() - Date.now())).toBeLessThan(
          10 * 60_000,
        );
        expect((await harness.api.getKeyInfo(harness.keys.plain)).derived).toBe(false);
      } finally {
        await harness.cleanup?.();
      }
    });

    it('denies rotation and configuration under the API policy', async () => {
      const harness = await setup();
      try {
        expect(await codeOf(harness.api.rotateKey(harness.keys.derived))).toBe('PERMISSION_DENIED');
        expect(await codeOf(harness.api.setMinDecryptionVersion(harness.keys.derived, 1))).toBe(
          'PERMISSION_DENIED',
        );
        expect((await harness.api.getKeyInfo(harness.keys.derived)).latestVersion).toBe(1);
      } finally {
        await harness.cleanup?.();
      }
    });

    it('fails clearly for a key that does not exist', async () => {
      const harness = await setup();
      try {
        expect(await codeOf(harness.api.getKeyInfo('scanova-does-not-exist'))).toBe('NOT_FOUND');
        // A real vault answers 403 here: creating a key on first use needs a capability nobody has.
        expect(['NOT_FOUND', 'PERMISSION_DENIED']).toContain(
          await codeOf(harness.api.encrypt('scanova-does-not-exist', randomBytes(32))),
        );
        await harness.api.health();
      } finally {
        await harness.cleanup?.();
      }
    });
  });
}
