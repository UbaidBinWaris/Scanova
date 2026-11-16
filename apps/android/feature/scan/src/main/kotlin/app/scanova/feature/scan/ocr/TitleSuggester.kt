package app.scanova.feature.scan.ocr

import app.scanova.core.ml.insight.DocumentInsights

/**
 * Proposes a title for a scan from the text recognised on its first page:
 * the document's type with the detail that tells it apart and its date
 * ("Receipt – Imtiaz Store – 16 Nov 2026", "Bill – K-Electric – Oct 2026"),
 * or, for anything else, the first line that reads like a heading. The rules
 * live in `core:ml` ([DocumentInsights]); the user can always change the title.
 */
object TitleSuggester {
    /** Returns null when the text offers nothing usable; the caller then falls back to a dated name. */
    fun suggest(recognizedText: String): String? = DocumentInsights.suggestTitle(recognizedText)
}
