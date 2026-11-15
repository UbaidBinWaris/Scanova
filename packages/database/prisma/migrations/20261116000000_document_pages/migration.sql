-- Document pages: per page, the untouched original photo (an encrypted object,
-- counted in the owner's storage) and the encrypted edit recipe that turns it
-- into the page in the PDF. The PDF stays the current version of the document.

-- AlterTable
ALTER TABLE "documents" ADD COLUMN     "pages_updated_at" TIMESTAMP(3);

-- CreateTable
CREATE TABLE "document_pages" (
    "id" SERIAL NOT NULL,
    "public_id" UUID NOT NULL,
    "document_id" INTEGER NOT NULL,
    "position" INTEGER NOT NULL,
    "width" INTEGER NOT NULL,
    "height" INTEGER NOT NULL,
    "edit_enc" BYTEA,
    "original_object_id" UUID,
    "original_object_key" TEXT,
    "original_size_bytes" BIGINT,
    "original_sha256" BYTEA,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "document_pages_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "document_pages_original_object_id_key" ON "document_pages"("original_object_id");

-- CreateIndex
CREATE UNIQUE INDEX "document_pages_original_object_key_key" ON "document_pages"("original_object_key");

-- CreateIndex
CREATE INDEX "document_pages_document_id_position_idx" ON "document_pages"("document_id", "position");

-- CreateIndex
CREATE UNIQUE INDEX "document_pages_document_id_public_id_key" ON "document_pages"("document_id", "public_id");

-- AddForeignKey
ALTER TABLE "document_pages" ADD CONSTRAINT "document_pages_document_id_fkey" FOREIGN KEY ("document_id") REFERENCES "documents"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Sizes and order are never negative, and an original is either described
-- completely (object id, bucket key, size, hash) or not at all.
ALTER TABLE "document_pages"
    ADD CONSTRAINT "document_pages_geometry_valid"
    CHECK ("position" >= 0 AND "width" > 0 AND "height" > 0);

ALTER TABLE "document_pages"
    ADD CONSTRAINT "document_pages_original_complete"
    CHECK (
        ("original_object_id" IS NULL) = ("original_object_key" IS NULL)
        AND ("original_object_id" IS NULL) = ("original_size_bytes" IS NULL)
        AND ("original_object_id" IS NULL) = ("original_sha256" IS NULL)
        AND ("original_size_bytes" IS NULL OR "original_size_bytes" >= 0)
    );
