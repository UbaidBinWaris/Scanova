package app.scanova.core.data.indexing

import app.scanova.core.data.documents.decodeTags
import app.scanova.core.data.documents.encodeTags
import app.scanova.core.ml.insight.AutomaticTitles
import app.scanova.core.ml.insight.DocumentInsight
import app.scanova.core.ml.insight.DocumentType
import app.scanova.core.database.entity.DocumentEntity
import app.scanova.core.database.entity.PageEntity

/**
 * The decisions of the background indexer, as pure functions so they are
 * tested on their own: how a document's text follows its pages, and what an
 * analysis may change about a document (and what it must never touch).
 */
internal object IndexRules {
    /**
     * Version of the rules that analyse documents. Bumping it analyses every
     * document once more, but only titles that are still automatic can change
     * then; type tags are added on a document's first analysis only, so a tag
     * the user removed never comes back.
     */
    const val ANALYSIS_VERSION = 1

    /** Same limits as for tags a person sets (DocumentRepository). */
    private const val MAX_TAGS = 20
    private const val MAX_TITLE_LENGTH = 200

    /** A document's text as the app builds it from its pages (ScanRepository, DocumentEditor). */
    fun joinedText(pages: List<PageEntity>): String = pages.joinToString("\n\n") { it.ocrText }.trim()

    /**
     * The document has text although none of its pages has: it came from
     * elsewhere — the server's OCR of a restored document, which is the
     * authoritative text and also reads Urdu. Its pages are then left without
     * text of their own, so Extract Text keeps showing that text and search
     * keeps using it.
     */
    fun hasTextFromElsewhere(document: DocumentEntity, pages: List<PageEntity>): Boolean =
        document.ocrText.isNotBlank() && joinedText(pages).isEmpty()

    /**
     * The document after one of its pages got text. Its text follows its
     * pages when it was built from them (or is empty); text from elsewhere —
     * the server's OCR of a restored document, which also reads Urdu — is kept.
     */
    fun withPageText(document: DocumentEntity, before: List<PageEntity>, after: List<PageEntity>): DocumentEntity {
        val old = joinedText(before)
        val new = joinedText(after)
        if (new == old) return document
        return if (document.ocrText.isBlank() || document.ocrText == old) document.copy(ocrText = new) else document
    }

    /**
     * Applies [insight] to [document]:
     * - the type becomes a tag on the first analysis, unless the document
     *   already has a type tag (set by a person or another phone) or is full;
     *   the user's tags are kept as they are;
     * - the title is replaced only while it is still one the app gave
     *   ("Scan …", "Imported …", "Merged …"), never one a person typed;
     * - a change is a metadata edit like any other: it is sent to the server
     *   with the next sync.
     */
    fun analysed(document: DocumentEntity, insight: DocumentInsight, nowMillis: Long): DocumentEntity {
        val tags = decodeTags(document.tags)
        val typeTag = insight.type.tag
        val newTags = if (
            document.analysisVersion == 0 && typeTag != null && DocumentType.fromTags(tags) == null && tags.size < MAX_TAGS &&
            tags.none { it.equals(typeTag, ignoreCase = true) }
        ) {
            tags + typeTag
        } else {
            tags
        }
        val suggestion = insight.title
        val newTitle = if (suggestion != null && AutomaticTitles.isAutomatic(document.title)) {
            AutomaticTitles.retitle(document.title, suggestion).take(MAX_TITLE_LENGTH)
        } else {
            document.title
        }
        val changed = newTags != tags || newTitle != document.title
        return document.copy(
            title = newTitle,
            tags = encodeTags(newTags),
            analysisVersion = ANALYSIS_VERSION,
            updatedAt = if (changed) nowMillis else document.updatedAt,
            // Before the document exists on the server the create request carries the latest values.
            metadataDirty = document.metadataDirty || (changed && document.remoteCreated),
        )
    }
}
