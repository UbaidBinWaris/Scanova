package app.scanova.feature.scan.ocr

/**
 * Proposes a title for a scan from the text recognised on its first page:
 * the first line that reads like a heading. The user can always change it.
 */
object TitleSuggester {
    private const val MAX_LENGTH = 60
    private const val MIN_LETTERS = 3
    private const val MAX_LINES_CONSIDERED = 12

    /** Returns null when the text offers nothing usable; the caller then falls back to a dated name. */
    fun suggest(recognizedText: String): String? {
        val candidate = recognizedText.lineSequence()
            .map { line -> line.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_LINES_CONSIDERED)
            .firstOrNull(::looksLikeTitle)
            ?: return null
        return shorten(candidate.trim(' ', '-', '–', '—', ':', '.', ',', ';', '|', '_', '*', '#'))
            .takeIf { it.count(Char::isLetter) >= MIN_LETTERS }
    }

    private fun looksLikeTitle(line: String): Boolean {
        val letters = line.count(Char::isLetter)
        if (letters < MIN_LETTERS) return false
        // Lines that are mostly digits and symbols are dates, amounts, phone
        // numbers or reference codes, not headings.
        val meaningful = line.count { !it.isWhitespace() }
        return letters * 2 >= meaningful
    }

    private fun shorten(text: String): String {
        if (text.length <= MAX_LENGTH) return text
        val cut = text.substring(0, MAX_LENGTH)
        val lastSpace = cut.lastIndexOf(' ')
        // Break at a word boundary unless that would throw most of the title away.
        return (if (lastSpace >= MAX_LENGTH / 2) cut.substring(0, lastSpace) else cut).trim()
    }
}
