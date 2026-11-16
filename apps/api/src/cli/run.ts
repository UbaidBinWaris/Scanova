import { parseArgs, type ParseArgsConfig } from 'node:util';
import type { INestApplicationContext } from '@nestjs/common';
import { EmailSchema } from '@scanova/contracts/auth';
import { UuidSchema } from '@scanova/contracts/common';
import type { UserType } from '@scanova/contracts/enums';
import { DisplayNameSchema } from '@scanova/contracts/me';
import type { User } from '@scanova/database/client';
import type { z } from 'zod';
import { AuditService } from '../audit/audit.service.js';
import { AppError } from '../common/app-error.js';
import { CLI_ACTOR } from '../internal/admin/actor.js';
import { AdminKeysService } from '../internal/admin/admin-keys.service.js';
import { AdminUsersService } from '../internal/admin/admin-users.service.js';
import { UsersService } from '../users/users.service.js';

/** Where command output goes. Results on `out`, problems on `err`. */
export interface CliIo {
  out(line: string): void;
  err(line: string): void;
}

type Options = NonNullable<ParseArgsConfig['options']>;
type Values = Record<string, string | boolean | undefined>;

interface Command {
  readonly usage: string;
  readonly summary: string;
  readonly options: Options;
  run(app: INestApplicationContext, values: Values, io: CliIo): Promise<void>;
}

const USER_SELECTOR: Options = { email: { type: 'string' }, id: { type: 'string' } };

/** Thrown for bad command-line input; printed without a stack trace. */
class UsageError extends Error {}

function parseWith<Schema extends z.ZodType>(
  schema: Schema,
  value: unknown,
  option: string,
): z.infer<Schema> {
  const result = schema.safeParse(value);
  if (!result.success) {
    throw new UsageError(`--${option}: ${result.error.issues[0]?.message ?? 'invalid value'}`);
  }
  return result.data;
}

function requireString(values: Values, option: string): string {
  const value = values[option];
  if (typeof value !== 'string' || value.length === 0) {
    throw new UsageError(`--${option} is required.`);
  }
  return value;
}

function userType(value: string | boolean | undefined, fallback: UserType): UserType {
  if (value === undefined) {
    return fallback;
  }
  const upper = String(value).toUpperCase();
  if (upper !== 'ADMIN' && upper !== 'MEMBER') {
    throw new UsageError('User type must be "admin" or "member".');
  }
  return upper;
}

/** Finds the user named by `--email` or `--id`. */
async function selectUser(app: INestApplicationContext, values: Values): Promise<User> {
  const users = app.get(UsersService);
  let user: User | null;
  if (typeof values.id === 'string') {
    user = await users.findByPublicId(parseWith(UuidSchema, values.id, 'id'));
  } else if (typeof values.email === 'string') {
    user = await users.findByEmail(parseWith(EmailSchema, values.email, 'email'));
  } else {
    throw new UsageError('Give --email or --id.');
  }
  if (user === null) {
    throw new UsageError('No such user.');
  }
  return user;
}

async function createUser(
  app: INestApplicationContext,
  values: Values,
  io: CliIo,
  type: UserType,
): Promise<void> {
  const created = await app.get(AdminUsersService).create(CLI_ACTOR, {
    email: parseWith(EmailSchema, requireString(values, 'email'), 'email'),
    name: parseWith(DisplayNameSchema, requireString(values, 'name'), 'name'),
    userType: type,
  });
  io.out(`Created ${created.user.userType} ${created.user.id}`);
  io.out(
    `Temporary password (shown once, must be changed at first sign-in): ${created.temporaryPassword}`,
  );
}

const COMMANDS: Readonly<Record<string, Command>> = {
  'admin:create': {
    usage: 'admin:create --email <e-mail> --name <name>',
    summary: 'Create an administrator (signs in on the web console only).',
    options: { email: { type: 'string' }, name: { type: 'string' } },
    run: (app, values, io) => createUser(app, values, io, 'ADMIN'),
  },
  'user:create': {
    usage: 'user:create --email <e-mail> --name <name> [--type member|admin]',
    summary: 'Create a user with a temporary password.',
    options: { email: { type: 'string' }, name: { type: 'string' }, type: { type: 'string' } },
    run: (app, values, io) => createUser(app, values, io, userType(values.type, 'MEMBER')),
  },
  'user:list': {
    usage: 'user:list',
    summary: 'List accounts.',
    options: {},
    run: async (app, _values, io) => {
      const service = app.get(AdminUsersService);
      let cursor: string | undefined;
      let count = 0;
      do {
        const page = await service.list({ limit: 200, cursor });
        for (const user of page.items) {
          count += 1;
          io.out(
            [
              user.id,
              user.userType.padEnd(6),
              user.status.padEnd(8),
              user.mustChangePassword ? 'temp-password' : '-'.padEnd(13),
              `${user.storageUsedBytes}/${user.storageQuotaBytes ?? 'unlimited'}`,
              user.email,
              user.name,
            ].join('  '),
          );
        }
        cursor = page.nextCursor ?? undefined;
      } while (cursor !== undefined);
      io.out(`${count} user(s)`);
    },
  },
  'user:disable': {
    usage: 'user:disable (--email <e-mail> | --id <uuid>)',
    summary: 'Disable an account and revoke its sessions. The data is kept.',
    options: USER_SELECTOR,
    run: async (app, values, io) => {
      const user = await selectUser(app, values);
      await app.get(AdminUsersService).setStatus(CLI_ACTOR, user.publicId, 'DISABLED');
      io.out(`Disabled ${user.publicId}`);
    },
  },
  'user:reset-password': {
    usage: 'user:reset-password (--email <e-mail> | --id <uuid>)',
    summary: 'Set a temporary password, revoke sessions and rotate the user key.',
    options: USER_SELECTOR,
    run: async (app, values, io) => {
      const user = await selectUser(app, values);
      const result = await app.get(AdminUsersService).resetPassword(CLI_ACTOR, user.publicId);
      io.out(`Password reset for ${user.publicId}`);
      io.out(
        `Temporary password (shown once, must be changed at first sign-in): ${result.temporaryPassword}`,
      );
    },
  },
  'owner:grant': {
    usage: 'owner:grant (--email <e-mail> | --id <uuid>)',
    summary: 'Make a member an owner: they can switch the server AI on and off in the app.',
    options: USER_SELECTOR,
    run: async (app, values, io) => {
      const user = await selectUser(app, values);
      await app.get(UsersService).setOwner(user, true);
      await app
        .get(AuditService)
        .record('OWNER_GRANTED', CLI_ACTOR.userId, { user: user.publicId });
      io.out(`${user.publicId} is now an owner. The AI switch appears in the app's settings.`);
    },
  },
  'owner:revoke': {
    usage: 'owner:revoke (--email <e-mail> | --id <uuid>)',
    summary: 'Take owner access away again.',
    options: USER_SELECTOR,
    run: async (app, values, io) => {
      const user = await selectUser(app, values);
      await app.get(UsersService).setOwner(user, false);
      await app
        .get(AuditService)
        .record('OWNER_REVOKED', CLI_ACTOR.userId, { user: user.publicId });
      io.out(`${user.publicId} is no longer an owner.`);
    },
  },
  'owner:list': {
    usage: 'owner:list',
    summary: 'List owners.',
    options: {},
    run: async (app, _values, io) => {
      const users = app.get(UsersService);
      const owners = await Promise.all(
        (await users.listOwners()).map((owner) => users.toDto(owner)),
      );
      for (const owner of owners) {
        io.out([owner.id, owner.status.padEnd(8), owner.email, owner.name].join('  '));
      }
      io.out(`${owners.length} owner(s)`);
    },
  },
  'keys:status': {
    usage: 'keys:status',
    summary: 'Show key versions, ages and rows pending re-wrap.',
    options: {},
    run: async (app, _values, io) => {
      const status = await app.get(AdminKeysService).status();
      for (const key of status.masterKeys) {
        io.out(
          [
            key.purpose.padEnd(16),
            key.name.padEnd(22),
            `v${key.latestVersion}`,
            `min v${key.minDecryptionVersion}`,
            `${key.ageDays} d old`,
            key.rotationDue ? 'DUE' : 'ok',
            `${key.pendingRewrap} pending re-wrap`,
          ].join('  '),
        );
      }
      const { userKeys } = status;
      io.out(
        `user keys: ${userKeys.active} active, ${userKeys.retired} retired, ${userKeys.destroyed} destroyed, ` +
          `${userKeys.rotationDue} due, ${userKeys.pendingRewrap} data key(s) pending re-wrap`,
      );
      io.out(`data keys: ${status.dataKeys}`);
    },
  },
  'keys:rotate': {
    usage:
      'keys:rotate --scope user-type-master|user-key|jwt-signing [--user-type admin|member] [--email <e-mail> | --id <uuid>]',
    summary: 'Request a key rotation. The worker carries it out.',
    options: { scope: { type: 'string' }, 'user-type': { type: 'string' }, ...USER_SELECTOR },
    run: async (app, values, io) => {
      const scope = requireString(values, 'scope').toUpperCase().replaceAll('-', '_');
      const keys = app.get(AdminKeysService);
      let run;
      if (scope === 'USER_TYPE_MASTER') {
        if (values['user-type'] === undefined) {
          throw new UsageError('--user-type is required for this scope.');
        }
        run = await keys.requestRotation(CLI_ACTOR, {
          scope,
          userType: userType(values['user-type'], 'MEMBER'),
        });
      } else if (scope === 'USER_KEY') {
        const user = await selectUser(app, values);
        run = await keys.requestRotation(CLI_ACTOR, { scope, userId: user.publicId });
      } else if (scope === 'JWT_SIGNING') {
        run = await keys.requestRotation(CLI_ACTOR, { scope });
      } else {
        throw new UsageError('Scope must be user-type-master, user-key or jwt-signing.');
      }
      io.out(
        `Rotation ${run.id} requested (${run.scope}, ${run.state}). The worker carries it out.`,
      );
    },
  },
};

export function usage(): string {
  const lines = ['Usage: node dist/cli.js <command> [options]', '', 'Commands:'];
  for (const command of Object.values(COMMANDS)) {
    lines.push(`  ${command.usage}`, `      ${command.summary}`);
  }
  return lines.join('\n');
}

/**
 * Runs one operator command and returns the process exit code. The application
 * context is only created for a valid command, and always closed again.
 */
export async function runCli(
  argv: readonly string[],
  io: CliIo,
  createContext: () => Promise<INestApplicationContext>,
): Promise<number> {
  const [name, ...rest] = argv;
  if (name === undefined || name === 'help' || name === '--help' || name === '-h') {
    io.out(usage());
    return name === undefined ? 2 : 0;
  }
  const command = COMMANDS[name];
  if (command === undefined) {
    io.err(`Unknown command: ${name}\n\n${usage()}`);
    return 2;
  }
  let values: Values;
  try {
    // No option is declared as `multiple`, so every value is a string or a boolean.
    values = parseArgs({
      args: [...rest],
      options: command.options,
      strict: true,
      allowPositionals: false,
    }).values as Values;
  } catch (error) {
    io.err(
      `${error instanceof Error ? error.message : 'Invalid arguments.'}\nUsage: ${command.usage}`,
    );
    return 2;
  }
  const app = await createContext();
  try {
    await command.run(app, values, io);
    return 0;
  } catch (error) {
    if (error instanceof UsageError) {
      io.err(`${error.message}\nUsage: ${command.usage}`);
      return 2;
    }
    if (error instanceof AppError) {
      io.err(`${error.code}: ${error.message}`);
      return 1;
    }
    throw error;
  } finally {
    await app.close();
  }
}
