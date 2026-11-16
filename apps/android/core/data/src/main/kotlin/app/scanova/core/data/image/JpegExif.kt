package app.scanova.core.data.image

/**
 * Reads the EXIF `Orientation` tag (0x0112) straight from JPEG bytes: the
 * APP1 "Exif" segment, its TIFF header (either byte order) and the first
 * image directory. Only this one tag is needed for an import, so a tiny
 * bounds-checked reader replaces a full EXIF library (and the framework
 * `ExifInterface`, which had parsing bugs on older Android versions).
 *
 * Anything missing, damaged or unexpected reads as [ExifOrientation.NORMAL].
 */
object JpegExif {

    fun orientation(jpeg: ByteArray): Int = try {
        findOrientation(jpeg) ?: ExifOrientation.NORMAL
    } catch (e: IndexOutOfBoundsException) {
        ExifOrientation.NORMAL
    }

    private fun findOrientation(bytes: ByteArray): Int? {
        if (bytes.size < 4 || u8(bytes, 0) != 0xFF || u8(bytes, 1) != 0xD8) return null
        var position = 2
        while (position + 4 <= bytes.size) {
            if (u8(bytes, position) != 0xFF) return null
            val marker = u8(bytes, position + 1)
            if (marker == 0xFF) {
                // A fill byte before the marker.
                position++
                continue
            }
            position += 2
            when {
                marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> continue // no payload
                marker == 0xDA || marker == 0xD9 -> return null // image data: metadata comes before it
            }
            val length = (u8(bytes, position) shl 8) or u8(bytes, position + 1)
            if (length < 2 || position + length > bytes.size) return null
            if (marker == APP1 && isExifHeader(bytes, position + 2, length - 2)) {
                return readTiff(bytes, start = position + 2 + EXIF_HEADER.size, end = position + length)
            }
            position += length
        }
        return null
    }

    private fun isExifHeader(bytes: ByteArray, start: Int, available: Int): Boolean =
        available >= EXIF_HEADER.size && EXIF_HEADER.indices.all { bytes[start + it] == EXIF_HEADER[it] }

    /** Finds the orientation in the first image directory of the TIFF structure in [start] until [end]. */
    private fun readTiff(bytes: ByteArray, start: Int, end: Int): Int? {
        if (start + TIFF_HEADER_SIZE > end) return null
        val littleEndian = when {
            bytes[start] == 'I'.code.toByte() && bytes[start + 1] == 'I'.code.toByte() -> true
            bytes[start] == 'M'.code.toByte() && bytes[start + 1] == 'M'.code.toByte() -> false
            else -> return null
        }

        fun u16(at: Int): Int = if (littleEndian) {
            u8(bytes, at) or (u8(bytes, at + 1) shl 8)
        } else {
            (u8(bytes, at) shl 8) or u8(bytes, at + 1)
        }

        fun u32(at: Int): Long = if (littleEndian) {
            u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        } else {
            (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }

        if (u16(start + 2) != TIFF_MAGIC) return null
        val directoryOffset = u32(start + 4)
        if (directoryOffset < TIFF_HEADER_SIZE || start + directoryOffset + 2 > end) return null
        val directory = start + directoryOffset.toInt()
        val entries = u16(directory)
        for (index in 0 until entries) {
            val entry = directory + 2 + index * ENTRY_SIZE
            if (entry + ENTRY_SIZE > end) return null
            if (u16(entry) == TAG_ORIENTATION) {
                if (u16(entry + 2) != TYPE_SHORT || u32(entry + 4) < 1) return null
                // A SHORT value sits in the first two bytes of the value field.
                return u16(entry + 8).takeIf { it in ExifOrientation.NORMAL..ExifOrientation.ROTATE_270 }
            }
        }
        return null
    }

    private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

    private const val APP1 = 0xE1
    private val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
    private const val TIFF_HEADER_SIZE = 8
    private const val TIFF_MAGIC = 42
    private const val ENTRY_SIZE = 12
    private const val TAG_ORIENTATION = 0x0112
    private const val TYPE_SHORT = 3
}
