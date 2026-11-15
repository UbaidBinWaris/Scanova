package app.scanova.core.data.export

/**
 * The recognised text of a document: per page when the pages are on this
 * phone, otherwise the text stored for the whole document (e.g. after a
 * restore from backup).
 */
data class DocumentText(
    /** Text of each page in order; empty when the document has no page images here. */
    val pages: List<String>,
    /** Text of the whole document. */
    val whole: String,
) {
    /** Nothing was recognised anywhere in the document. */
    val isEmpty: Boolean get() = sections().isEmpty()

    /**
     * The text in readable sections: one per page that has text (numbered),
     * or a single unnumbered section for a one-page document or when only the
     * whole-document text exists.
     */
    fun sections(): List<TextSection> {
        val perPage = pages.mapIndexedNotNull { index, text ->
            text.trim().takeIf { it.isNotEmpty() }?.let { TextSection(pageNumber = index + 1, text = it) }
        }
        return when {
            perPage.isNotEmpty() && pages.size == 1 -> listOf(perPage.single().copy(pageNumber = null))
            perPage.isNotEmpty() -> perPage
            else -> whole.trim().takeIf { it.isNotEmpty() }?.let { listOf(TextSection(pageNumber = null, text = it)) }
                .orEmpty()
        }
    }

    /**
     * Plain text for a `.txt` file, the clipboard or a share: sections
     * separated by blank lines, each page introduced by [pageHeading] (e.g.
     * "Page 2"). Empty when there is no text.
     */
    fun toPlainText(pageHeading: (pageNumber: Int) -> String): String {
        val sections = sections()
        if (sections.isEmpty()) return ""
        return sections.joinToString(separator = SECTION_SEPARATOR, postfix = "\n") { section ->
            section.pageNumber?.let { "${pageHeading(it)}\n\n${section.text}" } ?: section.text
        }
    }

    private companion object {
        const val SECTION_SEPARATOR = "\n\n\n"
    }
}

/** One readable block of a document's text; [pageNumber] (1-based) is null for unnumbered text. */
data class TextSection(val pageNumber: Int?, val text: String)
