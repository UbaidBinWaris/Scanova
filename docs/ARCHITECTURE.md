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
- [12. Web app](#12-web-app)

---

## 1. Scope and milestones

| Milestone | Contents |
|---|---|
| **M1 (now)** | Server stack (PostgreSQL, Garage, OpenBao, Caddy), NestJS API + worker, shared packages, envelope encryption with key rotation, Android app: pair, sign in, scan, crop, filter, PDF, encrypted local storage, sync. Server OCR. |
| **M2** | AI on the server (local model): auto title/tags, field extraction, summaries, semantic search, ask-your-documents. Backups. |
| **M3** | Next.js website: members view their documents; admin console on a secure path with MFA. Built (§12) except MFA, which needs API support first. |

**User types:** `ADMIN` and `MEMBER`. Members can create their own account in the app
(open sign-up, when the server enables it, with a 2 GB quota each); admins manage
accounts and keys; members own documents. The app itself works fully without any account. Admins never use the Android app and have no
API that returns another user's document content.

**Target server:** Intel Core i5-1235U (12th gen laptop, 10 cores / 12 threads,
Iris Xe graphics), 15 GB RAM, 238 GB NVMe with LUKS full-disk encryption, Debian 13.
It is shared with other services. Everything is sized for CPU-only work on that one machine.

---

## 2. System overview

```
 Android app (Members)                        Browser (Members + Admins, §12)
        │ HTTPS, pinned internal CA                    │ HTTPS
        ▼                                              ▼
 ┌──────────────────────────── edge (Caddy) ───────────────────────────┐
 │  /api/v1/*                   → api (raw path must match             │
 │                                /api/v1(/[A-Za-z0-9_-]+)*)           │
 │  /.well-known/scanova/ca.crt → internal CA certificate (pairing)    │
 │  /internal/*                 → 404, never proxied                   │
 │  /app, /_next/static/*       → web: member portal (site host)       │
 │  admin host: /admin …        → web: admin console (admin host only) │
 │  everything else             → 404 (site host: the download page)   │
 └───────────────┬─────────────────────────────────────┬───────────────┘
                 ▼                                     ▼
          api (NestJS) ◄── /internal/* + service token ── web (Next.js)
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
   **Edge ↔ web ↔ api.** The browser talks only to the web app, which holds every
   token server-side and calls the API on the private network (§12).
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
│   ├── web/                # Next.js: member portal + admin console (§12)
│   └── ai-image/           # page clean-up runtime: Python + ONNX Runtime, GCDRNet (§11.8)
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
│   ├── ai/                 # OpenAI-compatible client for the local AI runtimes: schema-checked chat, embeddings (§11)
│   └── tooling/            # shared tsconfig / eslint presets
├── tools/
│   └── ai-eval/            # AI evaluation suite: dataset generator, runner, mock runtime (§11.7); stdlib Python
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
ai         → config
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
  `loadWebConfig()`, `loadDatabaseConfig()`, `loadKeystoreConfig()`), so the worker does not require
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
| Scanned files (PDF, searchable PDF, page originals) | AES-256-GCM, unique key per object, in Garage | TLS phone↔edge |
| Titles, tags, OCR text, page edit recipes, e-mail, names, device names, audit payloads | AES-256-GCM per field, in PostgreSQL | TLS api↔postgres |
| Search index | keyed HMAC tokens (blind index), per user | — |
| Passwords | Argon2id hashes | TLS |
| Refresh tokens | SHA-256 hashes | TLS |
| User keys and data keys | wrapped, and stored in a separate key database with its own roles (§5.2) | TLS |
| Master keys, token-signing key | inside OpenBao, non-exportable | never leave |
| Phone: database, files, tokens | SQLCipher + Tink, keys in Android Keystore | — |

Deliberately **not** encrypted in the database, because the system needs to query
them and they reveal no content: random IDs, timestamps, sizes, page counts, page
order and pixel dimensions, status enums, key versions. Run the server's data disk on LUKS to cover residue
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
                 ├─ HKDF "scanova/v1/field"              → title, tags, OCR text, page recipes
                 └─ HKDF "scanova/v1/object|<objectId>"  → stream key of each stored file
                                                            (PDF variants and page originals)
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
| Encrypted field | `0x01 ‖ nonce(12) ‖ ciphertext ‖ tag(16)` | `scanova:field:v1\|<table>.<column>\|<rowId>` (`rowId` = public id; for `document_pages.edit_enc` the page id, unique within its document — the document is bound by the key, which is that document's) |
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
  never proxies `/internal/*`. The admin console (§12) and, on the server itself,
  an operator CLI cover user and key management.
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
| `documents` | one per scan | `title_enc`, `tags_enc`, `page_count`, `status`, `ocr_status`, `pages_updated_at`, `deleted_at` |
| `document_texts` | OCR text | `text_enc`, `source` (`CLIENT` \| `SERVER`) |
| `file_objects` | one per stored PDF | `document_id`, `variant` (`ORIGINAL` \| `SEARCHABLE`), `object_key`, `size_bytes`, `sha256` |
| `document_pages` | one per page: its original photo and edit recipe | `public_id` (phone-generated, unique within the document), `document_id`, `position`, `width`, `height`, `edit_enc` (nullable), `original_object_id`, `original_object_key`, `original_size_bytes`, `original_sha256` (all four set together or none), `updated_at` |
| `search_tokens` | blind index | `document_id`, `token` |
| `audit_events` | security log | `actor_id`, `action`, `payload_enc`, `created_at` |

The job queue (pg-boss) also lives here, in its own schema.

**Pages.** The phone keeps, per page, the untouched original photo, the recipe
that turns it into the page (crop outline, rotation, filter and strength,
brightness/contrast, pen and highlighter markup — the phone's `PageEdit` JSON)
and the rendered current image. The PDF in `file_objects` stays the document's
current version; `document_pages` makes every page re-editable without loss on
any device (the phone after a restore, the web app later):

- A page's original is its own SCNV1 object under the document's prefix
  (`u/<userId>/<documentId>/<objectId>`), with a fresh object id per upload.
  Its columns live on the page row rather than in `file_objects`, so a new PDF
  (which replaces every `file_objects` row of the document) never touches
  originals. Originals count towards the owner's storage like the PDFs.
- The recipe is encrypted like the title (`edit_enc`); order and pixel size are
  plain. Pages are written only under the document row lock, like files.
- `documents.pages_updated_at` is the instant of the last change to the page list
  or to an original (equal to the changed pages' `updated_at`); devices compare it
  with the value they last applied.
- Soft-deleting a document hides its pages; the trash purge destroys the key,
  then deletes PDFs and page originals, then the rows (pages cascade), and gives
  all their bytes back. A user purge removes everything under the user prefix.

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
           storageQuotaBytes: number|null, isOwner: boolean, createdAt }
Tokens   { accessToken, accessTokenExpiresAt, refreshToken, refreshTokenExpiresAt, user: User }
Session  { id, deviceName, platform, model, createdAt, lastUsedAt, current: boolean }
Document { id, title, tags: string[], pageCount: number, sizeBytes: number|null,
           contentSha256: string|null, status: "PENDING_UPLOAD"|"READY",
           ocrStatus: "NONE"|"PENDING"|"DONE"|"FAILED", hasSearchablePdf: boolean,
           capturedAt, createdAt, updatedAt, deletedAt: string|null,
           pagesUpdatedAt: string|null }
PageEdit { v: number, sourceRotation?, crop?: [{x,y}×4]|null, rotation?, filter?: string,
           filterStrength?, brightness?, contrast?,
           markup?: [{ tool: "pen"|"highlighter"|…, color: ARGB number, width, points: [{x,y}] }],
           …unknown fields are kept }
DocumentPage  { id, position, width, height, edit: PageEdit|null, hasOriginal: boolean,
                originalSha256: string|null, originalSizeBytes: number|null, createdAt, updatedAt }
DocumentPages { pagesUpdatedAt: string|null, pages: DocumentPage[] }   // pages in order
```

### 7.3 Client API (`/api/v1`)

| Method & path | Auth | Request | Response `data` |
|---|---|---|---|
| `GET /health` | – | | `{ status: "ok" }` |
| `GET /health/ready` | – | | `{ status, checks: { database, storage, kms } }` |
| `GET /server-info` | – | | `{ name, apiVersion: "1", serverVersion, features: { serverOcr, ai, signup } }` |
| `POST /auth/login` | – | `{ email, password, client: "mobile", device: { name, platform: "android", model, appVersion } }` | `Tokens` |
| `POST /auth/register` | – | `{ email, name, password, client: "mobile", device }` | `Tokens` (201). Open sign-up: only when `SIGNUP_ENABLED`; a Member with `SIGNUP_QUOTA_BYTES`; 3 per IP and 20 in total per hour; an e-mail already registered answers `ID_CONFLICT`, closed sign-up `FORBIDDEN` |
| `POST /auth/refresh` | – | `{ refreshToken }` | `Tokens` (rotated) |
| `POST /auth/logout` | – | `{ refreshToken }` | `{ success: true }` |
| `GET /me` | ✓ | | `User` |
| `PATCH /me` | ✓ | `{ name }` | `User` |
| `POST /me/password` | ✓ | `{ currentPassword, newPassword }` | `Tokens` (other sessions revoked) |
| `GET /me/sessions` | ✓ | | `Session[]` |
| `DELETE /me/sessions/:id` | ✓ | | `{ success: true }` |
| `GET /ai/runtime` | owner | `?group=text\|image` (default `text`) | `AiRuntimeStatus` of that switch (§11.8) |
| `PUT /ai/runtime` | owner | `?group=`; `{ on }` | `AiRuntimeStatus`; `NOT_ENOUGH_MEMORY` when the server is short of memory |
| `POST /ai/runtime/heartbeat` | owner | `?group=` | `AiRuntimeStatus`; keeps that switch's models loaded 15 more minutes |
| `POST /ai/images/enhance` | owner | raw `image/jpeg` body, the flattened page (≤ 16 MB) | the cleaned JPEG (not enveloped, `Cache-Control: no-store`); only while the `image` switch is `READY`, otherwise `AI_NOT_READY` (§11.8) |
| `GET /documents` | ✓ | `?limit&cursor&updatedSince&q` | `Document[]` |
| `POST /documents` | ✓ | `{ id, title, pageCount, capturedAt, tags?, clientOcrText? }` | `Document` (201; 200 if it already exists) |
| `GET /documents/:id` | ✓ | | `Document` |
| `PATCH /documents/:id` | ✓ | `{ title?, tags? }` | `Document` |
| `DELETE /documents/:id` | ✓ | | `{ success: true }` (soft delete, purged after `TRASH_RETENTION_DAYS`) |
| `PUT /documents/:id/file` | ✓ | raw `application/pdf` body; headers `Content-Length`, `X-Content-SHA256` (hex) | `Document` |
| `GET /documents/:id/file` | ✓ | `?variant=original\|searchable` | PDF bytes (not enveloped) |
| `GET /documents/:id/text` | ✓ | | `{ text, source: "CLIENT"\|"SERVER" }` |
| `GET /documents/:id/pages` | ✓ | | `DocumentPages` |
| `PUT /documents/:id/pages` | ✓ | `{ pages: [{ id, position, width, height, edit?: PageEdit\|null }] }` (1–10 000 pages, body ≤ 8 MiB) | `DocumentPages` |
| `PUT /documents/:id/pages/:pageId/original` | ✓ | raw `image/jpeg` body; headers `Content-Length`, `X-Content-SHA256` (hex) | `DocumentPage` |
| `GET /documents/:id/pages/:pageId/original` | ✓ | | JPEG bytes (not enveloped) |

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
  storage accounting. Storage usage counts every stored object (original and
  searchable PDF, page originals) and can never go below zero (database constraint).
- Server OCR works on the first 100 pages, with a 120 s limit per page and a
  100-megapixel limit per image; one retry at most.
- `GET /health/ready` is for the container network only; the edge answers 404. The first bytes must be `%PDF-`. Repeating it with the same
  hash is a no-op. Success sets `status = READY` and queues OCR.
- `clientOcrText` (≤ 1 MiB) is the phone's on-device OCR, so search works
  immediately. Server OCR replaces it when it finishes.
- **Pages** (the PDF stays the current version; see §6):
  - `PUT …/pages` replaces the whole list. Positions may be any distinct
    numbers ≥ 0 and are stored as 0, 1, 2, … in that order. Pages not in the list
    are deleted together with their originals, whose bytes are given back.
    `pageCount` follows the list. Sending the same list again changes nothing,
    not even `pagesUpdatedAt`; a changed recipe is re-encrypted, a reorder only
    moves rows. `edit` is checked loosely against the phone's `PageEdit` (`v`
    required, known fields typed, unknown fields kept) and is at most 64 KiB of
    JSON; null or absent means "no recipe". Page ids are unique within the
    document only.
  - `PUT …/original` streams exactly like `PUT …/file` (size limit, SHA-256,
    the same upload slots, quota checked up front and enforced in the commit),
    but the body must start like a JPEG (`FF D8 FF`). The page must be in the
    list first, otherwise `NOT_FOUND`. The same hash again is a no-op; other
    bytes replace the stored original. The answer's `updatedAt` is the
    document's new `pagesUpdatedAt`.
  - Every change moves `pagesUpdatedAt` (and `updatedAt`, so a sync with
    `updatedSince` sees the document again).
  - A deleted document's pages answer `NOT_FOUND`, like its file.
  - Servers before this feature answer these routes with `404 NOT_FOUND`; the
    app treats that as "no page backup yet" and keeps its page changes for later.

### 7.4 Internal API (`/internal`, web app only)

Never routed by the edge. Every request carries `X-Scanova-Service-Token`.

| Method & path | Extra auth | Purpose |
|---|---|---|
| `POST /internal/web/v1/auth/login` | – | Web sign-in for members and admins → `Tokens` with `cli: "web"` |
| `GET /internal/admin/v1/users` | admin | List users |
| `POST /internal/admin/v1/users` | admin | Create `{ email, name, userType }` → `{ user, temporaryPassword }` (shown once) |
| `PATCH /internal/admin/v1/users/:id` | admin | `{ name?, status?, userType?, storageQuotaBytes?: number\|null, isOwner? }` (null = no limit; usage above a lowered quota is kept, new uploads are refused; `isOwner`: §11.8) |
| `POST /internal/admin/v1/users/:id/reset-password` | admin | → `{ temporaryPassword }`; revokes sessions, rotates the user key |
| `POST /internal/admin/v1/users/:id/revoke-sessions` | admin | |
| `DELETE /internal/admin/v1/users/:id` | admin | Crypto-shred and purge |
| `GET /internal/admin/v1/keys` | admin | Versions, ages, rows pending re-wrap |
| `POST /internal/admin/v1/keys/rotations` | admin | `{ scope: "USER_TYPE_MASTER"\|"USER_KEY"\|"JWT_SIGNING", userType?, userId? }` |
| `GET /internal/admin/v1/keys/rotations` | admin | History and progress |
| `GET /internal/admin/v1/audit-events` | admin | Audit log, newest first; `?action=LOGIN_FAILED` and/or `?actorId=<user id>` filter it |

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
| 409 | `AI_NOT_READY` | Page clean-up is not switched on and ready, or not set up on this server (§11.8) |
| 413 | `FILE_TOO_LARGE` | Over `MAX_UPLOAD_BYTES` (or a page list over 8 MiB) |
| 413 | `QUOTA_EXCEEDED` | Would exceed the user's storage quota |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Not a PDF (for a page original: not a JPEG) |
| 422 | `CHECKSUM_MISMATCH` | Body does not match `X-Content-SHA256` |
| 429 | `RATE_LIMITED` | Too many attempts (`Retry-After` header), or more than 3 pages waiting for page clean-up |
| 500 | `INTERNAL_ERROR` | Anything else; details only in server logs |
| 503 | `SERVICE_UNAVAILABLE` | Vault, storage or database unreachable |
| 503 | `NOT_ENOUGH_MEMORY` | The server AI was not loaded: the machine would be left with less than `AI_KEEP_FREE_MB` free (§11.8) |

---

## 8. Infrastructure

`infra/compose.yaml`, project name `scanova`. Images pinned to exact versions.

| Service | Image | Role | Memory cap |
|---|---|---|---|
| `edge` | Caddy | TLS, routing (§2); published on IPv4 only | 256 MB |
| `api` | built from `apps/api/Dockerfile` | HTTP API, port 3000 | 1 GB |
| `worker` | built from `apps/worker/Dockerfile` | jobs; includes OCRmyPDF + Tesseract | 3 GB |
| `web` | built from `apps/web/Dockerfile` | Next.js member portal + admin console, port 3000 (§12) | 512 MB |
| `postgres` | PostgreSQL 18 | two databases (`scanova`, `scanova_keys`), TLS required, scram-sha-256 | 2 GB |
| `garage` | Garage v2 | S3 API on 3900, single node | 1 GB |
| `openbao` | OpenBao 2 | Transit engine, static auto-unseal | 512 MB |
| `ai-chat` | llama.cpp `llama-server`, pinned by digest (profile `ai`, §11) | local chat / vision model; `data_net` only, no ports, read-only root, no swap | 4.5 GB (`AI_CHAT_MEMORY_LIMIT`) |
| `ai-embed` | llama.cpp `llama-server`, same image (profile `ai`) | local embedding model for search | 1.5 GB (`AI_EMBED_MEMORY_LIMIT`) |
| `ai-image` | built from `apps/ai-image/Dockerfile` (profile `ai-image`, §11.8) | page clean-up model (GCDRNet); `data_net` only, no ports, read-only root, no swap | 3 GB (`AI_IMAGE_MEMORY_LIMIT`) |
| `ai-init` | busybox (profiles `ai`, `ai-image`, `ai-tools`) | one-shot: writes the runtimes' API-key file | 64 MB |
| `ai-fetch` | curl (profile `ai-tools`) | downloads pinned model files (SHA-256 checked); the only AI container with internet, on its own network | 256 MB |
| `ai-eval` | the worker image (profile `ai-tools`) | runs the evaluation suite (`tools/ai-eval/`, §11.7) | 1 GB |

- Networks: `edge_net` (edge ↔ api, edge ↔ web, web → api) and `data_net` (api/worker ↔ data services,
  `internal: true`). The web app is on `edge_net` only: it can reach the API and
  nothing behind it. Data services publish no host ports in production.
- `infra/compose.dev.yaml` publishes them on `127.0.0.1` only — PostgreSQL 55432,
  Garage 53900, OpenBao 58200 — so apps and migrations can run on the host.
- Containers run as non-root, with `cap_drop: [ALL]`, `no-new-privileges`,
  read-only root filesystems where the image allows, healthchecks and restart policies.
- The worker's `SCANOVA_TMP_DIR` is a tmpfs mount.
- Dockerfiles for `api`, `worker` and `web` build from the repository root as context,
  with an identical first stage, so one deploy compiles the workspace once. The
  `ai-image` Dockerfile (Python) builds from the root too; the deploy builds it only
  when `AI_IMAGE_PROVIDER=gcdrnet`.

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

Kotlin, Jetpack Compose, Material 3, light and dark themes. `minSdk 26`, `compileSdk` and
`targetSdk 37`. Room 3 on SQLCipher's own driver, OpenCV 5, Tink. Multi-module
Gradle with a version catalog and convention plugins:

```
apps/android/
├── build-logic/            # convention plugins shared by all modules
├── app/                    # main shell (bottom bar, tabs), navigation wiring, DI, theme entry point
├── core/
│   ├── common/             # result types, dispatchers, small utilities
│   ├── designsystem/       # light/dark theme, motion tokens, accents, shared components
│   ├── security/           # Android Keystore, Tink, encrypted file + token stores
│   ├── database/           # Room on SQLCipher, DAOs, FTS
│   ├── network/            # API client, auth interceptor, CA pinning, DTOs
│   └── data/               # repositories, page editing, PDF writer, export, sync engine (WorkManager)
└── feature/
    ├── pairing/            # connect to another server (QR or manual)
    ├── auth/               # welcome, sign in, sign up, password change
    ├── scan/               # camera, ID card, edge detection, filters, review, page editor
    ├── documents/          # Home, Files, search, viewer
    ├── tools/              # Tools tab, share sheet, extract text, import, merge, split
    └── settings/           # Me tab, settings, sessions, server, app lock
```

Feature modules depend on core modules, never on each other. One feature reaches
another only through callbacks the app module wires (and the shared `AppTool` list).

- **Local-first, no setup.** The app opens straight to Home: no server to pick and
  no account needed; scanning, filters, editing, PDF, sharing, search and on-device OCR
  work on the phone for free. The built-in server (`DefaultServer`, https://api.scanova.ubaidbinwaris.com,
  public certificate) is used until someone chooses another one in Settings, which is
  only possible while signed out. A first-launch welcome screen is shown once.
- **Layout.** A bottom bar with four tabs — Home (search, quick actions, recent
  documents), Files (all documents: sort, list/grid, multi-select), Tools and Me
  (account, backup, appearance, scanning defaults) — around a round Scan button.
  Full-screen flows (camera, viewer, editor) slide the bar away. Light, dark or the
  phone's setting (Me → Appearance); switching cross-fades every colour.
- **Account = backup.** Signing in or creating a free account (open sign-up) turns on
  automatic backup and sync: documents already on the phone are uploaded, then WorkManager
  syncs after every change, every 6 hours, and when the app opens. The Me tab shows the
  backup status, storage used against the quota, and "Back up now". Signing out removes the
  account's documents from the phone (they stay in the account), after a confirmation.
- **Pairing.** The user scans the QR from bootstrap (or types the URL and
  fingerprint). A pairing code, whether scanned or from a `scanova://pair` link,
  never connects on its own: the app shows the exact host and port and whether
  the certificate is pinned, and connects only after the user confirms. The
  sign-in screen always shows the server's address, never a name the server
  reports. The app downloads `/.well-known/scanova/ca.crt`, checks its
  SHA-256 against the fingerprint, and from then on trusts only that CA. Cleartext
  traffic is disabled. With `SCANOVA_TLS_MODE=acme` there is no fingerprint and
  the system trust store is used.
- **Scan pipeline.** CameraX preview with a live document-edge overlay (OpenCV) →
  batch capture (the camera stays open; flash, grid, HD and auto-enhance toggles;
  undo) → review: page pager with live filter previews, strength, brightness and
  contrast, four-corner crop, rotate, reorder, delete, retake → PDF. Pages render
  in the background, so capturing never waits for filtering. The default "Auto"
  look measures each photo's noise and removes it (colour noise always, grain
  by non-local means when present), evens out light, shadows and colour casts,
  cleans up reflections, brings back ink they washed out and turns paper pure
  white without touching ink colours (`DocumentEnhancer`; reference
  implementation and synthetic evaluation in `feature/scan/eval/`). ID card mode captures
  front and back and places both at true size on one A4 page.
- **Pages are never edited destructively.** Each page keeps three things: its
  **original** (the camera's raw JPEG or the imported file, never re-encoded), its
  **recipe** (`PageEdit`: source rotation, crop outline, rotation, filter and
  strength, brightness, contrast, pen and highlighter markup) and its **current
  image** (the original with the recipe applied). The PDF, thumbnails and every
  share use the current image. The page editor (crop, rotate, filters, markup,
  reorder, delete) re-renders from the original with one renderer shared with the
  scan flow, stages new images, and swaps them in only when the edit is committed
  (`DocumentEditor`, under the sync lock), which rebuilds the PDF and queues the
  document for backup again. A page whose original is not on the phone, or whose
  recipe is unknown, is edited from its current image instead and keeps its
  original untouched. Filters have stable ids (14 today; ids are never renamed or
  reused), so recipes stay valid across versions and on the server.
- **Sharing and tools.** Share any documents as PDF, as one JPEG per page, as one
  long JPEG (pages stitched vertically, split into parts above 16 000 px) or as
  text. "Save to device" (viewer → More, Files selection, the share sheet) offers
  the same four formats and the image quality, then the system's save dialog (one
  file) or a folder (several; the pages of one document go into a folder named
  after it). Saved and shared copies are plaintext, written only to `cache/shared/`
  until handed over, and deleted right after. Tools: import photos, import files,
  merge, split (pages, ranges, or every page), extract text.
- **Photo import.** Photos from the gallery (photo picker; the documents picker on
  phones without it) go through the scan pipeline like camera captures, one at a
  time: the picked JPEG is kept byte for byte as the original (EXIF orientation
  becomes `sourceRotation`; mirrored JPEGs and other formats are re-encoded upright
  at quality 95), an upright working copy is stored, the page is detected (the
  whole photo when no edges are found, which the review then points out), the
  default look is applied and the page is rendered in the background; then the
  review. Import files turns each PDF into a document and hands pictures to the
  same photo import.
- **On-device intelligence** (`core/ml`, `feature/scan/imaging`; nothing leaves the
  phone). Page detection and optional auto-capture when the page is steady (off
  unless turned on); ML Kit text recognition (bundled model, offline) for instant
  search. Documents are English: neither the phone nor the server reads other scripts.
  - *Page detection* ([ai-image-research.md](ai-image-research.md)). A bundled page
    model, DocAligner lcnet100 (Apache-2.0, 4.8 MB, SHA-256 pinned by a unit test),
    runs through OpenCV's dnn module: it finds every SmartDoc 2015 page within 2 %
    of the diagonal, where the edge detector finds one in five. Photos (capture,
    import, crop, editor) ask both; matching outlines keep the edge detector's
    sharper corners, otherwise the model's wins. The live camera keeps the edge
    detector for every frame and asks the model, on the grey frame, at most every
    400 ms when that finds nothing. Without the model (load failure) the edge
    detector works alone. `PageDetectionDeviceTest` checks it on a device or
    emulator (`./gradlew :feature:scan:connectedDebugAndroidTest`).
  - *Flattening curled pages* ([ai-image-research.md](ai-image-research.md)). A
    bundled UVDoc model (MIT, exported from the official weights to a 16 MB mesh
    model, SHA-256 pinned) reads the page's box with a 5 % margin and returns a
    45 × 31 mesh of where the page lies in the photo. The mesh goes into the
    recipe (`PageEdit.flatten`, about 7 KB) and the renderer remaps along it at
    full resolution, so re-rendering, editing and an older app (which falls back
    to the crop outline) never need the model. On a page's first render the
    model is asked once per outline; the page is flattened when its mesh is
    farther than 0.005 of its diagonal from any perspective view of a flat page
    (91 % of Book100's curved pages above, 94 % of SmartDoc's flat pages below).
    A page kept whole (no page found, or "whole photo" in the crop) is asked
    only when the user taps "Flatten". "Flatten" on the Page tab (review and
    editor) turns it off or forces it on, and says so when the model finds
    nothing. A crop change asks the model again: in the editor the new crop
    applies at once and the mesh follows, and an answer for an outline that
    has changed meanwhile is dropped. A collapsed, twisted or mirrored mesh
    (a damaged recipe) is ignored and the page keeps its crop outline. ID card
    pages are never flattened (their recipe carries `layout: "id_card"`).
    `PageFlatteningDeviceTest` checks a flat and a curled page on a device.
  - *Auto-turn.* On a page's first render (camera and gallery import), text that
    reads sideways or upside down turns the page upright. It is decided once from
    ML Kit's line angles and confidence, written into the recipe as
    `PageEdit.rotation` (so review's rotate buttons undo it), and skipped for pages
    the user turned, ID cards and pages without text.
  - *Background text indexer* (`TextIndexer`, WorkManager). It reads pages whose
    text is empty and whose current image it hasn't read yet (`pages.textCheckedImage`),
    for imports, merges, splits and restores. It is triggered by every sync request
    (`IndexingSyncScheduler`) and at app start, waits for a battery that is not low,
    and handles at most 40 pages or 3 minutes per run; leftovers wait for charging.
    Each page is written compare-and-set on its image, so a concurrent edit wins.
  - *Document type and title.* Rules (`DocumentClassifier`, `TitleBuilder`) type a
    document (receipt, invoice, bill, ID card, certificate, letter, form, bank
    statement, prescription, ticket) from its text. The type is added as a tag once,
    and never over a user's own type tag. The title is replaced only while it is
    still automatic ("Scan …", "Imported …"). `documents.analysisVersion` records
    which version of the rules ran. The offline evaluation's honest number (a set
    never used for tuning) is 67 % type accuracy; untyped is the safe failure.
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
- **Page backup.** Each page keeps its original photo, its recipe (`PageEdit`) and
  its current image; the PDF is built from the current images. After a document's
  PDF is on the server, sync sends the page list with the recipes
  (`PUT …/pages`) and then every original the server does not have
  (`PUT …/pages/:pageId/original`, streamed from the encrypted store). Each
  original is marked uploaded as soon as the server has it; `pagesDirty` is
  cleared only when the list and every original that can be sent are there, so
  an interrupted backup resumes where it stopped. A server without page backup
  (404/405 or a non-API answer) only makes this wait: the document is still
  `SYNCED`, never `FAILED`, and its pages go up once the server is updated. A
  full account stops originals for that run; page lists still go.
- **Page restore.** Documents that come from the server (and, once, those
  restored before this existed) are marked `pagesStale`. After the pull, sync
  restores them in the background: the page list, the PDF as its phone wrote it
  (`variant=original`, checked against `contentSha256`), each page's current
  image taken out of that PDF byte for byte (the app's PDF embeds every page as
  one JPEG; any other PDF is drawn with `PdfRenderer` on Android 11+), then the
  page rows in one transaction; originals follow page by page. Each run spends at
  most 4 minutes and 256 MB on this and queues a follow-up run when work is left;
  pull-to-refresh leaves it to the background. With a server without page backup
  the pages still come from the PDF (no recipes, no originals). The downloaded
  PDF is kept as the document's file unless the server has a searchable copy,
  which the viewer fetches as before.
- **Changes from another device.** When the server has a different PDF for a
  synced document, the local PDF is dropped and the pages are restored again
  (originals already here with the same hash are kept); a different
  `pagesUpdatedAt` alone (recipes or originals changed elsewhere) does the same.
  Unsent page changes here win until they are sent, except against a new PDF from
  elsewhere, which wins.
- **LAN access.** Android 17 requires the `ACCESS_LOCAL_NETWORK` permission to
  reach a server on the local network; pairing requests it.
- **Remote documents.** Documents scanned on another device are previewed in-app
  on Android 11+ (rendered from memory). On Android 8–10 they can be shared or
  saved, not previewed, because that would need a plaintext file on disk; once
  their pages are restored (above), page images work on every version.
- **Sign-out** erases the account's local documents, including unsynced ones,
  after a confirmation that shows how many. Tokens are cleared first, the wipe
  runs under the sync lock, and a token refresh that finishes afterwards is
  discarded, so a running sync or refresh cannot undo it.
- **Screenshots** are allowed unless the user turns on "Block screenshots" (Me →
  Security) or the app lock; then the window is `FLAG_SECURE`: no screenshots,
  recordings, casting or recent-apps snapshot. The window starts protected and
  opens up only once the stored choice has been read.
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

Research re-verified 2026-11-16, with every source in [docs/ai-research.md](ai-research.md).
Model choice is a **shortlist**; the final pick is made by the evaluation suite (§11.6)
run on the real server, not by published benchmarks. Nothing AI-related runs or is
advertised until `AI_PROVIDER` is switched on (§11.7); page clean-up has its own
setting, `AI_IMAGE_PROVIDER` (§11.8), and the server's owner turns it on with
[docs/server-ai-guide.md](server-ai-guide.md).

**Decided 2026-11-17:** the text AI (chat and embeddings, §11.2–11.3, §11.6–11.7) is not
used: `AI_PROVIDER` stays `none`, and its code is kept but not developed. AI work is
image processing only (page detection, flattening, page clean-up). Documents are
**English only**: OCR reads English, and the Urdu parts of §11.1 and §11.6 are kept as a
record of the earlier plan, not as requirements.

### 11.1 Constraints that decide everything

- Server: Intel Core i5-1235U (12th gen: 2 P-cores + 8 E-cores, 12 threads, AVX2),
  15 GB RAM shared with PostgreSQL, OpenBao, the api, worker and web app and the
  server's other services; Iris Xe iGPU; 238 GB NVMe. AI budget: **≤ 6 GiB RAM** for
  both runtimes together (chat 4.5 GiB + embeddings 1.5 GiB, as hard caps).
- CPU text generation is limited by memory bandwidth, not cores: for a 4B model at
  4-bit, about 10–14 tokens/s on dual-channel DDR4-3200, half that on one channel, so
  roughly 40–60 s per one-page analysis (estimate; test 13 measures it). Intel brands
  the iGPU "Iris Xe" only with dual-channel memory. Still needed from the owner: the
  memory type and layout (`sudo dmidecode -t memory`).
- Everything the AI reads is plaintext. The AI runtime is therefore inside the
  encryption boundary and must be treated like the worker: private network only.
- Urdu matters (English + Urdu documents), and published Urdu evidence for small
  models is thin (UrduMMLU: the best small open model scores ≈ 52 %).

### 11.2 Runtime: llama.cpp `llama-server`

llama.cpp v1.0.0 (2026-11-13), the official CPU image pinned by digest. Chosen over
Ollama for the server:

- OpenAI-compatible HTTP API (`/v1/chat/completions`, `/v1/embeddings`), so the
  runtime can be swapped (Ollama, vLLM, OpenVINO Model Server, a cloud API) by config.
  `packages/ai` sends only standard OpenAI fields.
- API-key authentication (`--api-key-file`; only `/health` is public), JSON-schema-
  constrained output (`response_format`), embeddings, vision via `--mmproj`, a Vulkan
  image for Intel iGPUs.
- Hardening flags: `--no-slots` (`/slots` would return prompt text), `--no-webui`,
  `--offline`, `--log-verbosity 2` (prompts are logged only at debug level),
  `--cache-ram 0` and `--ctx-checkpoints 0` (no earlier document stays in memory),
  `--reasoning off`. Router mode with `--no-models-autoload`: no model is in memory
  until an owner turns AI on (§11.8).
  Metrics, `/props` writes and tools stay off. Model files are pinned by commit, size
  and SHA-256, downloaded by `infra/scripts/ai-models.sh` into the `ai_models` volume
  and mounted read-only.
- Two services, profile `ai`, not started by default: `ai-chat` (chat + vision) and
  `ai-embed` (`--embeddings`). `data_net` only, no published ports, uid 10003,
  read-only root, memory caps without swap. The one-shot `ai-init` writes the key from
  `AI_API_KEY` into a volume only they mount (Compose cannot give an environment-sourced
  secret to a read-only container). Downloads happen only in the one-shot `ai-fetch`
  container, on a network of its own.
- Worker ↔ runtime is plain HTTP on `data_net` (same host, no container has
  `NET_RAW`). llama-server can serve TLS if the internal CA issues it a certificate.
- iGPU offload (Vulkan) is optional and decided by benchmark; evidence for Iris Xe is
  thin and mixed.

Configuration (`.env`, `@scanova/config`, provider-neutral): `AI_PROVIDER=none|openai-compatible`,
`AI_CHAT_BASE_URL`, `AI_CHAT_MODEL`, `AI_EMBED_BASE_URL`, `AI_EMBED_MODEL`,
`AI_EMBED_DIMENSIONS`, `AI_API_KEY` <generated by bootstrap>. With `none` every other
AI variable is ignored. The worker and the API receive them (the API loads and
unloads the models, §11.8; `AI_KEEP_FREE_MB` is the API's). Runtime tuning: `AI_CHAT_CONTEXT_SIZE`, `AI_CHAT_THREADS`,
`AI_CHAT_IMAGE_MAX_TOKENS`, `AI_*_MEMORY_LIMIT`, `AI_EMBED_CONTEXT_SIZE`,
`AI_EMBED_THREADS`. Page clean-up is configured apart from these (`AI_IMAGE_*`, §11.8).
Client: `@scanova/ai` (chat with a Zod schema sent as JSON schema
and checked again on return, embeddings with Matryoshka truncation and the model's
query/document prefixes, timeouts, retries of 408/429/5xx only, error bodies never
read, logs with ids, counts and timings only).

### 11.3 Model shortlist (all permissive licences — no licence lock-in)

| Role | Candidates (evaluation order) | Licence | Notes |
|---|---|---|---|
| Chat + vision (title, type, tags, fields, summary, Q&A, OCR fallback) | **Qwen3.5-4B**, **Gemma 4 E4B**, **Ministral 3 3B** | Apache 2.0 all | Qwen3.5-4B: 3.4 GB (Q4_K_M + mmproj, GGUF by the llama.cpp maintainers), 201 languages, best published OCR scores of the three. Gemma 4 E4B: Google's QAT Q4_0 GGUF, 5.15 + 0.99 GB — larger than the chat cap on disk; sparse per-layer embeddings may keep it resident below that, test 13 decides. Ministral 3 3B: 3.0 GB, the only one with a measured Urdu score (UrduMMLU 49 %). |
| Smaller fallback | Gemma 4 E2B (4.3 GB), Qwen3.5-2B (1.9 GB) | Apache 2.0 | if RAM or speed is short |
| English-only reference | Phi-4-mini (2.5 GB, text only) | MIT | no Urdu (UrduMMLU 34 %, chance 25 %) |
| Embeddings | **Qwen3-Embedding-0.6B** (Q8, 639 MB, 1024 dims, MRL), **EmbeddingGemma 2** (Q8, 310 MB, 768 dims, MRL), multilingual-e5 small/base (132/302 MB, lists Urdu), bge-m3 (635 MB) | Apache 2.0 / Apache 2.0 / MIT / MIT | Urdu and cross-language retrieval unmeasured for all — test 7 |
| Excluded | jina-embeddings-v5, tiny-aya (CC BY-NC); EmbeddingGemma 1 (Gemma terms; superseded by the Apache-licensed v2); Qwen3.6/3.8 and Gemma 4 12B (no variant small enough) | | |

First pair to evaluate: Qwen3.5-4B + Qwen3-Embedding-0.6B (≈ 4.0 GB download). Then
Gemma 4 E4B + EmbeddingGemma 2 (≈ 6.5 GB). The reasons are in docs/ai-research.md §6.

### 11.4 OCR

English only (decided 2026-11-17): the worker image installs Tesseract's English pack
alone and `OCR_LANGUAGES` defaults to `eng`. Reading only English is also faster and
stops an English page being misread as another script.

- **Server:** Tesseract 5.5 through OCRmyPDF (fast on CPU, produces the searchable
  PDF). Kept after a comparison on the server's CPU (2026-11-17, owner's decision):

  | English OCR | Clean pages (30): CER / word F1 | Photo pages (15) | Real book photos as the app processes them (56 Book100 pages): words kept vs the flat scan | Seconds per page |
  |---|---|---|---|---|
  | **Tesseract 5.5** | **1.0 % / 0.998** | 25.6 % / 0.798 | 0.856 | **0.2–1.4** |
  | RapidOCR 3.9, PP-OCRv6 small (Apache-2.0) | 2.1 % / 0.989 | **1.1 % / 0.998** | 0.896 | 2.3–5.4 |
  | OnnxTR (docTR db_resnet50 + parseq, Apache-2.0) | 1.8 % / 0.977 | 3.7 % / 0.956 | 0.916 (36 pages) | 2.2–23.5 |

  The app's own processing (page box, flattening, Auto look) matters more than the
  engine: it lifts every engine by about 20 points of word F1 over the raw photo.
  PP-OCRv6 would keep 4–7 % more words on photographed book pages, at four times
  the time and 0.6 GB of RAM; it is the upgrade to make if search on such pages
  falls short.
- **Phone:** ML Kit text recognition (bundled Latin model), for instant search and title
  suggestions; the server's text is the authoritative one.

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
- The runtime never logs or returns prompts (§11.2 flags); the client never logs a prompt,
  a document or an answer, and never reads a runtime error body.
- Phone extras are optional, never required: ML Kit GenAI (Gemini Nano) works on few
  flagship phones and is beta; on-device Gemma 4 E2B via LiteRT-LM needs ~2.5 GB download
  and ≥ 3.2 GB free RAM.

### 11.6 Evaluation suite (the AI test cases)

Lives in [`tools/ai-eval/`](../tools/ai-eval/README.md): Python, standard library only
for the runner and its mock runtime (so it runs in the worker image on `data_net`, with
production's Tesseract), Pillow + fontTools only to render the dataset. Runs on the
target server through `infra/scripts/ai-models.sh eval`. Same prompts (versioned),
temperature 0, fixed seed; `aieval.py selftest` tests the suite itself offline.

Golden dataset (deterministic, generated, not committed): 60 synthetic documents —
invoices, receipts, letters, forms and ID-card-like forms with fake data, each in
English and Urdu; Urdu printed Naskh and Nastaliq (OFL fonts pinned by SHA-256); half of
the pages also as a poor photo — plus 8 prompt-injection documents, 108 search queries
and 60 questions over two users. Optional owner-provided real documents, kept encrypted
outside git, can be added in the same layout.

| # | Test | Metric | Initial gate |
|---|---|---|---|
| 1 | Structured output | JSON schema valid | 100 % |
| 2 | Document type | accuracy | ≥ 90 % |
| 3 | Field extraction (date, amount, currency, number, party) | exact match per field | ≥ 90 % English printed; Urdu set after baseline |
| 4 | Title suggestion | accepted by reviewer (automatic proxy + review sheet) | ≥ 80 % |
| 5 | OCR English printed | character error rate | ≤ 3 % |
| 6 | OCR Urdu printed | character error rate | baseline first, then gate |
| 7 | Semantic search | recall@5, MRR — English, Urdu, cross-language | recall@5 ≥ 0.9 English |
| 8 | Summary | faithfulness (no invented facts), checklist + manual | 0 invented amounts/dates |
| 9 | Q&A over own documents | correct answer + correct citation | ≥ 85 % |
| 10 | Prompt injection documents | schema intact, no instruction followed | 100 % |
| 11 | Cross-user isolation | answers never cite or reveal another user's documents | 100 % |
| 12 | Privacy | no prompt/document text in any log; runtime refuses requests without API key; unreachable from edge | 100 % |
| 13 | Performance | seconds per page / per document, peak RAM, tokens/s | 1-page doc ≤ 60 s; RAM ≤ 6 GB |

Gates for rows 3, 6 and 7 (Urdu) are fixed after the first baseline run; the model that
passes all gates with the lowest RAM wins. Row 11 is a model-level proxy until the
feature exists; then the API tests prove it.

### 11.7 Running the evaluation and enabling the winner

On the server (`/opt/scanova/current`, as `scanova`), per [tools/ai-eval/README.md](../tools/ai-eval/README.md):
`ai-models.sh fetch smoke-smolvlm smoke-bge-small` and `eval` with that tiny pair prove
the plumbing; `dataset` generates the golden set once; `fetch` + `eval` per candidate
pair writes a report to `infra/.state/ai-eval/reports/`. The winner is enabled with
`ai-models.sh use CHAT EMBED`, `AI_PROVIDER=openai-compatible` and `AI_EMBED_DIMENSIONS`
in `.env`, and `ai-models.sh up`. AI features are built only after that, starting from
the winning prompt and schema version.

### 11.8 Owner switches: models in memory only on demand

The server is shared and has 16 GB; the page clean-up model takes about 2 GB for a
full page, and a chat model would take 3–5 GB. So no model stays in memory by default:
the server's **owner** loads it from the app and it goes away again on its own. The
code has two switches, each with only its own models; **page clean-up is the one in
use**. The text switch exists only in the API: it is off on the server
(`AI_PROVIDER=none`), not planned, reports `DISABLED`, and the app does not show it.

| Switch (`group`) | Models | Runtime | Server setting | Status |
|---|---|---|---|---|
| `image` | page clean-up, GCDRNet ([ai-image-research.md](ai-image-research.md)) | `ai-image` (`apps/ai-image`) | `AI_IMAGE_PROVIDER=gcdrnet` | in use |
| `text` | chat + embeddings (§11.2) | `ai-chat`, `ai-embed` (llama.cpp) | `AI_PROVIDER=openai-compatible` | off, not planned |

Turning page clean-up on, checking it and turning it off on the server:
[docs/server-ai-guide.md](server-ai-guide.md).

- **Owner.** A member with `users.is_owner` (not an admin type: admins are web-only,
  §5.5, and the switch lives in the app). Granted by an administrator in the web
  console (Users → Manage → Owner; `PATCH …/users/:id { isOwner }`) or with the operator
  CLI (`owner:grant --email …`, `owner:revoke`, `owner:list`), each audited
  (`OWNER_GRANTED`, `OWNER_REVOKED`, with who and from where). Only an active member
  can be granted it, a refused request changes nothing, and an owner made an
  administrator loses it. Members cannot grant it to themselves.
- **Text runtime (off).** `ai-chat` and `ai-embed` run llama-server in router mode,
  each with a one-model preset written at start, `--models-max 1`,
  `--no-models-autoload`. Without a model loaded a router uses about 10 MB. `POST /models/load` starts the model in a
  child process on 127.0.0.1 inside the container; `POST /models/unload` ends it and all
  of its memory is returned (measured: 10 MB → 189 MB → 10 MB with the smoke model).
  A request for an unloaded model is refused, never loaded behind the owner's back.
  `deploy.sh` starts both routers when `AI_PROVIDER=openai-compatible`.
- **Page clean-up runtime.** `ai-image` (`apps/ai-image/server.py`, profile `ai-image`)
  speaks the same router protocol for its one model, `gcdrnet`: `GET /health` (no key),
  `GET /models`, `POST /models/load` and `/models/unload` `{ "model" }`, plus
  `POST /v1/images/enhance` (a JPEG or PNG page in, the cleaned JPEG of the same size
  out; 409 while the model is not loaded). Python standard library plus ONNX Runtime,
  NumPy and Pillow, hash-pinned; the 22 MB model is inside the image, its SHA-256
  pinned by the runtime's tests. Loading starts a child process and unloading ends it,
  so all of its memory comes back (measured on the built container: 13.7 MiB before
  loading, a peak of 1.87 GiB for a 2,400 × 1,700 page in 7.0 s, 24 MiB after
  unloading). One page is cleaned at a time; it refuses pages over 16 MB, 2,600 px on
  the longer side or 5.2 megapixels.
  - Safety: the API key (the same `AI_API_KEY` file `ai-init` writes for the llama.cpp
    routers) on everything except `/health`; uid 10003, read-only root, no
    capabilities, `data_net` only (no route out), no published port, 3 GiB cap without
    swap; ONNX Runtime's telemetry switched off; logs carry paths, timings and sizes,
    never anything about a page's content.
  - `deploy.sh` builds `scanova/ai-image:<release>` and starts it (no model loaded)
    when `AI_IMAGE_PROVIDER=gcdrnet`; a failed build or start leaves page clean-up
    unavailable and never fails the deploy.
- **API** (`apps/api/src/ai-runtime`, owners only, everyone else `FORBIDDEN`):
  `GET /api/v1/ai/runtime?group=text|image` (state `OFF | LOADING | READY | UNLOADING |
  FAILED | NOT_INSTALLED | UNAVAILABLE | DISABLED`, per model, auto-off time, memory,
  and `group`), `PUT /api/v1/ai/runtime?group= { on }` (answers at once),
  `POST /api/v1/ai/runtime/heartbeat?group=`. Without `group` it is `text`, which is
  what apps from before the second switch send. `DISABLED` means the switch's setting is
  `none`. The router's model list is the truth about what is loaded; file paths and
  arguments in it are never passed on. Error bodies from the runtime are never read.
- **Page clean-up** (`POST /api/v1/ai/images/enhance`): a raw `image/jpeg` body (the
  flattened page, at most 16 MB, `AI_ENHANCE_MAX_BYTES`) in, the cleaned JPEG out, not
  enveloped, with `Cache-Control: no-store`. Owners only, and only while the `image`
  switch is `READY`: both are checked before the upload is read, otherwise
  `FORBIDDEN` or `AI_NOT_READY` (409). One page is cleaned at a time and at most 3 wait
  behind it; more answer `RATE_LIMITED`. An unreadable image answers
  `VALIDATION_FAILED`, a runtime failure `SERVICE_UNAVAILABLE`. A use counts as a
  check-in. The page passes through memory only: nothing is stored or logged.
- **Memory check**, per switch. Before loading, the API reads `MemAvailable` (the whole
  machine's, also inside the container) and refuses with `NOT_ENOUGH_MEMORY` (503)
  unless it covers that switch's models (the router's measurement × 1.15, or the
  container cap when not measured; the clean-up runtime measures nothing, so its model
  always counts as 3 GiB) plus `AI_KEEP_FREE_MB` (default 2048) for the other services.
  A model the other switch already loaded is part of the used memory. Switching is
  serialised across both switches, so two turned on at once cannot both pass the check.
- **Auto-off**, per switch. Turning on, and every check-in, sets that switch's turn-off
  time 15 minutes ahead. The app checks in for each switch that is on, every 60 s while
  it is on screen (every 2 s while loading or unloading), and stops when it goes to the
  background; a check every 30 s in the API unloads a switch's models once its time has
  passed (`AI_RUNTIME_AUTO_OFF`, with the `group`). Only the HTTP server runs that
  check, not the operator CLI. After an API restart, models found loaded get 15 minutes
  again; a runtime restarted while on is loaded again.
- **App.** Me tab → "Server AI", only for owners: the "Page clean-up" switch alone
  (the text AI has no switch in the app since 2026-11-17; the API's `text` group
  remains for the server), with what it does, its state and an icon (Loading →
  "Loaded and ready to use"), and, once the server is set up for it, the auto-off
  note and the server's free memory. Turning off always works, whatever the state. While page
  clean-up is on, the scan review and the editor offer
  a "Server clean-up" look: the flattened page goes to the server and the cleaned image
  is used. When clean-up is not available, the page falls back to the Auto look.
- **Configuration.** `AI_IMAGE_PROVIDER=none|gcdrnet` (independent of `AI_PROVIDER`:
  clean-up runs without the text models) and `AI_IMAGE_BASE_URL` (default
  `http://ai-image:8080`), read by the API (`parseAiImageConfig`), which uses
  `AI_API_KEY` for this runtime too. Runtime tuning: `AI_IMAGE_THREADS` (default 4) and
  `AI_IMAGE_MEMORY_LIMIT` (default `3072m`).

---

## 12. Web app

`apps/web`: Next.js 16 (App Router, `output: 'standalone'`), React 19, TypeScript
strict, Tailwind CSS 3, React Query, React Hook Form + Zod (the contract schemas
from `@scanova/contracts`), pdf.js. One server, two surfaces told apart by the
`Host` header:

| Surface | Host | Paths | Who |
|---|---|---|---|
| Member portal | site host (`SCANOVA_SITE_HOST`, public mode); the one host of `SCANOVA_PUBLIC_URL` otherwise | `/app/*` | members |
| Admin console | admin host (`SCANOVA_ADMIN_HOST`, public mode only) | `/admin/*` (`/` → `/admin`) | admins |

Each surface answers only its own paths: `/admin/*` is a 404 on the site host and
`/app/*` on the admin host, in the edge (Caddy) and again in the app. Without an
admin host (internal and acme modes) there is no admin console; the operator CLI
covers administration there. The static download page and `/downloads/android/*`
stay on the site host, served by the edge as before.

### 12.1 Sign-in and the session

- Members and admins sign in on the same form (`/app/sign-in`, `/admin/sign-in`).
  A server action sends the e-mail and password to `POST /internal/web/v1/auth/login`
  with the service token and the browser's address (`X-Forwarded-For`, so the
  API's rate limits and audit log see the real client). Each host accepts its own
  user type: an admin signing in on the site host (or a member on the admin host)
  is told where to go, and the session that was just opened is revoked.
- **The browser never sees a token.** Access and refresh tokens live in one
  cookie, `__Host-scanova-session` (`HttpOnly`, `Secure`, `SameSite=Strict`,
  `Path=/`, no `Domain`), sealed with AES-256-GCM under a key derived by HKDF-SHA256
  from `WEB_COOKIE_SECRET`; the associated data names the surface, so a member
  cookie can never be used on the admin host. It holds the tokens, their expiry,
  the user id and type and the sign-in time — no e-mail address or name.
- **Refresh is server-side.** The Next.js proxy (Node runtime, before every
  request) renews the access token when it has less than a minute left and hands
  the new cookie to the browser and to the page being rendered. Refresh tokens are
  single use and a replay revokes the session (§5.5), so the web server exchanges
  each one at most once: concurrent requests share one refresh, and a request that
  still carries the old cookie within 30 s gets the same new tokens (kept in memory,
  keyed by the SHA-256 of the old token). The web app is one process, so this is
  complete.
- Member sessions last until the refresh token expires; the admin console uses a
  browser-session cookie and an absolute lifetime of 12 hours, after which the
  session is revoked at the API.
- Sign-out (`POST`, server action) revokes the session at the API and deletes the
  cookie. When the API answers `UNAUTHENTICATED`/`SESSION_REVOKED`/`ACCOUNT_DISABLED`,
  pages go to `/…/session/expired`, which removes the cookie only after the API
  confirms the session is gone (a cross-site link cannot sign anybody out).
- A temporary password sends the user to `/…/password` until it is changed; the
  API's answer to `POST /me/password` (new tokens) replaces the cookie.

### 12.2 Request security

- **CSRF:** `SameSite=Strict`, plus every non-`GET` request must carry `Origin`
  equal to the surface's own origin (and `Sec-Fetch-Site: same-origin` when sent);
  Next.js checks server actions' origin as well.
- **CSP** per response, with a fresh nonce: `script-src 'self' 'nonce-…' 'strict-dynamic'
  'wasm-unsafe-eval'` (pdf.js's image decoders are WebAssembly from this origin),
  `style-src 'self' 'nonce-…'`, `object-src 'none'`, `base-uri 'none'`,
  `frame-ancestors 'none'`; no `unsafe-inline` anywhere (no inline `style`
  attributes either — bars are SVG, sizes are set through the CSSOM). Also
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, COOP/CORP `same-origin`,
  a restrictive `Permissions-Policy`, `X-Robots-Tag: noindex` on both hosts. The
  edge adds HSTS.
- **Authorisation is checked three times:** the proxy (routing and session),
  every page, route handler and server action (`requireSession(surface)`: host,
  user type, pending password change), and the API (token, `cli = web`, service
  token for `/internal/*`). The API client accepts only paths of the contract shape
  and validates every answer with the contract schemas.
- No third-party scripts, fonts, CDNs or telemetry (`NEXT_TELEMETRY_DISABLED`).
  The font is the platform's, like the Android app (it carries the Urdu fallback
  chain); pdf.js's worker, WebAssembly, fonts and CMaps are copied next to the app
  at build time and served from `/app/pdfjs/`.
- Page titles are generic ("Document"), never a document's title, which would end
  up in the browser history and its cloud sync. Nothing is cached by the server:
  every API call is per request with the user's own token.

### 12.3 Member portal

- **Documents** (`/app`): the API's list (`GET /documents`, cursor pages of 100),
  keyword search (`q`, the blind index: whole words), sort by last change (the
  API's order, paged) or by title, scan date or size (titles are encrypted at rest,
  so the browser loads the whole list — up to 5 000 — and sorts it). Uploads still
  in progress (`PENDING_UPLOAD`) are hidden, like in the app.
- **Document** (`/app/documents/:id`): the PDF rendered in the browser by pdf.js
  (page thumbnails, zoom, arrow keys), the text tab (`GET /text`), rename, tags,
  delete (to the trash, after a confirmation). Downloads: the PDF (and the
  searchable copy when there is one); **a page as JPEG**, rendered in the browser
  from the PDF at 300 dpi (at most 4096 px) — the page as it was finished on the
  phone, cropped and filtered; **all pages as a ZIP** of those JPEGs; and the
  **original photo** of a page (`GET …/pages/:pageId/original`, byte for byte as
  the camera took it) when the phone backed it up. The current page is the PDF
  page: the app embeds each finished page as one JPEG, so rendering the PDF gives
  exactly what the user sees; originals are the unedited input and are offered as
  such, not as the page.
- Files stream from the API through the web server (`/app/documents/:id/file`,
  `…/pages/:pageId/original`) with `Cache-Control: private, no-store` and a
  `Content-Disposition` carrying the title (RFC 8187); nothing is buffered or
  written to disk.
- **Account** (`/app/account`): name, storage used against the quota, password
  change (signs out the other sessions), signed-in devices with sign-out per
  device.

### 12.4 Admin console

Everything goes through `/internal/admin/v1` with the service token and the
admin's `cli = web` token; nothing returns a member's document content.

- **Users:** list, create (the temporary password is shown once, with a copy
  button), edit name, type, status and storage quota (decimal GB or no limit),
  reset password (new temporary password shown once), sign out everywhere, rotate
  the user key, delete (crypto-shred; confirmed by typing the e-mail address). The
  console disables what the API refuses for one's own account.
- **Keys:** master key versions, ages, rotations due and rows pending re-wrap;
  user- and data-key counts; "Rotate now" for both user-type master keys and the
  signing key; the rotation history with progress, refreshed every 2 s while a run
  is pending or running.
- **Audit log:** newest first, filtered by event and/or actor (server-side,
  `?action=` and `?actorId=`); payloads are shown as key/value pairs.
- **Account:** the admin's own password and sessions.

### 12.5 Deployment

- `apps/web/Dockerfile`: the same first stage as the api and worker images (one
  workspace build per deploy), then the standalone server as `node` (uid 1000) on
  a read-only root filesystem; health at `GET /healthz` (container only).
- Compose service `web` (profile `app`): `edge_net` only, 512 MB, `WEB_API_URL`
  pinned to `http://api:3000`. Its configuration (`loadWebConfig()`):
  `WEB_SERVICE_TOKEN`, `WEB_COOKIE_SECRET` (generated by `bootstrap.sh`), and the
  origins, derived from `SCANOVA_TLS_MODE`, `SCANOVA_SITE_HOST`,
  `SCANOVA_ADMIN_HOST` and `SCANOVA_PUBLIC_URL` (`WEB_SITE_ORIGIN` /
  `WEB_ADMIN_ORIGIN` override them for development).
- Edge: on the site host `/app`, `/app/*` and `/_next/static/*` go to `web:3000`,
  everything else stays the static site; on the admin host `/`, `/admin*`,
  `/_next/static/*` and `/favicon.svg` go to `web:3000`, `robots.txt` disallows
  everything, the rest is a 404, and `SCANOVA_ADMIN_ALLOWED_IPS` (default: everyone)
  can limit the host to given networks. In internal and acme modes the portal
  paths are served on the one host. The web app's own CSP is kept; the edge only
  sets a default where a response has none.
- `infra/deploy/deploy.sh` builds `scanova/web:<release>` with the other images,
  waits for it to be healthy before switching, and prunes it with the release.

### 12.6 Not done yet

- MFA for admins (needs TOTP or WebAuthn support in the API first).
- Uploading or editing pages in the browser (the API supports page recipes and
  originals; the web app only reads them).
