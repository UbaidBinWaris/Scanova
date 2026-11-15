import type { PipeTransform } from '@nestjs/common';
import type { ValidationDetail } from '@scanova/contracts/errors';
import type { z } from 'zod';
import { AppError } from './app-error.js';

/** Turns Zod issues into the `details` of a `VALIDATION_FAILED` answer. Field names and reasons, no values. */
export function toValidationDetails(error: z.ZodError, root: string): ValidationDetail[] {
  return error.issues.map((issue) => ({
    field: issue.path.length > 0 ? issue.path.map(String).join('.') : root,
    message: issue.message,
  }));
}

/** Validates one part of a request against a contract schema. */
export class ZodPipe<Schema extends z.ZodType> implements PipeTransform<unknown, z.infer<Schema>> {
  public constructor(
    private readonly schema: Schema,
    private readonly root: string,
  ) {}

  public transform(value: unknown): z.infer<Schema> {
    const result = this.schema.safeParse(value);
    if (!result.success) {
      throw new AppError('VALIDATION_FAILED', {
        details: toValidationDetails(result.error, this.root),
      });
    }
    return result.data;
  }
}

/** `@Body(body(LoginRequestSchema)) request: LoginRequest` */
export function body<Schema extends z.ZodType>(schema: Schema): ZodPipe<Schema> {
  return new ZodPipe(schema, 'body');
}

export function query<Schema extends z.ZodType>(schema: Schema): ZodPipe<Schema> {
  return new ZodPipe(schema, 'query');
}

export function params<Schema extends z.ZodType>(schema: Schema): ZodPipe<Schema> {
  return new ZodPipe(schema, 'path');
}
