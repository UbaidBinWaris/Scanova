package app.scanova.feature.scan.filter

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Tone curves as 256-entry lookup tables (input level → output level), used
 * by the OpenCV filters. Pure Kotlin, unit-tested.
 */
internal object ToneCurves {

    /** Brightens ([gamma] below 1) or darkens (above 1) the mid-tones; black and white stay put. */
    fun gamma(gamma: Double): IntArray = IntArray(LEVELS) { level ->
        (MAX * (level / MAX).pow(gamma)).roundToInt().coerceIn(0, 255)
    }

    /**
     * Stretches [black]..[white] to the full range (levels outside are clipped)
     * and bends it by [gamma]: how a photo editor's "levels" works.
     */
    fun levels(black: Int, white: Int, gamma: Double = 1.0): IntArray {
        require(white > black) { "white point must be above black point" }
        return IntArray(LEVELS) { level ->
            val position = ((level - black).toDouble() / (white - black)).coerceIn(0.0, 1.0)
            (MAX * position.pow(gamma)).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * Ink saver: paper (at or above [paper]) becomes pure white, and ink is
     * lightened so the darkest black prints at [inkDarkness] of full toner.
     */
    fun inkSaver(paper: Int = 228, inkDarkness: Double = 0.55): IntArray = IntArray(LEVELS) { level ->
        if (level >= paper) {
            255
        } else {
            val darkness = 255 - level
            (255 - darkness * inkDarkness).roundToInt().coerceIn(0, 255)
        }
    }

    fun toBytes(curve: IntArray): ByteArray = ByteArray(curve.size) { curve[it].toByte() }

    private const val LEVELS = 256
    private const val MAX = 255.0
}
