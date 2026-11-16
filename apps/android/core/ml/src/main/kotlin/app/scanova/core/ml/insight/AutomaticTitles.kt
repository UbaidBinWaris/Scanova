package app.scanova.core.ml.insight

/**
 * Recognises the titles the app gives documents by itself — "Scan
 * 16 Nov 2026, 10:05", "Imported 16 Nov 2026", "Merged 16 Nov 2026", a bare
 * "Scan", and split parts of those ("… (pages 1–3)") — so a better title is
 * only ever put in place of one of them, never in place of a title someone
 * typed ("Scan of passport" is the user's).
 *
 * The date part is accepted in any of the phone's formats: digits and
 * separators, English month names, AM/PM, and words in other scripts.
 */
object AutomaticTitles {
    private val PREFIXES = setOf("scan", "imported", "merged")
    private val PART_SUFFIX = Regex("\\s*\\((?:page|pages)\\s+[0-9][0-9\\s,–\\-]*\\)\\s*$", RegexOption.IGNORE_CASE)
    private val SEPARATORS = Regex("[\\s\\u00A0\\u202F,]+")
    private val NUMERIC = Regex("^[\\p{Nd}:./\\-]+$")
    private val MONTHS = setOf(
        "jan", "january", "feb", "february", "mar", "march", "apr", "april", "may", "jun", "june", "jul", "july",
        "aug", "august", "sep", "sept", "september", "oct", "october", "nov", "november", "dec", "december",
    )
    private val TIME_WORDS = setOf("am", "pm", "a.m.", "p.m.", "a.m", "p.m", "at")

    fun isAutomatic(title: String): Boolean {
        val base = stripParts(title.trim())
        val words = base.split(SEPARATORS).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.first().lowercase() !in PREFIXES) return false
        val rest = words.drop(1)
        if (rest.isEmpty()) return words.first().lowercase() == "scan"
        return rest.any { word -> word.any(Char::isDigit) } && rest.all(::isDateWord)
    }

    /** [suggestion] in place of the automatic [current] title, keeping a split part's "(pages …)". */
    fun retitle(current: String, suggestion: String): String {
        val part = PART_SUFFIX.find(current.trim())?.value?.trim()
        return if (part == null) suggestion else "$suggestion $part"
    }

    private fun stripParts(title: String): String {
        var base = title
        while (true) {
            val stripped = base.replace(PART_SUFFIX, "")
            if (stripped == base) return base.trim()
            base = stripped
        }
    }

    private fun isDateWord(word: String): Boolean {
        val lower = word.lowercase().trimEnd('.')
        return NUMERIC.matches(word) ||
            lower in MONTHS ||
            word.lowercase() in TIME_WORDS ||
            // Month names and digits of other scripts (an Urdu or Arabic date).
            word.none { it in 'a'..'z' || it in 'A'..'Z' }
    }
}
