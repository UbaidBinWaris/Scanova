import { describe, expect, it } from 'vitest';
import { KmsError } from './errors.js';
import { InMemoryKms } from './in-memory-kms.js';
import { ciphertextKeyVersion } from './kms.js';
import { describeKmsContract } from './test-support/kms-contract.js';

describeKmsContract('InMemoryKms', () => {
  const worker = new InMemoryKms();
  worker.createKey('derived', { derived: true });
  worker.createKey('plain');
  worker.createKey('signing', { type: 'ed25519' });
  return Promise.resolve({
    api: worker.withPolicy({ allowKeyManagement: false }),
    worker,
    keys: { derived: 'derived', plain: 'plain', signing: 'signing' },
  });
});

describe('InMemoryKms specifics', () => {
  it('reports UNAVAILABLE while the vault is down and recovers afterwards', async () => {
    const kms = new InMemoryKms();
    kms.createKey('plain');
    kms.setAvailable(false);
    await expect(kms.health()).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    await expect(kms.encrypt('plain', Buffer.alloc(32))).rejects.toBeInstanceOf(KmsError);
    kms.setAvailable(true);
    await expect(kms.health()).resolves.toBeUndefined();
  });

  it('stamps versions with the injected clock', async () => {
    let now = new Date('2026-01-01T00:00:00Z');
    const kms = new InMemoryKms({ now: () => now });
    kms.createKey('plain');
    now = new Date('2026-04-01T00:00:00Z');
    const info = await kms.rotateKey('plain');
    expect(info.versions.map((version) => version.createdAt.toISOString())).toEqual([
      '2026-01-01T00:00:00.000Z',
      '2026-04-01T00:00:00.000Z',
    ]);
  });

  it('refuses a min_decryption_version beyond the newest version', async () => {
    const kms = new InMemoryKms();
    kms.createKey('plain');
    await expect(kms.setMinDecryptionVersion('plain', 2)).rejects.toMatchObject({
      code: 'REJECTED',
    });
  });

  it('parses the version out of a vault ciphertext', () => {
    expect(ciphertextKeyVersion('vault:v12:AAAA')).toBe(12);
    expect(() => ciphertextKeyVersion('v1:AAAA')).toThrow(KmsError);
    expect(() => ciphertextKeyVersion('vault:v1:')).toThrow(KmsError);
  });
});
