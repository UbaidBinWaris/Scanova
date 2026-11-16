import { randomBytes, randomUUID } from 'node:crypto';
import { Inject, Injectable } from '@nestjs/common';
import type { ApiConfig } from '@scanova/config/sections';
import type { User as UserDto } from '@scanova/contracts/auth';
import type { UserType } from '@scanova/contracts/enums';
import { emailLookupIndex } from '@scanova/crypto/blind-index';
import { decryptFieldToString, encryptField } from '@scanova/crypto/field';
import { Prisma, type DatabaseClient, type User } from '@scanova/database/client';
import type { Keyring } from '@scanova/keyring/keyring';
import { AppError } from '../common/app-error.js';
import { PasswordService } from '../auth/password.service.js';
import { API_CONFIG, DATABASE, KEYRING } from '../platform/tokens.js';
import { FIELDS } from '@scanova/crypto/bindings';

export interface CreateUserInput {
  readonly email: string;
  readonly name: string;
  readonly userType: UserType;
  readonly password: string;
  /** True for a temporary password that must be changed before anything else works. */
  readonly mustChangePassword: boolean;
  /** Storage quota; the configured default when absent. */
  readonly quotaBytes?: number;
}

/** Accounts: lookup by e-mail index, encrypted profile fields, creation in key-first order. */
@Injectable()
export class UsersService {
  public constructor(
    @Inject(DATABASE) private readonly database: DatabaseClient,
    @Inject(KEYRING) private readonly keyring: Keyring,
    @Inject(API_CONFIG) private readonly config: ApiConfig,
    private readonly passwords: PasswordService,
  ) {}

  /** `HMAC-SHA256(systemIndexKey, trimmed lowercased e-mail)`: the only way a user is found by e-mail. */
  public emailIndex(email: string): Promise<Buffer<ArrayBuffer>> {
    return this.keyring.useSystemKey('EMAIL_INDEX', (key) => emailLookupIndex(key, email));
  }

  public async findByEmail(email: string): Promise<User | null> {
    const user = await this.database.user.findUnique({
      where: { emailIndex: await this.emailIndex(email) },
    });
    return user === null || user.deletedAt !== null ? null : user;
  }

  public async findByPublicId(publicId: string): Promise<User | null> {
    const user = await this.database.user.findUnique({ where: { publicId } });
    return user === null || user.deletedAt !== null ? null : user;
  }

  public async getByPublicId(publicId: string): Promise<User> {
    const user = await this.findByPublicId(publicId);
    if (user === null) {
      throw new AppError('NOT_FOUND');
    }
    return user;
  }

  /** The API shape of a user, with e-mail and name decrypted under their PROFILE key. */
  public toDto(user: User): Promise<UserDto> {
    return this.keyring.useProfileKey(user.publicId, (key) => ({
      id: user.publicId,
      email: decryptFieldToString(key, FIELDS.userEmail(user.publicId), user.emailEnc),
      name: decryptFieldToString(key, FIELDS.userName(user.publicId), user.nameEnc),
      userType: user.userType,
      status: user.status,
      mustChangePassword: user.mustChangePassword,
      storageUsedBytes: Number(user.storageUsedBytes),
      storageQuotaBytes: user.storageQuotaBytes === null ? null : Number(user.storageQuotaBytes),
      isOwner: user.isOwner,
      createdAt: user.createdAt.toISOString(),
    }));
  }

  /**
   * Creates an account. Order across the two databases: the user's keys first,
   * then the row they protect. If the row cannot be written the fresh keys are
   * destroyed again — no data exists under them yet.
   */
  public async create(input: CreateUserInput): Promise<User> {
    const email = input.email.trim();
    const emailIndex = await this.emailIndex(email);
    if (
      (await this.database.user.findUnique({ where: { emailIndex }, select: { id: true } })) !==
      null
    ) {
      throw emailTaken();
    }
    const passwordHash = await this.passwords.hash(input.password);
    const publicId = randomUUID();
    await this.keyring.provisionUser(publicId, input.userType);
    try {
      const encrypted = await this.keyring.useProfileKey(publicId, (key) => ({
        emailEnc: encryptField(key, FIELDS.userEmail(publicId), email),
        nameEnc: encryptField(key, FIELDS.userName(publicId), input.name),
      }));
      return await this.database.user.create({
        data: {
          publicId,
          emailIndex,
          ...encrypted,
          userType: input.userType,
          passwordHash,
          mustChangePassword: input.mustChangePassword,
          storageQuotaBytes: BigInt(input.quotaBytes ?? this.config.limits.userDefaultQuotaBytes),
        },
      });
    } catch (error) {
      await this.keyring.destroyUser(publicId).catch(() => undefined);
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') {
        throw emailTaken();
      }
      throw error;
    }
  }

  public async updateName(user: User, name: string): Promise<User> {
    const nameEnc = await this.keyring.useProfileKey(user.publicId, (key) =>
      encryptField(key, FIELDS.userName(user.publicId), name),
    );
    return this.database.user.update({ where: { id: user.id }, data: { nameEnc } });
  }

  /** Owner access (§11.8): members only, because owners act from the app and admins are web-only. */
  public async setOwner(user: User, isOwner: boolean): Promise<User> {
    if (isOwner && user.userType !== 'MEMBER') {
      throw new AppError('VALIDATION_FAILED', {
        message: 'Only members can be owners: owners use the app, and administrators are web-only.',
      });
    }
    if (isOwner && (user.status !== 'ACTIVE' || user.deletedAt !== null)) {
      throw new AppError('VALIDATION_FAILED', { message: 'The account is not active.' });
    }
    return this.database.user.update({ where: { id: user.id }, data: { isOwner } });
  }

  /** Active owners, oldest first. */
  public listOwners(): Promise<User[]> {
    return this.database.user.findMany({
      where: { isOwner: true, deletedAt: null },
      orderBy: { createdAt: 'asc' },
    });
  }

  /**
   * Crypto-shredding (§5.2). The keys are destroyed first, which makes every
   * field and object of the user unrecoverable at once; only then is the row
   * marked for the purge job. The e-mail index is replaced by random bytes so
   * the address is free again and can no longer be looked up.
   */
  public async shred(user: User): Promise<void> {
    await this.keyring.destroyUser(user.publicId);
    await this.database.$transaction([
      this.database.session.updateMany({
        where: { userId: user.id, revokedAt: null },
        data: { revokedAt: new Date() },
      }),
      this.database.user.update({
        where: { id: user.id },
        data: { deletedAt: new Date(), status: 'DISABLED', emailIndex: randomBytes(32) },
      }),
    ]);
  }
}

function emailTaken(): AppError {
  // §7.5 has no dedicated code for a taken e-mail address; a conflict is the closest.
  return new AppError('ID_CONFLICT', { message: 'A user with this e-mail already exists.' });
}
