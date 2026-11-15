package app.scanova.core.common.util

private const val HEX_DIGITS = "0123456789abcdef"

/** Lower-case hexadecimal encoding. */
fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0f])
    }
    return out.toString()
}

/**
 * Decodes hexadecimal text, ignoring the separators people type or paste in
 * fingerprints (spaces, colons, dashes). Returns null when the rest is not valid hex.
 */
fun String.hexToBytesOrNull(): ByteArray? {
    val cleaned = filterNot { it == ':' || it == '-' || it.isWhitespace() }.lowercase()
    if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
    val out = ByteArray(cleaned.length / 2)
    for (i in out.indices) {
        val high = HEX_DIGITS.indexOf(cleaned[i * 2])
        val low = HEX_DIGITS.indexOf(cleaned[i * 2 + 1])
        if (high < 0 || low < 0) return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}
