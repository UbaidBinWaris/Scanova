package app.scanova.core.data.util

/**
 * Cuts [text] so that its UTF-8 encoding is at most [maxBytes] long, without
 * splitting a character (or a surrogate pair) in the middle.
 */
fun truncateUtf8(text: String, maxBytes: Int): String {
    require(maxBytes >= 0)
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + size > maxBytes) break
        bytes += size
        index += Character.charCount(codePoint)
    }
    return if (index == text.length) text else text.substring(0, index)
}

/** Splits free text typed by a person into clean tags: trimmed, non-empty, unique, in order. */
fun normalizeTags(tags: List<String>): List<String> =
    tags.map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
