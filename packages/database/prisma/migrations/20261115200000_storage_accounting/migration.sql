-- Storage accounting: every stored variant (original and searchable PDF) counts
-- towards a user's usage, and usage can never go negative.

-- Recompute usage from the files that actually exist (repairs any drift).
UPDATE "users" AS u
SET "storage_used_bytes" = COALESCE((
    SELECT SUM(f."size_bytes")
    FROM "file_objects" AS f
    JOIN "documents" AS d ON d."id" = f."document_id"
    WHERE d."user_id" = u."id"
), 0);

ALTER TABLE "users"
    ADD CONSTRAINT "users_storage_used_bytes_nonnegative" CHECK ("storage_used_bytes" >= 0);
