package app.scanova.feature.scan.filter

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 4×5 colour matrices in Android's layout (row-major; the fifth column is an
 * offset in 0..255), usable both by `android.graphics.ColorMatrix` (final
 * render) and Compose's `ColorMatrix` (live preview), so both draw the same.
 *
 * Pure Kotlin, unit-tested.
 */
internal object ColorMatrices {
    val IDENTITY: FloatArray
        get() = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

    /** Scales colour values around mid-grey: 1 leaves them, above 1 adds contrast. */
    fun contrast(factor: Float): FloatArray {
        val shift = MID_GREY * (1f - factor)
        return floatArrayOf(
            factor, 0f, 0f, 0f, shift,
            0f, factor, 0f, 0f, shift,
            0f, 0f, factor, 0f, shift,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Adds [offset] (in 0..255 units) to every colour channel. */
    fun brightness(offset: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, offset,
        0f, 1f, 0f, 0f, offset,
        0f, 0f, 1f, 0f, offset,
        0f, 0f, 0f, 1f, 0f,
    )

    /** 0 is grey, 1 leaves colours, above 1 makes them stronger (Rec. 709 luminance, like Android's). */
    fun saturation(amount: Float): FloatArray {
        val inverse = 1f - amount
        val r = LUMA_R * inverse
        val g = LUMA_G * inverse
        val b = LUMA_B * inverse
        return floatArrayOf(
            r + amount, g, b, 0f, 0f,
            r, g + amount, b, 0f, 0f,
            r, g, b + amount, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Multiplies each channel and then shifts it. */
    fun channels(red: Float, green: Float, blue: Float, redShift: Float = 0f, greenShift: Float = 0f, blueShift: Float = 0f) =
        floatArrayOf(
            red, 0f, 0f, 0f, redShift,
            0f, green, 0f, 0f, greenShift,
            0f, 0f, blue, 0f, blueShift,
            0f, 0f, 0f, 1f, 0f,
        )

    /** Classic sepia toning. */
    val SEPIA: FloatArray
        get() = floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

    /** The matrix that applies [first], then [second]. */
    fun then(first: FloatArray, second: FloatArray): FloatArray {
        require(first.size == SIZE && second.size == SIZE) { "colour matrices have 20 entries" }
        val result = FloatArray(SIZE)
        for (row in 0 until 4) {
            for (column in 0 until 5) {
                var sum = 0f
                for (k in 0 until 4) sum += second[row * 5 + k] * first[k * 5 + column]
                if (column == 4) sum += second[row * 5 + 4]
                result[row * 5 + column] = sum
            }
        }
        return result
    }

    /** Applies matrices left to right. */
    fun chain(vararg matrices: FloatArray): FloatArray = matrices.fold(IDENTITY) { acc, next -> then(acc, next) }

    /** Linear mix: 0 gives [from], 1 gives [to]. */
    fun lerp(from: FloatArray, to: FloatArray, amount: Float): FloatArray =
        FloatArray(SIZE) { from[it] + (to[it] - from[it]) * amount }

    /** What [matrix] does to one opaque pixel; channels 0..255, clamped like the platform does. */
    fun apply(matrix: FloatArray, red: Int, green: Int, blue: Int): IntArray = IntArray(3) { row ->
        val value = matrix[row * 5] * red + matrix[row * 5 + 1] * green + matrix[row * 5 + 2] * blue +
            matrix[row * 5 + 3] * 255f + matrix[row * 5 + 4]
        value.roundToInt().coerceIn(0, 255)
    }

    // ── The colour-matrix filters of the catalogue ──────────────────────

    /** Stronger colours and a little more contrast: for colourful flyers and photos. */
    val VIVID: FloatArray get() = then(saturation(1.45f), contrast(1.12f))

    /** Warmer white balance: a touch more red, less blue. */
    val WARM: FloatArray get() = then(channels(1.05f, 1.0f, 0.9f, redShift = 5f), saturation(1.05f))

    /** Cooler white balance: less red, a touch more blue. */
    val COOL: FloatArray get() = then(channels(0.92f, 1f, 1.06f, blueShift = 8f), saturation(1.02f))

    /** Faded, sepia-tinted look; text stays legible. */
    val VINTAGE: FloatArray get() = chain(lerp(IDENTITY, SEPIA, 0.65f), contrast(0.9f), brightness(10f))

    /**
     * Stand-ins for the OpenCV filters when the native library could not be
     * loaded: simpler, but in the same spirit, so the choice still means something.
     */
    fun fallback(algorithm: FilterAlgorithm): FloatArray? = when (algorithm) {
        FilterAlgorithm.NONE -> null
        FilterAlgorithm.AUTO -> then(contrast(1.2f), saturation(1.1f))
        FilterAlgorithm.MAGIC_COLOR -> chain(contrast(1.3f), brightness(18f), saturation(1.3f))
        FilterAlgorithm.NO_SHADOW -> then(contrast(1.15f), brightness(14f))
        FilterAlgorithm.LIGHTEN -> then(brightness(28f), contrast(0.95f))
        FilterAlgorithm.SHARPEN -> contrast(1.12f)
        FilterAlgorithm.GREYSCALE -> then(saturation(0f), contrast(1.15f))
        // A steep contrast curve pushes paper to white and ink to black.
        FilterAlgorithm.BLACK_WHITE -> then(saturation(0f), contrast(4f))
        FilterAlgorithm.BW_SHARP -> chain(saturation(0f), brightness(12f), contrast(5f))
        FilterAlgorithm.INK_SAVER -> chain(saturation(0f), contrast(0.6f), brightness(60f))
        FilterAlgorithm.VIVID -> VIVID
        FilterAlgorithm.WARM -> WARM
        FilterAlgorithm.COOL -> COOL
        FilterAlgorithm.VINTAGE -> VINTAGE
    }

    /** The matrix for a pure colour-matrix filter, or null when the filter needs pixel work. */
    fun forAlgorithm(algorithm: FilterAlgorithm): FloatArray? = when (algorithm) {
        FilterAlgorithm.VIVID -> VIVID
        FilterAlgorithm.WARM -> WARM
        FilterAlgorithm.COOL -> COOL
        FilterAlgorithm.VINTAGE -> VINTAGE
        else -> null
    }

    private const val SIZE = 20
    private const val MID_GREY = 127.5f
    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f
}

/**
 * The user's brightness / contrast / strength controls, as maths shared by the
 * live preview and the final render.
 *
 * A page is drawn as two layers: the unfiltered page, then the filtered page
 * on top at an opacity equal to the filter strength (a linear blend). Each
 * layer goes through the same brightness-and-contrast matrix. Preview and
 * render do exactly this, so what the user sees is what is saved.
 */
internal object Adjustments {
    /** Brightness 1 adds this much to every channel (of 255). */
    const val MAX_BRIGHTNESS_SHIFT = 96f

    /** Contrast 1 doubles the distance from mid-grey; -1 halves it. */
    private const val CONTRAST_RANGE = 2f

    private const val NEUTRAL_EPSILON = 0.005f

    fun isNeutral(brightness: Float, contrast: Float): Boolean =
        kotlin.math.abs(brightness) < NEUTRAL_EPSILON && kotlin.math.abs(contrast) < NEUTRAL_EPSILON

    /** Contrast factor for a control value in -1..1. */
    fun contrastFactor(contrast: Float): Float = CONTRAST_RANGE.pow(contrast.coerceIn(-1f, 1f))

    /** Contrast around mid-grey, then the brightness shift. Identity at (0, 0). */
    fun brightnessContrast(brightness: Float, contrast: Float): FloatArray = ColorMatrices.then(
        ColorMatrices.contrast(contrastFactor(contrast)),
        ColorMatrices.brightness(brightness.coerceIn(-1f, 1f) * MAX_BRIGHTNESS_SHIFT),
    )

    /** Opacity (0..255) of the filtered layer for a strength in 0..1. */
    fun strengthAlpha(strength: Float): Int = (strength.coerceIn(0f, 1f) * 255f).roundToInt()

    /** True when the filtered layer covers the unfiltered one completely. */
    fun isFullStrength(strength: Float): Boolean = strengthAlpha(strength) >= 255

    /** One channel of the blend that drawing the filtered layer at [strength] produces. */
    fun blend(unfiltered: Int, filtered: Int, strength: Float): Int {
        val alpha = strengthAlpha(strength) / 255f
        return (unfiltered + (filtered - unfiltered) * alpha).roundToInt().coerceIn(0, 255)
    }
}
