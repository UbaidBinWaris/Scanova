package app.scanova.core.ml.insight

/** What the phone makes of a document's text. */
data class DocumentInsight(
    val type: DocumentType,
    /** A title built from the text; null when it offers nothing usable. */
    val title: String?,
)

/**
 * The document "intelligence" that runs on the phone without a model: type
 * ([DocumentClassifier]) and title ([TitleBuilder]) from recognised text.
 * Pure Kotlin and fast (a few milliseconds for a page), so it can run on any
 * thread; measured by `DocumentInsightsEvaluationTest`.
 */
object DocumentInsights {
    fun analyze(text: String): DocumentInsight {
        if (text.isBlank()) return DocumentInsight(DocumentType.OTHER, null)
        // The start of a document says what it is; a long one is not read to the end.
        val start = text.take(MAX_CHARS)
        val type = DocumentClassifier.classify(start).type
        val title = TitleBuilder.build(type, start) ?: TitleBuilder.build(DocumentType.OTHER, start)
        return DocumentInsight(type, title)
    }

    private const val MAX_CHARS = 8000

    /** A title for a document whose (first page's) text is [text], or null. */
    fun suggestTitle(text: String): String? = analyze(text).title
}
