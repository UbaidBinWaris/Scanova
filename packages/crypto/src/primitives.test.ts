import { createHmac } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { aesGcmDecrypt, aesGcmEncrypt, open, seal } from './aead.js';
import {
  blindIndexToken,
  blindIndexTokens,
  emailLookupIndex,
  normalizeEmail,
  tokenizeWords,
} from './blind-index.js';
import { constantTimeEqual } from './constant-time.js';
import { CryptoError } from './errors.js';
import { decryptField, decryptFieldToString, encryptField } from './field.js';
import {
  dataKeyAad,
  deriveFieldKey,
  deriveKeyWrapKey,
  deriveObjectKey,
  fieldAad,
  type DataKeyBinding,
} from './hierarchy.js';
import { hkdfSha256 } from './hkdf.js';
import { WRAPPED_DATA_KEY_BYTES, unwrapDataKey, wrapDataKey } from './key-wrap.js';
import { hashPassword, needsRehash, verifyPassword } from './password.js';
import { generateTemporaryPassword, randomKey, randomToken, sha256 } from './random.js';
import { zeroize } from './zeroize.js';

const hex = (value: string): Buffer => Buffer.from(value, 'hex');
const range = (start: number, length: number): Buffer =>
  Buffer.from(Array.from({ length }, (_, index) => start + index));

const USER_ID = '0b0e7a52-5c1d-4c53-9f0a-3d2e1f4a5b6c';
const DOCUMENT_ID = '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f';
const OBJECT_ID = 'c2a4e6f8-1b3d-4f5a-8c7e-9d0b1a2c3e4f';
const USER_KEY = range(0, 32);
const DOCUMENT_KEY = range(32, 32);
const INDEX_KEY = range(64, 32);
const NONCE = range(0xa0, 12);

function expectCode(action: () => unknown, code: CryptoError['code']): void {
  let caught: unknown;
  try {
    action();
  } catch (error) {
    caught = error;
  }
  expect(caught).toBeInstanceOf(CryptoError);
  expect((caught as CryptoError).code).toBe(code);
}

describe('AES-256-GCM (NIST GCM specification test cases)', () => {
  it('test case 13: empty plaintext, zero key', () => {
    const out = aesGcmEncrypt(Buffer.alloc(32), Buffer.alloc(12), Buffer.alloc(0), Buffer.alloc(0));
    expect(out.toString('hex')).toBe('530f8afbc74536b9a963b4f1c4cb738b');
  });

  it('test case 14: one zero block', () => {
    const out = aesGcmEncrypt(
      Buffer.alloc(32),
      Buffer.alloc(12),
      Buffer.alloc(16),
      Buffer.alloc(0),
    );
    expect(out.toString('hex')).toBe(
      'cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919',
    );
  });

  it('test case 16: with associated data', () => {
    const key = hex('feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308');
    const nonce = hex('cafebabefacedbaddecaf888');
    const aad = hex('feedfacedeadbeeffeedfacedeadbeefabaddad2');
    const plaintext = hex(
      'd9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39',
    );
    const expected =
      '522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f66276fc6ece0f4e1768cddf8853bb2d551b';
    const out = aesGcmEncrypt(key, nonce, plaintext, aad);
    expect(out.toString('hex')).toBe(expected);
    expect(aesGcmDecrypt(key, nonce, out, aad).equals(plaintext)).toBe(true);
  });

  it('rejects wrong key and nonce sizes', () => {
    expectCode(
      () => aesGcmEncrypt(Buffer.alloc(16), NONCE, Buffer.alloc(1), Buffer.alloc(0)),
      'INVALID_INPUT',
    );
    expectCode(
      () => aesGcmEncrypt(USER_KEY, Buffer.alloc(16), Buffer.alloc(1), Buffer.alloc(0)),
      'INVALID_INPUT',
    );
  });
});

describe('sealed values', () => {
  const aad = Buffer.from('context');

  it('round-trips, including the empty value', () => {
    for (const plaintext of [
      Buffer.alloc(0),
      Buffer.from('x'),
      randomKey(),
      Buffer.alloc(5000, 7),
    ]) {
      const sealed = seal(USER_KEY, plaintext, aad);
      expect(sealed[0]).toBe(0x01);
      expect(sealed.length).toBe(plaintext.length + 29);
      expect(open(USER_KEY, sealed, aad).equals(plaintext)).toBe(true);
    }
  });

  it('uses a fresh nonce every time', () => {
    const a = seal(USER_KEY, Buffer.from('same'), aad);
    const b = seal(USER_KEY, Buffer.from('same'), aad);
    expect(a.equals(b)).toBe(false);
  });

  it('rejects a flipped bit in every region', () => {
    const sealed = seal(USER_KEY, Buffer.from('attack at dawn'), aad);
    for (let position = 0; position < sealed.length; position += 1) {
      const tampered = Buffer.from(sealed);
      tampered[position] = tampered[position]! ^ 0x01;
      expectCode(
        () => open(USER_KEY, tampered, aad),
        position === 0 ? 'INVALID_FORMAT' : 'AUTHENTICATION_FAILED',
      );
    }
  });

  it('rejects truncation, wrong associated data and the wrong key', () => {
    const sealed = seal(USER_KEY, Buffer.from('attack at dawn'), aad);
    expectCode(
      () => open(USER_KEY, sealed.subarray(0, sealed.length - 1), aad),
      'AUTHENTICATION_FAILED',
    );
    expectCode(() => open(USER_KEY, sealed.subarray(0, 20), aad), 'INVALID_FORMAT');
    expectCode(() => open(USER_KEY, sealed, Buffer.from('contexT')), 'AUTHENTICATION_FAILED');
    expectCode(() => open(USER_KEY, sealed, Buffer.alloc(0)), 'AUTHENTICATION_FAILED');
    expectCode(() => open(DOCUMENT_KEY, sealed, aad), 'AUTHENTICATION_FAILED');
  });
});

describe('HKDF-SHA256 (RFC 5869)', () => {
  it('test case 1', () => {
    const okm = hkdfSha256(
      Buffer.alloc(22, 0x0b),
      hex('f0f1f2f3f4f5f6f7f8f9'),
      42,
      hex('000102030405060708090a0b0c'),
    );
    expect(okm.toString('hex')).toBe(
      '3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865',
    );
  });

  it('test case 3: empty salt and info', () => {
    const okm = hkdfSha256(Buffer.alloc(22, 0x0b), '', 42);
    expect(okm.toString('hex')).toBe(
      '8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8',
    );
  });
});

describe('key hierarchy', () => {
  it('derives the documented sub-keys (fixed vectors)', () => {
    expect(deriveKeyWrapKey(USER_KEY).toString('hex')).toBe(
      'ce80c791befbce20372c5fb2ce5f85ce9242063714fd73f0c13dc4a48eedfd83',
    );
    expect(deriveFieldKey(DOCUMENT_KEY).toString('hex')).toBe(
      'e9fceb6f54a730448e6a032e25867ecd92f39c1a4989662bd27054bb329624c1',
    );
    expect(deriveObjectKey(DOCUMENT_KEY, OBJECT_ID).toString('hex')).toBe(
      '9fb23dedb79f73770036a383417a121f741e180a26303b071a118a81255a79c9',
    );
  });

  it('gives every object its own stream key', () => {
    const other = deriveObjectKey(DOCUMENT_KEY, 'c2a4e6f8-1b3d-4f5a-8c7e-9d0b1a2c3e40');
    expect(other.equals(deriveObjectKey(DOCUMENT_KEY, OBJECT_ID))).toBe(false);
    expect(other.equals(deriveFieldKey(DOCUMENT_KEY))).toBe(false);
  });

  it('builds the documented associated-data strings', () => {
    expect(
      dataKeyAad({
        scope: 'DOCUMENT',
        userId: USER_ID,
        refId: DOCUMENT_ID,
        userKeyVersion: 3,
      }).toString(),
    ).toBe(`scanova:key:v1|DOCUMENT|${USER_ID}|${DOCUMENT_ID}|3`);
    expect(
      fieldAad({ table: 'documents', column: 'title_enc', rowId: DOCUMENT_ID }).toString(),
    ).toBe(`scanova:field:v1|documents.title_enc|${DOCUMENT_ID}`);
  });

  it('refuses components that could forge a field boundary', () => {
    expectCode(
      () => fieldAad({ table: 'documents', column: 'title_enc', rowId: 'a|b' }),
      'INVALID_INPUT',
    );
    expectCode(
      () => fieldAad({ table: '', column: 'title_enc', rowId: DOCUMENT_ID }),
      'INVALID_INPUT',
    );
    expectCode(
      () =>
        dataKeyAad({ scope: 'PROFILE', userId: `${USER_ID}|x`, refId: USER_ID, userKeyVersion: 1 }),
      'INVALID_INPUT',
    );
    expectCode(
      () => dataKeyAad({ scope: 'PROFILE', userId: USER_ID, refId: USER_ID, userKeyVersion: 0 }),
      'INVALID_INPUT',
    );
    expectCode(() => deriveObjectKey(DOCUMENT_KEY, 'a|b'), 'INVALID_INPUT');
    expectCode(() => deriveKeyWrapKey(Buffer.alloc(16)), 'INVALID_INPUT');
  });
});

describe('data-key wrap', () => {
  const binding: DataKeyBinding = {
    scope: 'DOCUMENT',
    userId: USER_ID,
    refId: DOCUMENT_ID,
    userKeyVersion: 3,
  };
  const wrapKey = deriveKeyWrapKey(USER_KEY);

  it('matches the fixed vector: 0x01 ‖ nonce(12) ‖ ciphertext(32) ‖ tag(16)', () => {
    const wrapped = wrapDataKey(wrapKey, DOCUMENT_KEY, binding, NONCE);
    expect(wrapped.length).toBe(WRAPPED_DATA_KEY_BYTES);
    expect(wrapped.length).toBe(61);
    expect(wrapped.toString('hex')).toBe(
      '01a0a1a2a3a4a5a6a7a8a9aaab6b351477aeeeb1b106bd677d5eab87eed9e11a3792e4658ca9fc1e946b0000e8776bb4e56f9334331127392735e8f2c7',
    );
    expect(unwrapDataKey(wrapKey, wrapped, binding).equals(DOCUMENT_KEY)).toBe(true);
  });

  it('cannot be unwrapped under a different scope, owner, reference or user-key version', () => {
    const wrapped = wrapDataKey(wrapKey, DOCUMENT_KEY, binding);
    const others: DataKeyBinding[] = [
      { ...binding, scope: 'PROFILE' },
      { ...binding, userId: '0b0e7a52-5c1d-4c53-9f0a-3d2e1f4a5b6d' },
      { ...binding, refId: '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e50' },
      { ...binding, userKeyVersion: 4 },
    ];
    for (const other of others) {
      expectCode(() => unwrapDataKey(wrapKey, wrapped, other), 'AUTHENTICATION_FAILED');
    }
  });

  it('rejects a flipped bit, truncation and the wrong user key', () => {
    const wrapped = wrapDataKey(wrapKey, DOCUMENT_KEY, binding);
    const tampered = Buffer.from(wrapped);
    tampered[20] = tampered[20]! ^ 0x04;
    expectCode(() => unwrapDataKey(wrapKey, tampered, binding), 'AUTHENTICATION_FAILED');
    expectCode(() => unwrapDataKey(wrapKey, wrapped.subarray(0, 60), binding), 'INVALID_FORMAT');
    expectCode(
      () => unwrapDataKey(deriveKeyWrapKey(DOCUMENT_KEY), wrapped, binding),
      'AUTHENTICATION_FAILED',
    );
    expectCode(() => wrapDataKey(wrapKey, Buffer.alloc(31), binding), 'INVALID_INPUT');
  });
});

describe('field encryption', () => {
  const fieldKey = deriveFieldKey(DOCUMENT_KEY);
  const binding = { table: 'documents', column: 'title_enc', rowId: DOCUMENT_ID };

  it('matches the fixed vector', () => {
    const sealed = encryptField(fieldKey, binding, 'Invoice 2026 — رسید', NONCE);
    expect(sealed.toString('hex')).toBe(
      '01a0a1a2a3a4a5a6a7a8a9aaab7cf1e2e8d17d43959bf60c9870c2d5b3ed71958279229130a37cfef9d456f2dd4e6c5c404c7a86db61',
    );
    expect(decryptFieldToString(fieldKey, binding, sealed)).toBe('Invoice 2026 — رسید');
  });

  it('fails when moved to another column or row, or read with another key', () => {
    const sealed = encryptField(fieldKey, binding, 'secret title');
    expectCode(
      () => decryptField(fieldKey, { ...binding, column: 'tags_enc' }, sealed),
      'AUTHENTICATION_FAILED',
    );
    expectCode(
      () => decryptField(fieldKey, { ...binding, table: 'users' }, sealed),
      'AUTHENTICATION_FAILED',
    );
    expectCode(
      () =>
        decryptField(
          fieldKey,
          { ...binding, rowId: '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e50' },
          sealed,
        ),
      'AUTHENTICATION_FAILED',
    );
    expectCode(
      () => decryptField(deriveFieldKey(USER_KEY), binding, sealed),
      'AUTHENTICATION_FAILED',
    );
  });
});

describe('blind index', () => {
  it('matches the fixed vectors: first 16 bytes of HMAC-SHA256', () => {
    expect(blindIndexToken(INDEX_KEY, 'invoice').toString('hex')).toBe(
      'e70d4b920f85504498e1b574aa38db19',
    );
    expect(blindIndexToken(INDEX_KEY, 'رسید').toString('hex')).toBe(
      '87ac2b42340dd712cdda580811513194',
    );
  });

  it('normalises with NFKC and lower case on both sides', () => {
    const expected = blindIndexToken(INDEX_KEY, 'invoice');
    expect(blindIndexToken(INDEX_KEY, 'INVOICE').equals(expected)).toBe(true);
    expect(blindIndexToken(INDEX_KEY, 'ＩＮＶＯＩＣＥ').equals(expected)).toBe(true);
  });

  it('tokenises text into unique words in any script', () => {
    expect(tokenizeWords('Invoice #42, invoice; total: 1,250.00 PKR — رسید نمبر')).toEqual([
      'invoice',
      '42',
      'total',
      '1',
      '250',
      '00',
      'pkr',
      'رسید',
      'نمبر',
    ]);
    expect(tokenizeWords('')).toEqual([]);
    expect(tokenizeWords(`ok ${'x'.repeat(65)}`)).toEqual(['ok']);
  });

  it('produces one token per distinct word, keyed per user', () => {
    const tokens = blindIndexTokens(INDEX_KEY, 'Tax tax return 2026');
    expect(tokens).toHaveLength(3);
    expect(tokens[0]!.equals(blindIndexToken(INDEX_KEY, 'tax'))).toBe(true);
    expect(blindIndexToken(USER_KEY, 'tax').equals(tokens[0]!)).toBe(false);
    expectCode(() => blindIndexToken(Buffer.alloc(8), 'tax'), 'INVALID_INPUT');
  });

  it('builds the 32-byte e-mail lookup index from the trimmed lower-cased address', () => {
    const expected = 'eec7fe5261cce6eb4eea5072cc8e0422cd4f98c8f63f5d7806ed1e5be044c13a';
    expect(emailLookupIndex(INDEX_KEY, 'member@example.org').toString('hex')).toBe(expected);
    expect(emailLookupIndex(INDEX_KEY, '  Member@Example.ORG ').toString('hex')).toBe(expected);
    expect(normalizeEmail('  Member@Example.ORG ')).toBe('member@example.org');
  });

  it('agrees with RFC 4231 test case 2 for the underlying HMAC', () => {
    const mac = createHmac('sha256', 'Jefe').update('what do ya want for nothing?').digest('hex');
    expect(mac).toBe('5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843');
  });
});

describe('Argon2id passwords', () => {
  const fast = { memoryKib: 19_456, timeCost: 2, parallelism: 1 };

  it('verifies the Argon2 reference vector', async () => {
    const reference =
      '$argon2id$v=19$m=65536,t=2,p=1$c29tZXNhbHQ$CTFhFdXPJO1aFaMaO6Mm5c8y7cJHAph8ArZWb2GRPPc';
    expect(await verifyPassword(reference, 'password')).toBe(true);
    expect(await verifyPassword(reference, 'Password')).toBe(false);
  });

  it('hashes with a random salt and verifies', async () => {
    const a = await hashPassword('correct horse battery staple', fast);
    const b = await hashPassword('correct horse battery staple', fast);
    expect(a).toMatch(/^\$argon2id\$v=19\$m=19456,t=2,p=1\$/u);
    expect(a).not.toBe(b);
    expect(await verifyPassword(a, 'correct horse battery staple')).toBe(true);
    expect(await verifyPassword(a, 'correct horse battery stapl')).toBe(false);
  });

  it('answers "no" for malformed hashes instead of throwing', async () => {
    expect(await verifyPassword('', 'x')).toBe(false);
    expect(await verifyPassword('$argon2i$v=19$m=16,t=2,p=1$c29tZXNhbHQ$AAAA', 'x')).toBe(false);
    expect(await verifyPassword('plaintext', 'plaintext')).toBe(false);
    await expect(hashPassword('', fast)).rejects.toBeInstanceOf(CryptoError);
  });

  it('asks for a re-hash exactly when the parameters changed', async () => {
    const encoded = await hashPassword('pw-pw-pw-pw-pw', fast);
    expect(needsRehash(encoded, fast)).toBe(false);
    expect(needsRehash(encoded, { ...fast, timeCost: 3 })).toBe(true);
    expect(needsRehash(encoded, { ...fast, memoryKib: 65_536 })).toBe(true);
    expect(needsRehash(encoded, { ...fast, parallelism: 2 })).toBe(true);
    expect(needsRehash('garbage', fast)).toBe(true);
  });
});

describe('helpers', () => {
  it('compares secrets in constant time regardless of length', () => {
    expect(constantTimeEqual('service-token', 'service-token')).toBe(true);
    expect(constantTimeEqual('service-token', 'service-tokeN')).toBe(false);
    expect(constantTimeEqual('short', 'a much longer value')).toBe(false);
    expect(constantTimeEqual('', '')).toBe(true);
    expect(constantTimeEqual(Buffer.from('ab'), 'ab')).toBe(true);
  });

  it('overwrites key buffers', () => {
    const a = randomKey();
    const b = randomKey();
    zeroize(a, null, undefined, b);
    expect(a.equals(Buffer.alloc(32))).toBe(true);
    expect(b.equals(Buffer.alloc(32))).toBe(true);
  });

  it('generates keys, tokens and temporary passwords', () => {
    expect(randomKey()).toHaveLength(32);
    expect(randomKey().equals(randomKey())).toBe(false);
    expect(Buffer.from(randomToken(), 'base64url')).toHaveLength(32);
    expect(sha256('abc').toString('hex')).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    );
    const password = generateTemporaryPassword();
    expect(password).toMatch(/^[A-HJ-NP-Za-km-z2-9]{20}$/u);
    expect(generateTemporaryPassword()).not.toBe(password);
  });
});
