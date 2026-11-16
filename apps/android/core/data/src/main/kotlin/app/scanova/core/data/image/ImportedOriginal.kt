package app.scanova.core.data.image

import app.scanova.core.data.pdf.JpegInfo

/**
 * How the original of an imported picture is kept. Pages are never edited
 * destructively (docs/ARCHITECTURE.md §9): the original is the file the user
 * picked whenever that is possible, and the page's recipe puts it upright.
 */
sealed interface ImportedOriginal {
    /**
     * A JPEG kept byte for byte. [sourceRotation] (clockwise degrees, from its
     * EXIF orientation) goes into the page's recipe instead of rewriting pixels.
     */
    data class KeepBytes(val sourceRotation: Int) : ImportedOriginal

    /**
     * Re-encoded upright as a JPEG: any other format (PNG, WebP, HEIF, …), a
     * JPEG the PDF writer cannot read, or a mirrored JPEG (recipes turn pages,
     * they never mirror them), with [transform] baked into the pixels.
     */
    data class Reencode(val transform: ExifTransform) : ImportedOriginal

    companion object {
        /** JPEG quality of a re-encoded original: visually lossless, about a third of a PNG. */
        const val REENCODE_QUALITY = 95

        /** Decides for the picked file [bytes]. */
        fun of(bytes: ByteArray): ImportedOriginal {
            val jpeg = JpegInfo.read(bytes) != null
            return of(isJpeg = jpeg, exifOrientation = if (jpeg) JpegExif.orientation(bytes) else ExifOrientation.NORMAL)
        }

        /**
         * Decides from what is known about the file: whether it is a JPEG this
         * app can embed and, for a JPEG, its EXIF orientation. Other formats are
         * decoded upright by the platform, so they need no transform here.
         */
        fun of(isJpeg: Boolean, exifOrientation: Int): ImportedOriginal {
            if (!isJpeg) return Reencode(ExifTransform.NONE)
            val transform = ExifOrientation.transform(exifOrientation)
            return if (transform.mirrored) Reencode(transform) else KeepBytes(transform.rotation)
        }
    }
}
