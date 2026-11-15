import {
  Injectable,
  StreamableFile,
  type CallHandler,
  type ExecutionContext,
  type NestInterceptor,
} from '@nestjs/common';
import { successEnvelope } from '@scanova/contracts/envelope';
import { map, type Observable } from 'rxjs';
import { Page } from './page.js';

/**
 * Wraps every successful answer in `{ data, error: null, meta }` (§7.1).
 * File downloads are the documented exception and pass through untouched.
 */
@Injectable()
export class EnvelopeInterceptor implements NestInterceptor {
  public intercept(_context: ExecutionContext, next: CallHandler): Observable<unknown> {
    return next.handle().pipe(
      map((result: unknown) => {
        if (result instanceof StreamableFile) {
          return result;
        }
        if (result instanceof Page) {
          return successEnvelope(result.items, result.nextCursor);
        }
        return successEnvelope(result);
      }),
    );
  }
}
