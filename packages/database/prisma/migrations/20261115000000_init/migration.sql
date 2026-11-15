-- CreateEnum
CREATE TYPE "user_type" AS ENUM ('ADMIN', 'MEMBER');

-- CreateEnum
CREATE TYPE "user_status" AS ENUM ('ACTIVE', 'DISABLED');

-- CreateEnum
CREATE TYPE "session_client" AS ENUM ('MOBILE', 'WEB');

-- CreateEnum
CREATE TYPE "document_status" AS ENUM ('PENDING_UPLOAD', 'READY');

-- CreateEnum
CREATE TYPE "ocr_status" AS ENUM ('NONE', 'PENDING', 'DONE', 'FAILED');

-- CreateEnum
CREATE TYPE "text_source" AS ENUM ('CLIENT', 'SERVER');

-- CreateEnum
CREATE TYPE "file_variant" AS ENUM ('ORIGINAL', 'SEARCHABLE');

-- CreateTable
CREATE TABLE "users" (
    "id" SERIAL NOT NULL,
    "public_id" UUID NOT NULL,
    "email_index" BYTEA NOT NULL,
    "email_enc" BYTEA NOT NULL,
    "name_enc" BYTEA NOT NULL,
    "user_type" "user_type" NOT NULL,
    "status" "user_status" NOT NULL DEFAULT 'ACTIVE',
    "password_hash" TEXT NOT NULL,
    "must_change_password" BOOLEAN NOT NULL DEFAULT false,
    "storage_quota_bytes" BIGINT,
    "storage_used_bytes" BIGINT NOT NULL DEFAULT 0,
    "deleted_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "users_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "sessions" (
    "id" SERIAL NOT NULL,
    "public_id" UUID NOT NULL,
    "user_id" INTEGER NOT NULL,
    "client" "session_client" NOT NULL,
    "refresh_token_hash" BYTEA NOT NULL,
    "refresh_expires_at" TIMESTAMP(3) NOT NULL,
    "device_name_enc" BYTEA NOT NULL,
    "device_model_enc" BYTEA NOT NULL,
    "platform" TEXT NOT NULL,
    "app_version" TEXT,
    "revoked_at" TIMESTAMP(3),
    "last_used_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "sessions_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "spent_refresh_tokens" (
    "id" SERIAL NOT NULL,
    "session_id" INTEGER NOT NULL,
    "token_hash" BYTEA NOT NULL,
    "spent_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "spent_refresh_tokens_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "documents" (
    "id" SERIAL NOT NULL,
    "public_id" UUID NOT NULL,
    "user_id" INTEGER NOT NULL,
    "title_enc" BYTEA NOT NULL,
    "tags_enc" BYTEA NOT NULL,
    "page_count" INTEGER NOT NULL,
    "status" "document_status" NOT NULL DEFAULT 'PENDING_UPLOAD',
    "ocr_status" "ocr_status" NOT NULL DEFAULT 'NONE',
    "captured_at" TIMESTAMP(3) NOT NULL,
    "deleted_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "documents_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "document_texts" (
    "id" SERIAL NOT NULL,
    "document_id" INTEGER NOT NULL,
    "text_enc" BYTEA NOT NULL,
    "source" "text_source" NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "document_texts_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "file_objects" (
    "id" SERIAL NOT NULL,
    "public_id" UUID NOT NULL,
    "document_id" INTEGER NOT NULL,
    "variant" "file_variant" NOT NULL,
    "object_key" TEXT NOT NULL,
    "size_bytes" BIGINT NOT NULL,
    "sha256" BYTEA NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "file_objects_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "search_tokens" (
    "id" SERIAL NOT NULL,
    "document_id" INTEGER NOT NULL,
    "token" BYTEA NOT NULL,

    CONSTRAINT "search_tokens_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "audit_events" (
    "id" SERIAL NOT NULL,
    "public_id" UUID NOT NULL,
    "actor_id" INTEGER,
    "action" TEXT NOT NULL,
    "payload_enc" BYTEA NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "audit_events_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "users_public_id_key" ON "users"("public_id");

-- CreateIndex
CREATE UNIQUE INDEX "users_email_index_key" ON "users"("email_index");

-- CreateIndex
CREATE INDEX "users_created_at_public_id_idx" ON "users"("created_at", "public_id");

-- CreateIndex
CREATE INDEX "users_user_type_status_idx" ON "users"("user_type", "status");

-- CreateIndex
CREATE INDEX "users_deleted_at_idx" ON "users"("deleted_at");

-- CreateIndex
CREATE UNIQUE INDEX "sessions_public_id_key" ON "sessions"("public_id");

-- CreateIndex
CREATE UNIQUE INDEX "sessions_refresh_token_hash_key" ON "sessions"("refresh_token_hash");

-- CreateIndex
CREATE INDEX "sessions_user_id_revoked_at_idx" ON "sessions"("user_id", "revoked_at");

-- CreateIndex
CREATE INDEX "sessions_refresh_expires_at_idx" ON "sessions"("refresh_expires_at");

-- CreateIndex
CREATE UNIQUE INDEX "spent_refresh_tokens_token_hash_key" ON "spent_refresh_tokens"("token_hash");

-- CreateIndex
CREATE INDEX "spent_refresh_tokens_session_id_idx" ON "spent_refresh_tokens"("session_id");

-- CreateIndex
CREATE INDEX "spent_refresh_tokens_spent_at_idx" ON "spent_refresh_tokens"("spent_at");

-- CreateIndex
CREATE UNIQUE INDEX "documents_public_id_key" ON "documents"("public_id");

-- CreateIndex
CREATE INDEX "documents_user_id_updated_at_public_id_idx" ON "documents"("user_id", "updated_at", "public_id");

-- CreateIndex
CREATE INDEX "documents_user_id_deleted_at_idx" ON "documents"("user_id", "deleted_at");

-- CreateIndex
CREATE INDEX "documents_deleted_at_idx" ON "documents"("deleted_at");

-- CreateIndex
CREATE UNIQUE INDEX "document_texts_document_id_key" ON "document_texts"("document_id");

-- CreateIndex
CREATE UNIQUE INDEX "file_objects_public_id_key" ON "file_objects"("public_id");

-- CreateIndex
CREATE UNIQUE INDEX "file_objects_object_key_key" ON "file_objects"("object_key");

-- CreateIndex
CREATE UNIQUE INDEX "file_objects_document_id_variant_key" ON "file_objects"("document_id", "variant");

-- CreateIndex
CREATE INDEX "search_tokens_token_document_id_idx" ON "search_tokens"("token", "document_id");

-- CreateIndex
CREATE UNIQUE INDEX "search_tokens_document_id_token_key" ON "search_tokens"("document_id", "token");

-- CreateIndex
CREATE UNIQUE INDEX "audit_events_public_id_key" ON "audit_events"("public_id");

-- CreateIndex
CREATE INDEX "audit_events_created_at_public_id_idx" ON "audit_events"("created_at", "public_id");

-- CreateIndex
CREATE INDEX "audit_events_actor_id_created_at_idx" ON "audit_events"("actor_id", "created_at");

-- CreateIndex
CREATE INDEX "audit_events_action_created_at_idx" ON "audit_events"("action", "created_at");

-- AddForeignKey
ALTER TABLE "sessions" ADD CONSTRAINT "sessions_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "spent_refresh_tokens" ADD CONSTRAINT "spent_refresh_tokens_session_id_fkey" FOREIGN KEY ("session_id") REFERENCES "sessions"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "documents" ADD CONSTRAINT "documents_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "document_texts" ADD CONSTRAINT "document_texts_document_id_fkey" FOREIGN KEY ("document_id") REFERENCES "documents"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "file_objects" ADD CONSTRAINT "file_objects_document_id_fkey" FOREIGN KEY ("document_id") REFERENCES "documents"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "search_tokens" ADD CONSTRAINT "search_tokens_document_id_fkey" FOREIGN KEY ("document_id") REFERENCES "documents"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "audit_events" ADD CONSTRAINT "audit_events_actor_id_fkey" FOREIGN KEY ("actor_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;
