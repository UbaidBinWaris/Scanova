-- CreateEnum
CREATE TYPE "user_key_state" AS ENUM ('ACTIVE', 'RETIRED', 'DESTROYED');

-- CreateEnum
CREATE TYPE "data_key_scope" AS ENUM ('PROFILE', 'INDEX', 'DOCUMENT');

-- CreateEnum
CREATE TYPE "system_key_purpose" AS ENUM ('EMAIL_INDEX', 'AUDIT');

-- CreateEnum
CREATE TYPE "system_key_state" AS ENUM ('ACTIVE', 'RETIRED');

-- CreateEnum
CREATE TYPE "rotation_scope" AS ENUM ('USER_TYPE_MASTER', 'USER_KEY', 'JWT_SIGNING');

-- CreateEnum
CREATE TYPE "rotation_state" AS ENUM ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED');

-- CreateEnum
CREATE TYPE "rotation_trigger" AS ENUM ('SCHEDULED', 'MANUAL', 'PASSWORD_RESET', 'USER_TYPE_CHANGE');

-- CreateTable
CREATE TABLE "user_keys" (
    "id" SERIAL NOT NULL,
    "user_id" UUID NOT NULL,
    "version" INTEGER NOT NULL,
    "wrapped_key" TEXT,
    "master_key_name" TEXT NOT NULL,
    "master_key_version" INTEGER NOT NULL,
    "state" "user_key_state" NOT NULL DEFAULT 'ACTIVE',
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "retired_at" TIMESTAMP(3),
    "destroyed_at" TIMESTAMP(3),

    CONSTRAINT "user_keys_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "data_keys" (
    "id" SERIAL NOT NULL,
    "scope" "data_key_scope" NOT NULL,
    "user_id" UUID NOT NULL,
    "ref_id" UUID NOT NULL,
    "wrapped_key" BYTEA NOT NULL,
    "user_key_version" INTEGER NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "claimed_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "data_keys_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "system_keys" (
    "id" SERIAL NOT NULL,
    "purpose" "system_key_purpose" NOT NULL,
    "version" INTEGER NOT NULL,
    "wrapped_key" TEXT NOT NULL,
    "master_key_name" TEXT NOT NULL,
    "master_key_version" INTEGER NOT NULL,
    "state" "system_key_state" NOT NULL DEFAULT 'ACTIVE',
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "system_keys_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "key_rotation_runs" (
    "id" SERIAL NOT NULL,
    "public_id" UUID NOT NULL,
    "scope" "rotation_scope" NOT NULL,
    "target" TEXT NOT NULL,
    "state" "rotation_state" NOT NULL DEFAULT 'PENDING',
    "trigger" "rotation_trigger" NOT NULL,
    "from_version" INTEGER,
    "to_version" INTEGER,
    "total_items" INTEGER NOT NULL DEFAULT 0,
    "processed_items" INTEGER NOT NULL DEFAULT 0,
    "error_code" TEXT,
    "requested_by" UUID,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "started_at" TIMESTAMP(3),
    "finished_at" TIMESTAMP(3),

    CONSTRAINT "key_rotation_runs_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "user_keys_user_id_state_idx" ON "user_keys"("user_id", "state");

-- CreateIndex
CREATE INDEX "user_keys_master_key_name_master_key_version_state_idx" ON "user_keys"("master_key_name", "master_key_version", "state");

-- CreateIndex
CREATE INDEX "user_keys_state_created_at_idx" ON "user_keys"("state", "created_at");

-- CreateIndex
CREATE UNIQUE INDEX "user_keys_user_id_version_key" ON "user_keys"("user_id", "version");

-- CreateIndex
CREATE INDEX "data_keys_user_id_user_key_version_idx" ON "data_keys"("user_id", "user_key_version");

-- CreateIndex
CREATE INDEX "data_keys_scope_claimed_at_idx" ON "data_keys"("scope", "claimed_at");

-- CreateIndex
CREATE UNIQUE INDEX "data_keys_scope_user_id_ref_id_key" ON "data_keys"("scope", "user_id", "ref_id");

-- CreateIndex
CREATE INDEX "system_keys_purpose_state_idx" ON "system_keys"("purpose", "state");

-- CreateIndex
CREATE UNIQUE INDEX "system_keys_purpose_version_key" ON "system_keys"("purpose", "version");

-- CreateIndex
CREATE UNIQUE INDEX "key_rotation_runs_public_id_key" ON "key_rotation_runs"("public_id");

-- CreateIndex
CREATE INDEX "key_rotation_runs_scope_target_state_idx" ON "key_rotation_runs"("scope", "target", "state");

-- CreateIndex
CREATE INDEX "key_rotation_runs_state_created_at_idx" ON "key_rotation_runs"("state", "created_at");

-- CreateIndex
CREATE INDEX "key_rotation_runs_created_at_public_id_idx" ON "key_rotation_runs"("created_at", "public_id");
