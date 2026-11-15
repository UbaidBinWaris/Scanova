# Scanova — Architecture & Contract

Scanova is a self-hosted document scanner: an Android app that scans paper into
PDFs, and a server that stores, encrypts, OCRs and (later) analyses them. Nothing
leaves hardware the owner controls.

This document is the contract every part of the system is built against. If code
and this document disagree, fix one of them in the same change.

- [1. Scope and milestones](#1-scope-and-milestones)
- [2. System overview](#2-system-overview)
- [3. Repository layout](#3-repository-layout)
- [4. Configuration: one `.env`](#4-configuration-one-env)
- [5. Security model](#5-security-model)
- [6. Data model](#6-data-model)
- [7. API contract](#7-api-contract)
- [8. Infrastructure](#8-infrastructure)
- [9. Android app](#9-android-app)
- [10. Engineering standards](#10-engineering-standards)
- [11. AI (milestone 2)](#11-ai-milestone-2--research-based-plan)

---

## 1. Scope and milestones

| Milestone | Contents |
|---|---|
| **M1 (now)** | Server stack (PostgreSQL, Garage, OpenBao, Caddy), NestJS API + worker, shared packages, envelope encryption with key rotation, Android app: pair, sign in, scan, crop, filter, PDF, encrypted local storage, sync. Server OCR. |
| **M2** | AI on the server (local model): auto title/tags, field extraction, summaries, semantic search, ask-your-documents. Backups. |
| **M3** | Next.js website: members view their documents; admin console on a secure path with MFA. |

**User types:** `ADMIN` and `MEMBER`. No public sign-up. Admins manage accounts
and keys; members own documents. Admins never use the Android app and have no
API that returns another user's document content.

**Target server:** Intel Core i5-1235U (12th gen laptop, 10 cores / 12 threads,
Iris Xe graphics), 15 GB RAM, 238 GB NVMe with LUKS full-disk encryption, Debian 13.
It is shared with other services. Everything is sized for CPU-only work on that one machine.

---

## 2. System overview

```
 Android app (Members)                        Browser (M3: Members + Admins)
        │ HTTPS, pinned internal CA                    │ HTTPS
        ▼                                              ▼
 ┌──────────────────────────── edge (Caddy) ───────────────────────────┐
 │  /api/v1/*                   → api (raw path must match             │
 │                                /api/v1(/[A-Za-z0-9_-]+)*)           │
 │  /.well-known/scanova/ca.crt → internal CA certificate (pairing)    │
 │  /internal/*                 → 404, never proxied                   │
 │  everything else             → 404 until the web app exists (M3)    │
 └───────────────┬─────────────────────────────────────┬───────────────┘
                 ▼                                     ▼
          api (NestJS) ◄── /internal/* + service token ── web (Next.js, M3)
            │    │    │
            │    │    └──► openbao   Transit engine: master keys, token signing
            │    └───────► garage    S3 API: ciphertext objects only
            └────────────► postgres  database `scanova`:      metadata, encrypted fields, job queue
                                     database `scanova_keys`: wrapped keys only, separate roles
                 ▲
          worker (NestJS standalone)  OCR, key rotation, purge, AI (M2)
```

Trust boundaries:

1. **Phone ↔ edge.** TLS only. The phone trusts exactly one CA, pinned at pairing.
2. **Edge ↔ api.** Only `/api/v1/*` crosses. `/internal/*` is unreachable from outside.
3. **api/worker ↔ data services.** Private Compose network. PostgreSQL and OpenBao
   require TLS (they carry credentials and key material). Garage is plain HTTP on
   the private network because it only ever sees ciphertext and signed requests.
4. **Vault boundary.** Master keys never leave OpenBao. Application processes hold
   unwrapped user keys in memory only, briefly.

---

## 3. Repository layout

pnpm workspace + Turborepo for the TypeScript side; Gradle for Android. Apps are
thin and own no reusable logic. Anything two apps need lives in a package.

```
Scanova/
├── .env.example            # the single env template (§4)
├── apps/
│   ├── api/                # NestJS HTTP API (client API + internal web/admin API) + operator CLI
│   ├── worker/             # NestJS standalone app: background jobs
│   ├── android/            # Kotlin + Jetpack Compose, multi-module Gradle (§9)
│   └── web/                # M3 — Next.js (not created yet)
├── packages/
│   ├── config/             # Zod env schema + loader for the root .env; typed config per app
│   ├── contracts/          # API request/response Zod schemas, enums, error codes, job payloads
│   ├── crypto/             # pure cryptography: AEAD, HKDF, envelope, stream format, blind index, passwords
│   ├── kms/                # key-vault client (OpenBao Transit) behind a `Kms` interface + in-memory fake
│   ├── database/           # main database: Prisma schema, migrations, client factory
│   ├── keystore/           # key database: its own Prisma schema, migrations, client factory
│   ├── storage/            # S3 client + encrypted object store (stream in → ciphertext out)
│   ├── keyring/            # key hierarchy: user keys, data keys, rotation engine and policy
│   ├── jobs/               # queue client (pg-boss) + typed job definitions shared by api and worker
│   └── tooling/            # shared tsconfig / eslint presets
├── infra/                  # Compose files, service configs, bootstrap and ops scripts (§8)
└── docs/
```

Dependency direction (no cycles, enforced by package boundaries):

```
config, contracts, crypto         ← no workspace dependencies
kms        → config
database   → config
keystore   → config
storage    → config, crypto
keyring    → config, crypto, kms, keystore   (never the main database)
jobs       → config, contracts
apps/*     → any package; packages never import from apps
```

Rules:

- Package names are `@scanova/<name>`. Public surface is declared with explicit
  subpath `exports` in `package.json` (for example `@scanova/crypto/stream`).
  **No barrel `index.ts` files that re-export everything.**
- `crypto` is pure: no network, no filesystem, no database. That is what makes it
  testable with fixed vectors and reviewable in isolation.
- Inside apps, group by feature (`auth/`, `documents/`, `admin/`), not by type.
- A new app (for example `apps/web`) must need no changes to existing packages
  beyond adding what it genuinely introduces.

---

## 4. Configuration: one `.env`

There is exactly one environment file: `/.env`, created from `/.env.example`.
No app or package has its own `.env`.

- **Compose** interpolates `${VAR}` from it and passes each container only the
  variables that container needs through an explicit `environment:` list. It never
  uses `env_file:` to hand a container the whole file. In-network addresses
  (`postgres`, `garage`, `openbao`) are pinned in `infra/compose.yaml`; the
  addresses in `.env` are host-side.
- **Node code** never touches `process.env`. It calls `@scanova/config`, which
  locates the root `.env`, validates with Zod and returns a typed object. Each
  process validates only its own subset (`loadApiConfig()`, `loadWorkerConfig()`,
  `loadDatabaseConfig()`, `loadKeystoreConfig()`), so the worker does not require
  the API's secrets. The main database and the key database have separate
  connection settings (`POSTGRES_*` and `KEYS_POSTGRES_*`).
- Connection strings are assembled in `@scanova/config` from their parts, so a
  password is written in one place.
- Key material and certificates are files, not env values. They live in
  `infra/.state/` (git-ignored, mode 0600/0700).
- Tests inject config objects directly and never read the root `.env`.

Variable names are fixed by `.env.example`. Adding one means adding it there, in
the config schema, and (if a container needs it) in `infra/compose.yaml`.

---

## 5. Security model

### 5.1 What is encrypted

| Data | At rest | In transit |
|---|---|---|
| Scanned files (PDF, searchable PDF) | AES-256-GCM, unique key per object, in Garage | TLS phone↔edge |
| Titles, tags, OCR text, e-mail, names, device names, audit payloads | AES-256-GCM per field, in PostgreSQL | TLS api↔postgres |
| Search index | keyed HMAC tokens (blind index), per user | — |
| Passwords | Argon2id hashes | TLS |
| Refresh tokens | SHA-256 hashes | TLS |
| User keys and data keys | wrapped, and stored in a separate key database with its own roles (§5.2) | TLS |
| Master keys, token-signing key | inside OpenBao, non-exportable | never leave |
| Phone: database, files, tokens | SQLCipher + Tink, keys in Android Keystore | — |

Deliberately **not** encrypted in the database, because the system needs to query
them and they reveal no content: random IDs, timestamps, sizes, page counts,
status enums, key versions. Run the server's data disk on LUKS to cover residue
such as WAL, indexes and Garage metadata.

Plaintext exists only in process memory, and on a tmpfs mount for the duration of
an OCR job. It is never written to a persistent disk and never logged.

### 5.2 Key hierarchy

```
OpenBao seal key (static auto-unseal key file, outside the data volume)
  └─ Transit master keys — never leave the vault, versioned, auto-rotated
       ├─ scanova-utk-admin   user-type key for ADMIN   (derived, context = user id)
       ├─ scanova-utk-member  user-type key for MEMBER  (derived, context = user id)
       ├─ scanova-sys         wraps system data keys
       └─ scanova-jwt         Ed25519, signs access tokens
            │
            ▼ wraps
       User key (UK) — 32 random bytes per user, versioned, stored only wrapped
            │
            ▼ wraps (AES-256-GCM under HKDF(UK, "scanova/v1/key-wrap"))
       Data keys — 32 random bytes each, stored only wrapped
            ├─ PROFILE key, one per user       → e-mail, name, device names
            ├─ INDEX key, one per user         → HMAC key for search tokens
            └─ DOCUMENT key, one per document
                 ├─ HKDF "scanova/v1/field"              → title, tags, OCR text
                 └─ HKDF "scanova/v1/object|<objectId>"  → stream key of each stored file
```

**Key database.** Every wrapped key lives in its own PostgreSQL database,
`scanova_keys`, never beside the data it protects. The main database holds
ciphertext and metadata but not a single key, wrapped or otherwise.

- The two databases have separate roles. The main runtime role cannot connect to
  the key database, and the key runtime role cannot connect to the main one, so a
  stolen main-database credential, dump or backup contains nothing that decrypts it.
- Only `@scanova/keyring` talks to the key database. Rows in the two databases
  refer to each other by public UUID; there are no cross-database foreign keys.
- Order of operations across the two: **create the key before the row it
  protects; destroy the key before the data.** A key without data is harmless and
  is garbage-collected; data without a key must never exist.
- By default both databases run in the same PostgreSQL instance. Because the
  connection settings are independent, the key database can move to its own
  instance or host by changing `.env` plus the two in-network address lines
  (`x-key-db-address`) in `infra/compose.yaml`.

- "Different keys for different user types" is the two user-type keys. A member's
  key can only be unwrapped by `scanova-utk-member`, an admin's only by
  `scanova-utk-admin`. Changing a user's type re-wraps their user key under the
  other master key.
- Transit keys for user types are created with `derived=true`. Every wrap/unwrap
  passes the user's public id as `context`, so a wrapped key copied onto another
  user's row cannot be unwrapped.
- Unwrapped user keys are cached in process memory for at most 5 minutes and
  overwritten on eviction.
- Deleting a user destroys their wrapped user keys. Every object and field of
  theirs becomes unrecoverable at once (crypto-shredding); objects are then purged.
- Deleting a document for good destroys its DOCUMENT key first, which makes that
  one document unrecoverable before its rows and objects are purged.

### 5.3 Formats

All formats start with a version byte so they can evolve.

| Item | Layout | Associated data (AAD) |
|---|---|---|
| Wrapped user key | OpenBao ciphertext `vault:v<N>:<base64>` | Transit `context` = user public id |
| Wrapped data key | `0x01 ‖ nonce(12) ‖ ciphertext(32) ‖ tag(16)` | `scanova:key:v1\|<scope>\|<userId>\|<refId>\|<ukVersion>` (`scope` = `PROFILE` \| `INDEX` \| `DOCUMENT`; `refId` = user id or document id) |
| Encrypted field | `0x01 ‖ nonce(12) ‖ ciphertext ‖ tag(16)` | `scanova:field:v1\|<table>.<column>\|<rowId>` |
| Blind-index token | first 16 bytes of `HMAC-SHA256(indexKey, NFKC-lowercased word)` | — |
| E-mail lookup index | `HMAC-SHA256(systemIndexKey, trimmed lowercased e-mail)`, 32 bytes | — |

**Object stream format `SCNV1`** (files of any size, constant memory):

```
header (16 bytes) = "SCNV" ‖ version 0x01 ‖ alg 0x01 (AES-256-GCM) ‖ chunkLog2 ‖ 0x00 0x00 ‖ noncePrefix(7)
chunk i           = AES-256-GCM(key   = HKDF(DOCUMENT key, "scanova/v1/object|<objectId>"),
                                nonce = noncePrefix(7) ‖ counter_i (u32 BE) ‖ final flag (0x00 | 0x01),
                                aad   = header ‖ objectId)
                    → ciphertext ‖ tag(16)
```

- Default plaintext chunk size 64 KiB (`chunkLog2 = 16`).
- Exactly one chunk carries the final flag, and it is the last. An empty file is
  one empty final chunk. Truncation, reordering and splicing all fail authentication.
- A chunk is released to the caller only after its tag verifies.

AEAD is AES-256-GCM with 96-bit nonces and 128-bit tags throughout. Random nonces
are used only under per-user or per-document keys, which keeps message counts per
key far below the GCM limit.

### 5.4 Rotation

| Key | Trigger | What happens | Cost |
|---|---|---|---|
| User-type master key | every `KEY_ROTATION_UTK_DAYS`, or on demand | New Transit version. Worker re-wraps every wrapped user key inside the vault (`rewrap`, batched), then raises `min_decryption_version` so old versions stop working. | one small call per user |
| User key | every `KEY_ROTATION_USER_KEY_DAYS`, on demand, on password reset by an admin, on user-type change | New UK version becomes active. Worker re-wraps that user's data keys in batches inside the key database, then destroys the old version. | small writes in one database; no file and no document row is touched |
| Token-signing key | every `KEY_ROTATION_JWT_DAYS` | New Transit version signs new tokens. Old version verifies until the longest-lived access token has expired, then is retired. | none |
| Data key | never rotated in place | A DOCUMENT key protects one document. Content is only re-encrypted by an explicit compromise-response job. | — |
| Service credentials (S3, DB, AppRole) | operator script | `infra/scripts/rotate-credentials.sh` | restart |

A failed rotation run is never executed again. A user-key run only ever moves
keys to the version that is active at that moment, re-checked under the row lock
before anything is destroyed, and never destroys its own target version. The
hourly sweep opens a fresh run for any user left with a retired, undestroyed
version.

**What crypto-shredding guarantees today.** Destroying a key deletes it from the
live key database at once. Copies of it can still exist in PostgreSQL WAL, in
backups of `scanova_keys`, and in OpenBao's retained old master-key versions
(versions are not trimmed yet). So "deleted" data becomes unrecoverable from
backups only once (a) those backups have expired and (b) the master-key versions
that wrapped the deleted keys are trimmed. Planned: an operator trim step after
the backup retention window. Until then, keep `scanova_keys` backups short-lived
and stored apart from main-database backups.

Rotation jobs are resumable and idempotent: each wrapped key records the version
of the key above it, a job processes rows in `KEY_REWRAP_BATCH_SIZE` batches, and a
crash resumes where it stopped. Because all wrapped keys sit in the key database,
rotating a user key never reads or writes the main database. New writes always use the newest version, so the backlog only shrinks.
The "is this key due?" decision is one pure function in `@scanova/keyring`, so the
policy can change without touching the engine.

### 5.5 Authentication and admin separation

- Passwords: Argon2id (parameters from `.env`), re-hashed on login when parameters change.
- Access token: JWT, EdDSA, `ACCESS_TOKEN_TTL_SECONDS` lifetime. Claims: `sub` (user id),
  `typ` (`ADMIN` | `MEMBER`), `cli` (`mobile` | `web`), `sid` (session id), `iss`, `iat`, `exp`.
  Header `kid` names the signing-key version.
- Refresh token: 32 random bytes, stored hashed, single use. Reusing a spent token
  revokes the whole session.
- **Admins are web-only.** The client login refuses `ADMIN` accounts for
  `client: "mobile"` with `INVALID_CREDENTIALS`, without checking the password
  (a decoy hash keeps the timing equal), so the public route cannot be used to
  test admin passwords. `ADMIN_WEB_ONLY` stays defined for the web app but is
  not returned by the mobile login. `cli: "web"` tokens are only issued by
  `/internal/web/v1/auth/login`, which requires the web service token. Admin routes
  require `typ = ADMIN` **and** `cli = web` **and** the service token, and the edge
  never proxies `/internal/*`. Until the website exists, an operator CLI on the
  server itself covers user and key management.
- A user created with a temporary password must change it before anything else works.
  Login still succeeds and returns `Tokens` with `user.mustChangePassword = true`;
  every authenticated endpoint then answers 403 `PASSWORD_CHANGE_REQUIRED` except
  `GET /me`, `POST /me/password`, `POST /auth/logout` and `POST /auth/refresh`.
- Password policy: 12–256 characters, enforced by the server (the app checks the same).
- Replaying any spent refresh token, not only the latest one, revokes the session
  (`spent_refresh_tokens`).
- Rate limits (sliding 60 s windows, in memory): sign-in per IP + e-mail
  (`LOGIN_MAX_ATTEMPTS_PER_MINUTE`), sign-in per IP across all e-mails (6× that),
  and `POST /me/password` per user (5). At most 2 Argon2 hashes run at once with
  16 waiting; beyond that the API answers 429. The edge publishes IPv4 only, so
  every client keeps its real source address (§8).
- Sign-out with a refresh token that a racing refresh already exchanged still
  ends the session.
- Every security-relevant event is audited.

---

## 6. Data model

Conventions (Prisma): `Int @id @default(autoincrement())` primary keys used only
inside the database; a `publicId` UUID on every externally visible row, which is
the only id the API exposes; snake_case columns via `@map`, lowercase tables via
`@@map`; indexes on both sides of every lookup; enums in the schema with a comment
explaining the business meaning. Document `publicId` is generated on the phone so
that uploads are idempotent and offline-created documents keep their identity.

There are two databases. They share nothing but public UUIDs.

**Main database `scanova`** (`@scanova/database`) — ciphertext and metadata, no keys:

| Table | Purpose | Notable columns |
|---|---|---|
| `users` | accounts | `email_index` (unique), `email_enc`, `name_enc`, `user_type`, `status`, `password_hash`, `must_change_password`, quota |
| `sessions` | one per signed-in device | `client`, `refresh_token_hash`, device name and model (encrypted), `platform` and `app_version` (plain), `revoked_at`, `last_used_at` |
| `spent_refresh_tokens` | hashes of every rotated-out refresh token | `session_id`, `token_hash`; a replay revokes the session |
| `documents` | one per scan | `title_enc`, `tags_enc`, `page_count`, `status`, `ocr_status`, `deleted_at` |
| `document_texts` | OCR text | `text_enc`, `source` (`CLIENT` \| `SERVER`) |
| `file_objects` | one per stored object | `document_id`, `variant` (`ORIGINAL` \| `SEARCHABLE`), `object_key`, `size_bytes`, `sha256` |
| `search_tokens` | blind index | `document_id`, `token` |
| `audit_events` | security log | `actor_id`, `action`, `payload_enc`, `created_at` |

The job queue (pg-boss) also lives here, in its own schema.

**Key database `scanova_keys`** (`@scanova/keystore`) — wrapped keys only:

| Table | Purpose | Notable columns |
|---|---|---|
| `user_keys` | wrapped user keys, versioned | `user_id`, `version`, `wrapped_key`, `master_key_name`, `master_key_version`, `state` (`ACTIVE` \| `RETIRED` \| `DESTROYED`) |
| `data_keys` | wrapped data keys | `scope` (`PROFILE` \| `INDEX` \| `DOCUMENT`), `user_id`, `ref_id`, `wrapped_key`, `user_key_version` |
| `system_keys` | wrapped system data keys | `purpose`, `version`, `wrapped_key`, `state` |
| `key_rotation_runs` | rotation history and progress | `scope`, `target`, `state`, counters, timestamps |

`user_key_version` on `data_keys` (and `master_key_version` on `user_keys`) is
what makes rotation resumable.

**Roles and permissions:**

| Role | Database | May do |
|---|---|---|
| `scanova_migrator` | `scanova` | owns the schema; used only to apply migrations |
| `scanova_app` | `scanova` | read/write rows; no schema changes; cannot connect to `scanova_keys` |
| `scanova_keys_migrator` | `scanova_keys` | owns the schema; used only to apply migrations |
| `scanova_keys_app` | `scanova_keys` | select/insert/update on key tables, delete on `data_keys` only; no schema changes; cannot connect to `scanova` |

Infrastructure creates the databases, the roles and the connect isolation.
Table-level grants are applied by the migrate step after migrations, from the
role names in `.env`, so they are versioned with the schema.

---

## 7. API contract

### 7.1 Conventions

- Client API base: `https://<host>/api/v1`. JSON unless stated.
- Envelope, always:

  ```json
  { "data": { }, "error": null, "meta": { "nextCursor": null } }
  { "data": null, "error": { "code": "INVALID_CREDENTIALS", "message": "E-mail or password is incorrect." } }
  ```

- Auth: `Authorization: Bearer <accessToken>`. Ids are UUID strings. Lists are
  cursor-paginated: `?limit=50&cursor=…` → `meta.nextCursor`.
- Times are always `Date.prototype.toISOString()` output: `YYYY-MM-DDTHH:mm:ss.sssZ`,
  fixed width, so clients may compare them as strings.
- Path segments use only `[A-Za-z0-9_-]` (the edge refuses anything else).
- A user only ever sees their own rows. Another user's id answers `404 NOT_FOUND`.

### 7.2 Shapes

```ts
User     { id, email, name, userType: "ADMIN"|"MEMBER", status: "ACTIVE"|"DISABLED",
           mustChangePassword: boolean, storageUsedBytes: number,
           storageQuotaBytes: number|null, createdAt }
Tokens   { accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, user: User }
Session  { id, deviceName, platform, model, createdAt, lastUsedAt, current: boolean }
Document { id, title, tags: string[], pageCount: number, sizeBytes: number|null,
           contentSha256: string|null, status: "PENDING_UPLOAD"|"READY",
           ocrStatus: "NONE"|"PENDING"|"DONE"|"FAILED", hasSearchablePdf: boolean,
           capturedAt, createdAt, updatedAt, deletedAt: string|null }
```

### 7.3 Client API (`/api/v1`)

| Method & path | Auth | Request | Response `data` |
|---|---|---|---|
| `GET /health` | – | | `{ status: "ok" }` |
| `GET /health/ready` | – | | `{ status, checks: { database, storage, kms } }` |
| `GET /server-info` | – | | `{ name, apiVersion: "1", serverVersion, features: { serverOcr, ai } }` |
| `POST /auth/login` | – | `{ email, password, client: "mobile", device: { name, platform: "android", model, appVersion } }` | `Tokens` |
| `POST /auth/refresh` | – | `{ refreshToken }` | `Tokens` (rotated) |
| `POST /auth/logout` | – | `{ refreshToken }` | `{ success: true }` |
| `GET /me` | ✓ | | `User` |
| `PATCH /me` | ✓ | `{ name }` | `User` |
| `POST /me/password` | ✓ | `{ currentPassword, newPassword }` | `Tokens` (other sessions revoked) |
| `GET /me/sessions` | ✓ | | `Session[]` |
| `DELETE /me/sessions/:id` | ✓ | | `{ success: true }` |
| `GET /documents` | ✓ | `?limit&cursor&updatedSince&q` | `Document[]` |
| `POST /documents` | ✓ | `{ id, title, pageCount, capturedAt, tags?, clientOcrText? }` | `Document` (201; 200 if it already exists) |
| `GET /documents/:id` | ✓ | | `Document` |
| `PATCH /documents/:id` | ✓ | `{ title?, tags? }` | `Document` |
| `DELETE /documents/:id` | ✓ | | `{ success: true }` (soft delete, purged after `TRASH_RETENTION_DAYS`) |
| `PUT /documents/:id/file` | ✓ | raw `application/pdf` body; headers `Content-Length`, `X-Content-SHA256` (hex) | `Document` |
| `GET /documents/:id/file` | ✓ | `?variant=original\|searchable` | PDF bytes (not enveloped) |
| `GET /documents/:id/text` | ✓ | | `{ text, source: "CLIENT"\|"SERVER" }` |

Behaviour:

- `GET /documents` returns live documents, newest change first. With `updatedSince`
  (inclusive, `>=`) it returns oldest change first and also includes deleted ones
  (`deletedAt` set) so other devices can sync removals. `PENDING_UPLOAD` documents
  are included; the app hides them until `READY`.
- `POST /me/password` with a wrong `currentPassword` answers 401 `INVALID_CREDENTIALS`. `q` is a
  keyword search: every word must match (blind index over title, tags and OCR text).
- `PUT …/file` streams: body → SHA-256 check → encrypt → storage. It is never
  buffered whole. At most 2 uploads per user and 8 in total run at once (more
  answer 429 `RATE_LIMITED`), and a body that sends nothing for 60 s is cut off.
  The commit locks the document row, so parallel uploads cannot corrupt the
  storage accounting. Storage usage counts every stored variant (original and
  searchable PDF) and can never go below zero (database constraint).
- Server OCR works on the first 100 pages, with a 120 s limit per page and a
  100-megapixel limit per image; one retry at most.
- `GET /health/ready` is for the container network only; the edge answers 404. The first bytes must be `%PDF-`. Repeating it with the same
  hash is a no-op. Success sets `status = READY` and queues OCR.
- `clientOcrText` (≤ 1 MiB) is the phone's on-device OCR, so search works
  immediately. Server OCR replaces it when it finishes.

### 7.4 Internal API (`/internal`, web app only)

Never routed by the edge. Every request carries `X-Scanova-Service-Token`.

| Method & path | Extra auth | Purpose |
|---|---|---|
| `POST /internal/web/v1/auth/login` | – | Web sign-in for members and admins → `Tokens` with `cli: "web"` |
| `GET /internal/admin/v1/users` | admin | List users |
| `POST /internal/admin/v1/users` | admin | Create `{ email, name, userType }` → `{ user, temporaryPassword }` (shown once) |
| `PATCH /internal/admin/v1/users/:id` | admin | `{ name?, status?, userType? }` |
| `POST /internal/admin/v1/users/:id/reset-password` | admin | → `{ temporaryPassword }`; revokes sessions, rotates the user key |
| `POST /internal/admin/v1/users/:id/revoke-sessions` | admin | |
| `DELETE /internal/admin/v1/users/:id` | admin | Crypto-shred and purge |
| `GET /internal/admin/v1/keys` | admin | Versions, ages, rows pending re-wrap |
| `POST /internal/admin/v1/keys/rotations` | admin | `{ scope: "USER_TYPE_MASTER"\|"USER_KEY"\|"JWT_SIGNING", userType?, userId? }` |
| `GET /internal/admin/v1/keys/rotations` | admin | History and progress |
| `GET /internal/admin/v1/audit-events` | admin | Audit log |

"admin" = access token with `typ = ADMIN` and `cli = web`.

### 7.5 Error codes

| HTTP | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Body/query failed schema validation (`error.details` lists fields) |
| 401 | `UNAUTHENTICATED` | Missing, malformed or expired access token |
| 401 | `INVALID_CREDENTIALS` | Wrong e-mail or password |
| 401 | `INVALID_REFRESH_TOKEN` | Unknown or expired refresh token |
| 401 | `SESSION_REVOKED` | Session revoked, or a spent refresh token was reused |
| 403 | `ACCOUNT_DISABLED` | Account disabled by an admin |
| 403 | `ADMIN_WEB_ONLY` | Admin account tried the mobile login |
| 403 | `PASSWORD_CHANGE_REQUIRED` | Temporary password must be changed first |
| 403 | `FORBIDDEN` | Authenticated but not allowed |
| 404 | `NOT_FOUND` | No such resource for this user |
| 409 | `ID_CONFLICT` | Client-generated id already used, or e-mail already registered |
| 413 | `FILE_TOO_LARGE` | Over `MAX_UPLOAD_BYTES` |
| 413 | `QUOTA_EXCEEDED` | Would exceed the user's storage quota |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Not a PDF |
| 422 | `CHECKSUM_MISMATCH` | Body does not match `X-Content-SHA256` |
| 429 | `RATE_LIMITED` | Too many attempts (`Retry-After` header) |
| 500 | `INTERNAL_ERROR` | Anything else; details only in server logs |
| 503 | `SERVICE_UNAVAILABLE` | Vault, storage or database unreachable |

---

## 8. Infrastructure

`infra/compose.yaml`, project name `scanova`. Images pinned to exact versions.

| Service | Image | Role | Memory cap |
|---|---|---|---|
| `edge` | Caddy | TLS, routing (§2); published on IPv4 only | 256 MB |
| `api` | built from `apps/api/Dockerfile` | HTTP API, port 3000 | 1 GB |
| `worker` | built from `apps/worker/Dockerfile` | jobs; includes OCRmyPDF + Tesseract | 3 GB |
| `postgres` | PostgreSQL 18 | two databases (`scanova`, `scanova_keys`), TLS required, scram-sha-256 | 2 GB |
| `garage` | Garage v2 | S3 API on 3900, single node | 1 GB |
| `openbao` | OpenBao 2 | Transit engine, static auto-unseal | 512 MB |
| `ollama` | Ollama (profile `ai`, M2) | local model | 6 GB |

- Networks: `edge_net` (edge ↔ api) and `data_net` (api/worker ↔ data services,
  `internal: true`). Data services publish no host ports in production.
- `infra/compose.dev.yaml` publishes them on `127.0.0.1` only — PostgreSQL 55432,
  Garage 53900, OpenBao 58200 — so apps and migrations can run on the host.
- Containers run as non-root, with `cap_drop: [ALL]`, `no-new-privileges`,
  read-only root filesystems where the image allows, healthchecks and restart policies.
- The worker's `SCANOVA_TMP_DIR` is a tmpfs mount.
- Dockerfiles for `api` and `worker` build from the repository root as context.

**Production deployment** (the shared home server, GitHub Actions, the
download site and Android releases) is described in
[infra/deploy/README.md](../infra/deploy/README.md). In short: TLS mode
`public` behind the server's HAProxy (SNI passthrough, PROXY protocol), S3
mode `external` on the server's existing Garage through a host relay limited to
Scanova's internal network, and releases activated by `infra/deploy/deploy.sh`
through a forced-command deploy key, with automatic rollback.

`infra/scripts/bootstrap.sh` is idempotent and takes a fresh clone to a running stack:

1. Create `.env` from `.env.example`, generating every empty secret.
2. Create the internal CA and certificates for edge, postgres and openbao in `infra/.state/pki/`.
3. Start data services. Create both databases and the four PostgreSQL roles (§6),
   revoke `CONNECT` from `PUBLIC` on each database, and grant it only to that
   database's own two roles.
4. Initialise OpenBao: static auto-unseal, Transit mount, the four keys (user-type
   keys derived and non-exportable, rotation periods from `.env`), least-privilege
   policies, one AppRole each for api and worker. Write the AppRole ids into `.env`.
5. Configure Garage: layout, bucket, access key. Write the S3 credentials into `.env`.
6. Print the pairing details: server URL, CA fingerprint and a QR code for
   `scanova://pair?url=<url-encoded URL>&ca=<sha256 hex of the CA certificate DER>`.

OpenBao policies: `api` may encrypt, decrypt, re-wrap, generate data keys, sign
and read key metadata. `worker` may additionally rotate keys and change only
`min_decryption_version` / `min_encryption_version` (`allowed_parameters`).
Neither may export or delete a key. A third AppRole, `scanova-operator`, can only
submit recovery shares (OpenBao 2.7 disables unauthenticated root generation).

- The root token is never written to disk and is revoked when bootstrap ends.
- Recovery shares (2 of 3) are written to `infra/.state/openbao/recovery/`.
  Move at least two of them off the server after deployment.
- A one-shot `init` container (no network, read-only root filesystem, only
  `CHOWN`, `DAC_OVERRIDE`, `FOWNER`) prepares volume ownership and copies each
  service's TLS key pair into its own volume.
- PostgreSQL and OpenBao require TLS 1.3. The PostgreSQL superuser cannot log in
  over the network.

---

## 9. Android app

Kotlin, Jetpack Compose, Material 3, dark-first. `minSdk 26`, `compileSdk` and
`targetSdk 37`. Room 3 on SQLCipher's own driver, OpenCV 5, Tink. Multi-module
Gradle with a version catalog and convention plugins:

```
apps/android/
├── build-logic/            # convention plugins shared by all modules
├── app/                    # navigation host, DI wiring, theme entry point
├── core/
│   ├── common/             # result types, dispatchers, small utilities
│   ├── designsystem/       # theme, typography, shared components
│   ├── security/           # Android Keystore, Tink, encrypted file + token stores
│   ├── database/           # Room on SQLCipher, DAOs, FTS
│   ├── network/            # API client, auth interceptor, CA pinning, DTOs
│   └── data/               # repositories, sync engine (WorkManager)
└── feature/
    ├── pairing/            # connect to a server (QR or manual)
    ├── auth/               # sign in, forced password change
    ├── scan/               # camera, edge detection, crop, filters, page review, PDF
    ├── documents/          # library, search, viewer, share, delete
    └── settings/           # account, sessions, server, app lock
```

Feature modules depend on core modules, never on each other.

- **Pairing.** The user scans the QR from bootstrap (or types the URL and
  fingerprint). A pairing code, whether scanned or from a `scanova://pair` link,
  never connects on its own: the app shows the exact host and port and whether
  the certificate is pinned, and connects only after the user confirms. The
  sign-in screen always shows the server's address, never a name the server
  reports. The app downloads `/.well-known/scanova/ca.crt`, checks its
  SHA-256 against the fingerprint, and from then on trusts only that CA. Cleartext
  traffic is disabled. With `SCANOVA_TLS_MODE=acme` there is no fingerprint and
  the system trust store is used.
- **Scan pipeline.** CameraX preview → live document-edge overlay (OpenCV) →
  capture → adjustable four-corner crop → perspective correction → filter
  (original, enhanced colour, greyscale, black & white) → multi-page review
  (reorder, delete, add) → PDF.
- **On-device intelligence.** Edge detection and auto-capture when the page is
  steady; ML Kit text recognition (bundled model, offline) for instant search and
  a suggested title. Urdu and other non-Latin scripts are recognised by server OCR.
- **Local storage.** Room on SQLCipher; database passphrase and file keys wrapped
  by a hardware-backed Android Keystore key. Page images and PDFs are encrypted
  with Tink streaming AEAD. Tokens live in an encrypted store. `allowBackup` is
  off. Jetpack Security (`EncryptedSharedPreferences`) is deprecated and not used.
- **Sync.** Local-first. Each document has a sync state (`LOCAL_ONLY`,
  `UPLOADING`, `SYNCED`, `FAILED`, `PENDING_DELETE`). WorkManager creates the
  document (`POST /documents`), uploads the PDF (`PUT …/file`), retries with
  backoff, and pulls remote changes with `updatedSince`. Offline or 5xx returns a
  document to `LOCAL_ONLY` with a scheduled retry; `FAILED` means the server
  rejected it and the user must retry. Local edits win until pushed; server
  deletions always win.
- **LAN access.** Android 17 requires the `ACCESS_LOCAL_NETWORK` permission to
  reach a server on the local network; pairing requests it.
- **Remote documents.** Documents scanned on another device are previewed in-app
  on Android 11+ (rendered from memory). On Android 8–10 they can be shared or
  saved, not previewed, because that would need a plaintext file on disk.
- **Sign-out** erases the account's local documents, including unsynced ones,
  after a confirmation that shows how many. Tokens are cleared first, the wipe
  runs under the sync lock, and a token refresh that finishes afterwards is
  discarded, so a running sync or refresh cannot undo it.
- **Screens** are always `FLAG_SECURE`: no screenshots, recordings, casting or
  recent-apps snapshot, whatever the app-lock setting.
- **Key problems.** If the phone's stored keys cannot be read, the app never
  erases anything on its own. Known transient Keystore errors are retried; then
  a screen offers "Try again" and, behind a confirmation, "Erase and start over".
- **No third-party telemetry.** ML Kit's Google data-transport backend is removed
  from the manifest; the app talks only to the paired server.
- **Updates.** The app is sideloaded (no Play Store). When the signed-in app
  opens, at most every 6 hours (or on "Check for updates" in Settings), it reads
  `/downloads/android/latest.json` from the paired server over the pinned
  connection. A newer build is downloaded into the cache, checked for size,
  SHA-256, package name, a newer version code and the same signing certificate
  as the installed app and `latest.json`, then handed to Android's installer,
  which asks the user to confirm. `minSupportedVersionCode` above the installed
  version makes the update required (no "Later"). Release builds are arm64-v8a
  only.
- **Share copies** (plaintext, in the app cache) are deleted when the share
  returns and at every app start.
- **Members only.** `ADMIN_WEB_ONLY` is shown as "Administrators sign in on the
  web console."

---

## 10. Engineering standards

- TypeScript strict, no `any`. Explicit return types on exported and async functions.
- Zod at every external boundary: HTTP input, env, job payloads, vault responses.
- Thin controllers, logic in services; errors are typed and mapped to §7.5 in one place.
- Never log secrets, tokens, key material, e-mail addresses, document titles or
  text. Log ids and error codes.
- No `console.log`. No `TODO` without an issue reference.
- Idempotent by default for anything that can replay: uploads, jobs, rotation.
- Tests: fixed-vector and tamper tests for `crypto`; rotation tests that prove old
  data still decrypts and old key versions stop working; integration tests for the
  API against real PostgreSQL, Garage and OpenBao; permission tests proving each
  runtime role is refused by the other database.
- Buffers holding key material are overwritten when no longer needed (best effort
  in a garbage-collected runtime).

---

## 11. AI (milestone 2) — research-based plan

Research date: 2026-11-15. Model choice is a **shortlist**; the final pick is made by
the evaluation suite (§11.6) run on the real server, not by published benchmarks.

### 11.1 Constraints that decide everything

- Server: i5 11th-gen laptop CPU, 16 GB RAM shared with PostgreSQL, Garage, OpenBao,
  api and worker. AI budget: **≤ 6 GB RAM** in total (chat model + embedding model + cache).
- CPU text generation is limited by memory bandwidth, not cores. Dual-channel RAM is
  roughly twice as fast as single-channel. Intel names Iris Xe "UHD Graphics" when only
  one memory channel is populated, so "Intel HD graphics" may mean single-channel RAM.
  Needed from the owner: exact CPU model and memory layout.
- Everything the AI reads is plaintext. The AI runtime is therefore inside the
  encryption boundary and must be treated like the worker: private network only.
- Urdu matters (English + Urdu documents).

### 11.2 Runtime: llama.cpp `llama-server`

Chosen over Ollama for the server:

- OpenAI-compatible HTTP API (`/v1/chat/completions`, `/v1/embeddings`), so the
  runtime can be swapped (Ollama, vLLM, OpenVINO Model Server, a cloud API) by config.
- API-key authentication (`--api-key-file`), JSON-schema-constrained output
  (`response_format`), embeddings, vision via `--mmproj`, Vulkan backend for Intel iGPUs.
- Hardening flags: `--no-webui`, `--no-slots`, `--log-disable` (or log level that never
  prints prompts); no `-hf` downloads at runtime. Model files are pinned by SHA-256,
  downloaded once by an ops script and mounted read-only.
- Two instances: one chat/vision model, one embedding model (`--embeddings`).
- Runs on `data_net` only, no published ports, non-root, read-only rootfs, memory caps.
- iGPU offload (Vulkan) is optional and decided by benchmark; evidence for Iris Xe is
  thin and the gain over CPU is modest.

`.env` changes at M2 start (provider-neutral, replaces `OLLAMA_*`):
`AI_PROVIDER=none|openai-compatible`, `AI_CHAT_BASE_URL`, `AI_CHAT_MODEL`,
`AI_EMBED_BASE_URL`, `AI_EMBED_MODEL`, `AI_EMBED_DIMENSIONS`, `AI_API_KEY` <generated>.

### 11.3 Model shortlist (all permissive licences — no licence lock-in)

| Role | Candidates | Licence | Notes |
|---|---|---|---|
| Chat + vision (title, type, tags, fields, summary, Q&A, OCR fallback) | **Gemma 4 E4B**, **Qwen3.5-4B** | Apache 2.0 both | ~3 GB at Q4. Gemma 4 E2B/E4B have official llama.cpp GGUF with vision; Qwen3.5 GGUF is community-built. |
| Smaller fallback | Gemma 4 E2B, Qwen3.5-2B | Apache 2.0 | if RAM or speed is short |
| English-only fallback | Phi-4-mini | MIT | weaker multilingual |
| Embeddings | **Qwen3-Embedding-0.6B** (Q8, ~640 MB), multilingual-e5 (base/small), bge-m3 | Apache 2.0 / MIT / MIT | Urdu quality unverified for all — measured in §11.6 |
| Excluded | jina-embeddings-v5 (CC BY-NC, non-commercial), EmbeddingGemma (licence terms unclear) | | |

### 11.4 OCR

- **English:** Tesseract through OCRmyPDF stays (fast on CPU, produces the searchable PDF).
- **Urdu:** Tesseract is weak on Urdu (≈55 % character error on a printed page in one
  vendor test; 72 % on handwriting in a 2026 study). Candidates to benchmark on real pages:
  PaddleOCR PP-OCRv5 multilingual (lists Urdu), and page transcription with the chosen
  vision model as a fallback only when Tesseract confidence is low (est. 1–2 min per page on
  this CPU). Handwritten Urdu is not reliable with any open model today (best open model
  in the 2026 study: 49 % character error) — set user expectations accordingly.
- **Phone:** ML Kit text recognition has no Arabic-script support, so on-device OCR is
  English/Latin only. Urdu documents become searchable after server OCR.

### 11.5 Encryption and safety rules for AI

- AI outputs (title suggestion, type, tags, extracted fields, summary) are encrypted with
  the document key like every other field.
- **Embeddings count as content**: text can be partially reconstructed from them. They are
  encrypted per document (no plaintext pgvector). Semantic search decrypts only that user's
  vectors in memory and ranks them there. Store reduced dimensions (MRL truncation) to keep
  this cheap.
- Each stored vector records `embedModel` + version. Changing the embedding model triggers a
  background re-embed job — the one real lock-in point, handled explicitly.
- Document text is untrusted input (prompt injection): no tools/function calling, output
  constrained to a JSON schema, results validated with Zod, retrieval scoped to the
  requesting user only.
- Phone extras are optional, never required: ML Kit GenAI (Gemini Nano) works on few
  flagship phones and is beta; on-device Gemma 4 E2B via LiteRT-LM needs ~2.5 GB download
  and ≥ 3.2 GB free RAM.

### 11.6 Evaluation suite (the AI test cases)

Lives in `tools/ai-eval/`. Runs on the target server. Same prompts, temperature 0, fixed seed.

Golden dataset: synthetic documents generated for the purpose (English and Urdu invoices,
receipts, letters, forms, ID-card-like forms with fake data, Urdu printed Naskh and
Nastaliq, poor photos), plus optional owner-provided real documents kept encrypted outside git.

| # | Test | Metric | Initial gate |
|---|---|---|---|
| 1 | Structured output | JSON schema valid | 100 % |
| 2 | Document type | accuracy | ≥ 90 % |
| 3 | Field extraction (date, amount, number, party) | exact match per field | ≥ 90 % English printed; Urdu set after baseline |
| 4 | Title suggestion | accepted by reviewer | ≥ 80 % |
| 5 | OCR English printed | character error rate | ≤ 3 % |
| 6 | OCR Urdu printed | character error rate | baseline first, then gate |
| 7 | Semantic search | recall@5, MRR — English, Urdu, cross-language | recall@5 ≥ 0.9 English |
| 8 | Summary | faithfulness (no invented facts), manual + checklist | 0 invented amounts/dates |
| 9 | Q&A over own documents | correct answer + correct citation | ≥ 85 % |
| 10 | Prompt injection documents | schema intact, no instruction followed | 100 % |
| 11 | Cross-user isolation | answers never cite another user's documents | 100 % |
| 12 | Privacy | no prompt/document text in any log; runtime refuses requests without API key; unreachable from edge | 100 % |
| 13 | Performance | seconds per page / per document, peak RAM, tokens/s | 1-page doc ≤ 60 s; RAM ≤ 6 GB |

Gates for rows 3, 6 and 7 (Urdu) are fixed after the first baseline run; the model that
passes all gates with the lowest RAM wins.
