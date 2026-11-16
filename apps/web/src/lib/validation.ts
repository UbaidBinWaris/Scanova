import { EmailSchema, NewPasswordSchema, PasswordInputSchema } from '@scanova/contracts/auth';
import { UuidSchema } from '@scanova/contracts/common';
import { DocumentTagsSchema, DocumentTitleSchema, MAX_TAGS } from '@scanova/contracts/documents';
import { UserStatusSchema, UserTypeSchema } from '@scanova/contracts/enums';
import { DisplayNameSchema } from '@scanova/contracts/me';
import { z } from 'zod';

/**
 * Form schemas. The browser validates with them for instant feedback, and the
 * server actions validate again with the same schemas: the client is never trusted.
 * The field rules come from @scanova/contracts, i.e. from what the API enforces.
 */

export const SignInSchema = z.object({
  email: EmailSchema,
  password: PasswordInputSchema,
  next: z.string().max(512).optional(),
});
export type SignInValues = z.input<typeof SignInSchema>;

export const ChangePasswordSchema = z
  .object({
    currentPassword: PasswordInputSchema,
    newPassword: NewPasswordSchema,
    confirmPassword: z.string(),
  })
  .refine((value) => value.newPassword === value.confirmPassword, {
    path: ['confirmPassword'],
    message: 'The passwords do not match.',
  })
  .refine((value) => value.newPassword !== value.currentPassword, {
    path: ['newPassword'],
    message: 'Choose a password different from the current one.',
  });
export type ChangePasswordValues = z.input<typeof ChangePasswordSchema>;

export const UpdateNameSchema = z.object({ name: DisplayNameSchema });
export type UpdateNameValues = z.input<typeof UpdateNameSchema>;

export const DocumentIdSchema = UuidSchema;

export const RenameDocumentSchema = z.object({ id: UuidSchema, title: DocumentTitleSchema });
export type RenameDocumentValues = z.input<typeof RenameDocumentSchema>;

export const DocumentTagsInputSchema = z.object({ id: UuidSchema, tags: DocumentTagsSchema });

/** Tags typed by a person: trimmed, case-insensitively unique, within the API's limits. */
export function normaliseTags(tags: readonly string[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const raw of tags) {
    const tag = raw.trim().replace(/\s+/gu, ' ');
    const key = tag.toLocaleLowerCase();
    if (tag !== '' && !seen.has(key)) {
      seen.add(key);
      result.push(tag);
    }
  }
  return result.slice(0, MAX_TAGS);
}

// ── Admin console ────────────────────────────────────────────────────

export const CreateUserSchema = z.object({
  email: EmailSchema,
  name: DisplayNameSchema,
  userType: UserTypeSchema,
});
export type CreateUserValues = z.input<typeof CreateUserSchema>;

const BYTES_PER_GB = 1_000_000_000;
/** The largest quota the form accepts: 100 TB. */
export const MAX_QUOTA_GB = 100_000;

/** The edit form works in decimal gigabytes; the API in bytes (null = unlimited). */
export const EditUserFormSchema = z
  .object({
    name: DisplayNameSchema,
    userType: UserTypeSchema,
    status: UserStatusSchema,
    unlimited: z.boolean(),
    quotaGb: z.string().trim(),
    /** Owner access (members only): switch the server AI on and off from the app. */
    isOwner: z.boolean(),
  })
  .superRefine((value, context) => {
    if (value.unlimited) {
      return;
    }
    const quota = Number(value.quotaGb);
    if (value.quotaGb === '' || !Number.isFinite(quota) || quota < 0 || quota > MAX_QUOTA_GB) {
      context.addIssue({
        code: 'custom',
        path: ['quotaGb'],
        message: `Enter a size between 0 and ${MAX_QUOTA_GB.toLocaleString('en-US')} GB.`,
      });
    }
  });
export type EditUserFormValues = z.input<typeof EditUserFormSchema>;

export function quotaBytesFromForm(values: { unlimited: boolean; quotaGb: string }): number | null {
  return values.unlimited ? null : Math.round(Number(values.quotaGb) * BYTES_PER_GB);
}

export function quotaGbForForm(bytes: number | null): string {
  return bytes === null ? '' : String(Math.round((bytes / BYTES_PER_GB) * 1000) / 1000);
}

export const UpdateUserSchema = z.object({
  id: UuidSchema,
  name: DisplayNameSchema.optional(),
  userType: UserTypeSchema.optional(),
  status: UserStatusSchema.optional(),
  storageQuotaBytes: z
    .number()
    .int()
    .nonnegative()
    .max(MAX_QUOTA_GB * BYTES_PER_GB)
    .nullable()
    .optional(),
  isOwner: z.boolean().optional(),
});
export type UpdateUserValues = z.input<typeof UpdateUserSchema>;

export const UserIdSchema = z.object({ id: UuidSchema });
