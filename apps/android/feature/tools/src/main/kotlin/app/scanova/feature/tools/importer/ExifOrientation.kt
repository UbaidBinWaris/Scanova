package app.scanova.feature.tools.importer

/**
 * What a photo needs to be shown upright: mirror it horizontally first (when
 * [mirrored]), then turn it clockwise by [rotation] degrees.
 */
internal data class ExifTransform(val rotation: Int, val mirrored: Boolean) {
    val isIdentity: Boolean get() = rotation == 0 && !mirrored

    companion object {
        val NONE = ExifTransform(rotation = 0, mirrored = false)
    }
}

/**
 * Maps the EXIF `Orientation` tag (1–8) to an [ExifTransform]. Phones save a
 * photo as the sensor saw it and record here how to turn it, so an imported
 * photo is put upright through its edit recipe instead of rewriting its pixels.
 *
 * Same convention as AndroidX `ExifInterface.getRotationDegrees()` /
 * `isFlipped()`: transpose (5) is a mirror then 270°, transverse (7) a mirror
 * then 90°. Unknown or missing values mean "as stored".
 */
internal object ExifOrientation {
    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90 = 6
    const val TRANSVERSE = 7
    const val ROTATE_270 = 8

    fun transform(orientation: Int): ExifTransform = when (orientation) {
        FLIP_HORIZONTAL -> ExifTransform(rotation = 0, mirrored = true)
        ROTATE_180 -> ExifTransform(rotation = 180, mirrored = false)
        FLIP_VERTICAL -> ExifTransform(rotation = 180, mirrored = true)
        TRANSPOSE -> ExifTransform(rotation = 270, mirrored = true)
        ROTATE_90 -> ExifTransform(rotation = 90, mirrored = false)
        TRANSVERSE -> ExifTransform(rotation = 90, mirrored = true)
        ROTATE_270 -> ExifTransform(rotation = 270, mirrored = false)
        else -> ExifTransform.NONE
    }
}
