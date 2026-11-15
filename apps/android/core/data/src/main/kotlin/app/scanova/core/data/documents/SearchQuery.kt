package app.scanova.core.data.documents

/**
 * Turns what a person typed in the search box into an FTS4 `MATCH` expression.
 *
 * User text is never passed to SQLite as-is: quotes, parentheses and words such
 * as `OR` or `NEAR` are query syntax there. Instead the input is reduced to
 * plain words — split exactly where the index's tokenizer splits, at anything
 * that is not a letter or digit — and each word becomes a lower-case prefix
 * term. Terms are separated by spaces, which FTS reads as "all of these".
 */
object SearchQuery {
    private const val MAX_TERMS = 12

    /** Returns null when the input contains nothing searchable (show everything). */
    fun toFtsMatch(input: String): String? {
        val terms = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                terms += current.toString().lowercase()
                current.setLength(0)
            }
        }
        var index = 0
        while (index < input.length) {
            val codePoint = input.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) current.appendCodePoint(codePoint) else flush()
            index += Character.charCount(codePoint)
        }
        flush()
        if (terms.isEmpty()) return null
        return terms.take(MAX_TERMS).joinToString(" ") { "$it*" }
    }
}
