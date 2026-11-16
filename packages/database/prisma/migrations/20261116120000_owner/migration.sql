-- Owners run the server: they may switch the AI runtime on and off from the app.
-- Granted only with the operator CLI (owner:grant). Nobody is an owner by default.

-- AlterTable
ALTER TABLE "users" ADD COLUMN     "is_owner" BOOLEAN NOT NULL DEFAULT false;
