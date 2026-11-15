import { describe, expect, it } from 'vitest';
import { FIELDS } from './bindings.js';
import { blindIndexToken } from './blind-index.js';
import {
  MAX_SEARCH_TOKENS_PER_DOCUMENT,
  documentSearchTokens,
  openDocumentMetadata,
  openDocumentText,
  openPageEdits,
  querySearchTokens,
  sealDocumentMetadata,
  sealDocumentText,
  sealPageEdits,
} from './document.js';
import { CryptoError } from './errors.js';
import { decryptFieldToString, encryptField } from './field.js';
import { deriveFieldKey } from './hierarchy.js';

const DOCUMENT_KEY = Buffer.from(Array.from({ length: 32 }, (_, index) => 32 + index));
const OTHER_KEY = Buffer.alloc(32, 9);
const INDEX_KEY = Buffer.from(Array.from({ length: 32 }, (_, index) => 64 + index));
const DOCUMENT_ID = '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f';
const OTHER_ID = '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e50';

describe('document fields', () => {
  it('round-trips title, tags and text under the derived field key', () => {
    const sealed = sealDocumentMetadata(DOCUMENT_KEY, DOCUMENT_ID, {
      title: 'Invoice 2026',
      tags: ['tax', 'رسید'],
    });
    expect(openDocumentMetadata(DOCUMENT_KEY, DOCUMENT_ID, sealed)).toEqual({
      title: 'Invoice 2026',
      tags: ['tax', 'رسید'],
    });
    const text = sealDocumentText(DOCUMENT_KEY, DOCUMENT_ID, 'Total due: 1,250.00');
    expect(openDocumentText(DOCUMENT_KEY, DOCUMENT_ID, text)).toBe('Total due: 1,250.00');
    // It is the documented derivation, not the DOCUMENT key itself.
    expect(
      decryptFieldToString(
        deriveFieldKey(DOCUMENT_KEY),
        FIELDS.documentTitle(DOCUMENT_ID),
        sealed.titleEnc,
      ),
    ).toBe('Invoice 2026');
    expect(() =>
      decryptFieldToString(DOCUMENT_KEY, FIELDS.documentTitle(DOCUMENT_ID), sealed.titleEnc),
    ).toThrow(CryptoError);
  });

  it('binds each value to its document and its column', () => {
    const sealed = sealDocumentMetadata(DOCUMENT_KEY, DOCUMENT_ID, { title: 'Invoice', tags: [] });
    expect(() => openDocumentMetadata(DOCUMENT_KEY, OTHER_ID, sealed)).toThrow(CryptoError);
    expect(() => openDocumentMetadata(OTHER_KEY, DOCUMENT_ID, sealed)).toThrow(CryptoError);
    // Title and tags swapped between columns do not decrypt.
    expect(() =>
      openDocumentMetadata(DOCUMENT_KEY, DOCUMENT_ID, {
        titleEnc: sealed.tagsEnc,
        tagsEnc: sealed.titleEnc,
      }),
    ).toThrow(CryptoError);
    const text = sealDocumentText(DOCUMENT_KEY, DOCUMENT_ID, 'body');
    expect(() => openDocumentText(DOCUMENT_KEY, OTHER_ID, text)).toThrow(CryptoError);
  });

  it('rejects stored tags that are not a list of strings', () => {
    const fieldKey = deriveFieldKey(DOCUMENT_KEY);
    const sealed = sealDocumentMetadata(DOCUMENT_KEY, DOCUMENT_ID, { title: 'x', tags: [] });
    const tagsEnc = encryptField(
      fieldKey,
      FIELDS.documentTags(DOCUMENT_ID),
      JSON.stringify({ not: 'a list' }),
    );
    expect(() =>
      openDocumentMetadata(DOCUMENT_KEY, DOCUMENT_ID, { titleEnc: sealed.titleEnc, tagsEnc }),
    ).toThrow(/tags/u);
  });
});

describe('page recipes', () => {
  const PAGE_ID = '0b0e7a52-5c1d-4c53-9f0a-3d2e1f4a5b6c';
  const OTHER_PAGE_ID = '0b0e7a52-5c1d-4c53-9f0a-3d2e1f4a5b6d';
  const recipe = JSON.stringify({ v: 1, rotation: 90, filter: 'enhanced' });

  it('round-trips recipes under the document field key, bound to their page', () => {
    const [sealed, other] = sealPageEdits(DOCUMENT_KEY, [
      { pageId: PAGE_ID, json: recipe },
      { pageId: OTHER_PAGE_ID, json: '{"v":1}' },
    ]);
    expect(sealed![0]).toBe(0x01);
    expect(sealed!.includes(Buffer.from('enhanced'))).toBe(false);
    expect(
      openPageEdits(DOCUMENT_KEY, [
        { pageId: PAGE_ID, editEnc: sealed! },
        { pageId: OTHER_PAGE_ID, editEnc: other! },
      ]),
    ).toEqual([recipe, '{"v":1}']);
    expect(
      decryptFieldToString(deriveFieldKey(DOCUMENT_KEY), FIELDS.pageEdit(PAGE_ID), sealed!),
    ).toBe(recipe);
  });

  it('refuses a recipe moved to another page, another document or another column', () => {
    const [sealed] = sealPageEdits(DOCUMENT_KEY, [{ pageId: PAGE_ID, json: recipe }]);
    expect(() =>
      openPageEdits(DOCUMENT_KEY, [{ pageId: OTHER_PAGE_ID, editEnc: sealed! }]),
    ).toThrow(CryptoError);
    expect(() => openPageEdits(OTHER_KEY, [{ pageId: PAGE_ID, editEnc: sealed! }])).toThrow(
      CryptoError,
    );
    const title = sealDocumentMetadata(DOCUMENT_KEY, PAGE_ID, { title: recipe, tags: [] }).titleEnc;
    expect(() => openPageEdits(DOCUMENT_KEY, [{ pageId: PAGE_ID, editEnc: title }])).toThrow(
      CryptoError,
    );
  });
});

describe('document search tokens', () => {
  it('indexes the distinct words of title, tags and text', () => {
    const tokens = documentSearchTokens(INDEX_KEY, {
      title: 'Tax Return',
      tags: ['2026', 'tax'],
      text: 'return of income',
    });
    const expected = ['tax', 'return', '2026', 'of', 'income'].map((word) =>
      blindIndexToken(INDEX_KEY, word).toString('hex'),
    );
    expect(tokens.map((token) => token.toString('hex'))).toEqual(expected);
    expect(documentSearchTokens(INDEX_KEY, { title: 'Tax', tags: [], text: null })).toHaveLength(1);
  });

  it('matches a query word to the same token, whatever its case or width', () => {
    const [token] = querySearchTokens(INDEX_KEY, 'ＴＡＸ');
    expect(token!.equals(blindIndexToken(INDEX_KEY, 'tax'))).toBe(true);
    expect(querySearchTokens(INDEX_KEY, '  ,;  ')).toEqual([]);
    expect(querySearchTokens(INDEX_KEY, 'tax return tax')).toHaveLength(2);
  });

  it('caps the tokens of one document but always keeps title and tags', () => {
    const text = Array.from(
      { length: MAX_SEARCH_TOKENS_PER_DOCUMENT + 500 },
      (_, index) => `w${index}`,
    ).join(' ');
    const tokens = documentSearchTokens(INDEX_KEY, { title: 'Deed', tags: ['house'], text });
    expect(tokens).toHaveLength(MAX_SEARCH_TOKENS_PER_DOCUMENT);
    expect(tokens[0]!.equals(blindIndexToken(INDEX_KEY, 'deed'))).toBe(true);
    expect(tokens[1]!.equals(blindIndexToken(INDEX_KEY, 'house'))).toBe(true);
  });
});
