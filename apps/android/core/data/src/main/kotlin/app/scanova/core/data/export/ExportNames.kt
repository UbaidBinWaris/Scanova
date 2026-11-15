package app.scanova.core.data.export

/**
 * File names for exported documents: the title without characters other
 * apps or file systems reject, plus what the file holds. Names are not
 * translated (they travel to other people and apps), so they use digits and
 * the title only, apart from "page" / "part", which keep them understandable.
 */
object ExportNames {
    private const val MAX_BASE_LENGTH = 80
    private const val FALLBACK = "Scanova document"

    /** The title as a file name without extension (same rules as the viewer's "Save a copy"). */
    fun baseName(title: String): String {
        val cleaned = title
            .map { if (it.isLetterOrDigit() || it in " -_().") it else ' ' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(MAX_BASE_LENGTH)
            .trim()
        return cleaned.ifEmpty { FALLBACK }
    }

    fun pdf(title: String): String = "${baseName(title)}.pdf"

    fun text(title: String): String = "${baseName(title)}.txt"

    /**
     * Page [pageNumber] (1-based) of [pageCount] as an image. The number is
     * zero-padded to the width of [pageCount] (at least two digits), so the
     * files sort in page order everywhere.
     */
    fun page(title: String, pageNumber: Int, pageCount: Int): String {
        val digits = maxOf(2, pageCount.toString().length)
        return "${baseName(title)} - page ${pageNumber.toString().padStart(digits, '0')}.jpg"
    }

    /** A long image; [part] (1-based) is only added when the document needed several. */
    fun longImage(title: String, part: Int, parts: Int): String =
        if (parts <= 1) "${baseName(title)}.jpg" else "${baseName(title)} - part $part.jpg"

    /**
     * Hands out names that are unique within one export (case-insensitively,
     * as most file systems compare them): a repeated name gets " (2)", " (3)",
     * … before its extension. Used when several documents share a title.
     */
    class Unique {
        private val taken = mutableSetOf<String>()

        fun next(name: String): String {
            var candidate = name
            var counter = 2
            while (!taken.add(candidate.lowercase())) {
                candidate = withSuffix(name, " ($counter)")
                counter++
            }
            return candidate
        }

        private fun withSuffix(name: String, suffix: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) + suffix + name.substring(dot) else name + suffix
        }
    }
}
