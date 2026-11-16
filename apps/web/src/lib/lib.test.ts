import type { Document } from '@scanova/contracts/documents';
import { describe, expect, it } from 'vitest';
import { contentDisposition, pageFileName, safeFileName } from './filename';
import { formatBytes, usagePercent } from './format';
import { sortDocuments } from './sorting';
import { describeBrowser } from './user-agent';
import {
  ChangePasswordSchema,
  EditUserFormSchema,
  normaliseTags,
  quotaBytesFromForm,
  quotaGbForForm,
  SignInSchema,
  UpdateUserSchema,
} from './validation';

describe('file names', () => {
  it('keeps the title readable but strips separators, control and reserved characters', () => {
    expect(safeFileName('Tax 2026', 'pdf')).toBe('Tax 2026.pdf');
    expect(safeFileName('../../etc/passwd', 'pdf')).toBe('.. .. etc passwd.pdf');
    expect(safeFileName('a\u0000b\r\nc:"d"', 'pdf')).toBe('a b c d.pdf');
    expect(safeFileName('   ', 'pdf')).toBe('document.pdf');
    expect(safeFileName('x'.repeat(300), 'pdf')).toHaveLength(124);
    expect(pageFileName('Invoice', 3, 12)).toBe('Invoice — page 03.jpg');
  });

  it('writes a Content-Disposition with an ASCII fallback and the UTF-8 name', () => {
    expect(contentDisposition('attachment', 'رسید فروری', 'pdf')).toBe(
      `attachment; filename="____ _____.pdf"; filename*=UTF-8''%D8%B1%D8%B3%DB%8C%D8%AF%20%D9%81%D8%B1%D9%88%D8%B1%DB%8C.pdf`,
    );
    const tricky = contentDisposition('attachment', `a"; filename=evil.exe; x='`, 'pdf');
    // One quoted ASCII name and one encoded UTF-8 name: nothing can close the quotes early.
    expect(tricky).toMatch(/^attachment; filename="[^"]*"; filename\*=UTF-8''[^;"\s]+$/u);
  });
});

describe('formatting', () => {
  it('uses decimal units like the app', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(999)).toBe('999 B');
    expect(formatBytes(1500)).toBe('1.5 KB');
    expect(formatBytes(2_000_000_000)).toBe('2 GB');
    expect(formatBytes(123_456_789)).toBe('123 MB');
    expect(formatBytes(null)).toBe('—');
  });

  it('computes the share of a quota, unlimited as null', () => {
    expect(usagePercent(500, 1000)).toBe(50);
    expect(usagePercent(5000, 1000)).toBe(100);
    expect(usagePercent(1, null)).toBeNull();
    expect(usagePercent(0, 0)).toBe(100);
  });
});

describe('browser names', () => {
  it('names common browsers and systems', () => {
    expect(
      describeBrowser('Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0'),
    ).toBe('Firefox on Linux');
    expect(
      describeBrowser(
        'Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36',
      ),
    ).toBe('Chrome on Android');
    expect(
      describeBrowser(
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36 Edg/141.0',
      ),
    ).toBe('Edge on Windows');
    expect(
      describeBrowser(
        'Mozilla/5.0 (iPhone; CPU iPhone OS 19_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/19.0 Mobile/15E148 Safari/604.1',
      ),
    ).toBe('Safari on iPhone');
    expect(describeBrowser(null)).toBe('Web browser');
  });
});

describe('form validation', () => {
  it('validates sign-in input like the API', () => {
    expect(SignInSchema.safeParse({ email: ' person@example.org ', password: 'x' }).success).toBe(
      true,
    );
    expect(SignInSchema.safeParse({ email: 'not-an-email', password: 'x' }).success).toBe(false);
    expect(SignInSchema.safeParse({ email: 'person@example.org', password: '' }).success).toBe(
      false,
    );
  });

  it('enforces the password policy and a matching confirmation', () => {
    const base = {
      currentPassword: 'temporary-password',
      newPassword: 'a long new password',
      confirmPassword: 'a long new password',
    };
    expect(ChangePasswordSchema.safeParse(base).success).toBe(true);
    expect(
      ChangePasswordSchema.safeParse({ ...base, confirmPassword: 'something else' }).success,
    ).toBe(false);
    expect(
      ChangePasswordSchema.safeParse({ ...base, newPassword: 'short', confirmPassword: 'short' })
        .success,
    ).toBe(false);
    expect(
      ChangePasswordSchema.safeParse({
        ...base,
        newPassword: base.currentPassword,
        confirmPassword: base.currentPassword,
      }).success,
    ).toBe(false);
  });

  it('converts the quota between gigabytes and bytes', () => {
    expect(quotaBytesFromForm({ unlimited: false, quotaGb: '2' })).toBe(2_000_000_000);
    expect(quotaBytesFromForm({ unlimited: false, quotaGb: '0.5' })).toBe(500_000_000);
    expect(quotaBytesFromForm({ unlimited: true, quotaGb: '2' })).toBeNull();
    expect(quotaGbForForm(10_737_418_240)).toBe('10.737');
    expect(quotaGbForForm(null)).toBe('');
    const form = { name: 'A', userType: 'MEMBER', status: 'ACTIVE' } as const;
    expect(
      EditUserFormSchema.safeParse({ ...form, unlimited: false, quotaGb: 'lots' }).success,
    ).toBe(false);
    expect(EditUserFormSchema.safeParse({ ...form, unlimited: false, quotaGb: '-1' }).success).toBe(
      false,
    );
    expect(EditUserFormSchema.safeParse({ ...form, unlimited: true, quotaGb: '' }).success).toBe(
      true,
    );
    expect(
      UpdateUserSchema.safeParse({
        id: '6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e5f',
        storageQuotaBytes: 1.5,
      }).success,
    ).toBe(false);
  });

  it('normalises tags: trimmed, unique regardless of case, at most 32', () => {
    expect(normaliseTags([' Tax ', 'tax', 'Receipts', '', 'a  b'])).toEqual([
      'Tax',
      'Receipts',
      'a b',
    ]);
    expect(normaliseTags(Array.from({ length: 40 }, (_, index) => `t${index}`))).toHaveLength(32);
  });
});

describe('document sorting', () => {
  const document = (
    title: string,
    updatedAt: string,
    sizeBytes: number,
    capturedAt = updatedAt,
  ): Document => ({
    id: `6f1d7c1e-2f3a-4b5c-8d9e-0a1b2c3d4e${String(sizeBytes).padStart(2, '0')}`,
    title,
    tags: [],
    pageCount: 1,
    sizeBytes,
    contentSha256: null,
    status: 'READY',
    ocrStatus: 'DONE',
    hasSearchablePdf: false,
    capturedAt,
    createdAt: updatedAt,
    updatedAt,
    deletedAt: null,
    pagesUpdatedAt: null,
  });
  const list = [
    document('b 10', '2026-11-02T00:00:00.000Z', 30),
    document('B 9', '2026-11-03T00:00:00.000Z', 10, '2026-10-01T00:00:00.000Z'),
    document('a', '2026-11-01T00:00:00.000Z', 20),
  ];

  it('sorts by change, title (natural, case-insensitive), scan date and size', () => {
    expect(sortDocuments(list, 'recent').map((item) => item.title)).toEqual(['B 9', 'b 10', 'a']);
    expect(sortDocuments(list, 'oldest').map((item) => item.title)).toEqual(['a', 'b 10', 'B 9']);
    expect(sortDocuments(list, 'title-asc').map((item) => item.title)).toEqual([
      'a',
      'B 9',
      'b 10',
    ]);
    expect(sortDocuments(list, 'title-desc').map((item) => item.title)).toEqual([
      'b 10',
      'B 9',
      'a',
    ]);
    expect(sortDocuments(list, 'captured').map((item) => item.title)).toEqual(['b 10', 'a', 'B 9']);
    expect(sortDocuments(list, 'largest').map((item) => item.title)).toEqual(['b 10', 'a', 'B 9']);
    expect(list.map((item) => item.title)).toEqual(['b 10', 'B 9', 'a']);
  });
});
