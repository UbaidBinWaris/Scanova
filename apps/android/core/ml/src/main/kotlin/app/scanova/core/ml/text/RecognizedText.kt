package app.scanova.core.ml.text

/**
 * What text recognition found on an image, reduced to what the app uses:
 * the lines in reading order with ML Kit's per-line angle and confidence.
 * Plain Kotlin, so the orientation logic runs in JVM unit tests.
 */
data class RecognizedText(val lines: List<RecognizedLine>) {
    /** All lines, one per row, in the order the recogniser returned them. */
    val text: String get() = lines.joinToString("\n") { it.text }

    companion object {
        val EMPTY = RecognizedText(emptyList())
    }
}

data class RecognizedLine(
    val text: String,
    /**
     * Rotation of the line in degrees, clockwise, as ML Kit reports it
     * (`Text.Line.getAngle`, -180..180). About 0 for text that reads upright
     * in the image as it was given to the recogniser.
     */
    val angle: Float,
    /** ML Kit's confidence for the line, 0..1; [Float.NaN] or 0 when the recogniser gave none. */
    val confidence: Float,
)
