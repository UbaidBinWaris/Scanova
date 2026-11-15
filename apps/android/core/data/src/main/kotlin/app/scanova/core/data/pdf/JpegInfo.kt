package app.scanova.core.data.pdf

/** What a PDF needs to know about a JPEG to embed it without re-encoding. */
data class JpegInfo(
    val width: Int,
    val height: Int,
    /** 1 = greyscale, 3 = colour (YCbCr), 4 = CMYK. */
    val components: Int,
) {
    companion object {
        /**
         * Reads the frame header of a JPEG. Returns null when [bytes] is not a
         * baseline or progressive JPEG this writer can embed.
         */
        fun read(bytes: ByteArray): JpegInfo? {
            if (bytes.size < 4 || u(bytes, 0) != 0xFF || u(bytes, 1) != 0xD8) return null
            var position = 2
            while (position + 3 < bytes.size) {
                if (u(bytes, position) != 0xFF) return null
                var marker = u(bytes, position + 1)
                // Fill bytes: any number of 0xFF may precede a marker.
                while (marker == 0xFF && position + 2 < bytes.size) {
                    position++
                    marker = u(bytes, position + 1)
                }
                position += 2
                when {
                    marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> continue // no payload
                    marker == 0xD9 || marker == 0xDA -> return null // image data before a frame header
                }
                if (position + 1 >= bytes.size) return null
                val length = (u(bytes, position) shl 8) or u(bytes, position + 1)
                if (length < 2 || position + length > bytes.size) return null
                if (isFrameHeader(marker)) {
                    if (length < 8) return null
                    val height = (u(bytes, position + 3) shl 8) or u(bytes, position + 4)
                    val width = (u(bytes, position + 5) shl 8) or u(bytes, position + 6)
                    val components = u(bytes, position + 7)
                    if (width <= 0 || height <= 0 || components !in setOf(1, 3, 4)) return null
                    return JpegInfo(width, height, components)
                }
                position += length
            }
            return null
        }

        /** SOF0–SOF15 except DHT (C4), JPG (C8) and DAC (CC), which share the range. */
        private fun isFrameHeader(marker: Int): Boolean =
            marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

        private fun u(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF
    }
}
