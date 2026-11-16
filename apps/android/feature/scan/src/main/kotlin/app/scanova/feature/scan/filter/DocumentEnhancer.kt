package app.scanova.feature.scan.filter

import app.scanova.feature.scan.filter.Mats.Companion.odd
import kotlin.math.max
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/** How far the enhancement goes beyond evening out the page. */
internal enum class EnhanceStyle(
    /** Normalised level that becomes black: higher = deeper ink. */
    val black: Double,
    /** Tone gamma after levels: > 1 deepens mid-tones (ink edges). */
    val gamma: Double,
) {
    /** Clean and natural: white paper, true ink. */
    NATURAL(black = 0.05, gamma = 1.04),

    /** Punchier: deeper ink (the colour boost is the caller's). */
    VIVID(black = 0.10, gamma = 1.12),

    /** Only light and noise fixed: no extra depth. */
    EVEN(black = 0.0, gamma = 1.0),
}

/**
 * Turns a photographed page into a clean scan. On the synthetic test in
 * `feature/scan/eval/enhance_eval.py` (a clean page photographed with uneven
 * light, a shadow, a reflection, a colour cast, blur and sensor noise) the
 * "Auto" look restores it to SSIM 0.977 / PSNR 27 dB (the photo: 0.60 / 10 dB).
 * That script is the reference implementation: every step here matches it,
 * so change both together and keep the scores from dropping.
 *
 * 1. **Noise.** The noise level is measured on the page itself. Colour noise
 *    is removed by smoothing only the colour planes (cheap, invisible); grain
 *    in the brightness, when there is any, by non-local means at a strength
 *    that follows the measured level — so the lighting step below never
 *    amplifies it.
 * 2. **Lighting.** Each colour channel is divided by an estimate of the bare
 *    paper (ink removed by a closing, large solid areas filled in from the
 *    paper around them, then smoothed): shadows, a lamp on one side and colour
 *    casts disappear; paper becomes ≈ 1 everywhere, stamps and logos stay solid.
 * 3. **Reflections.** A reflection is a compact spot of paper brighter than
 *    its own surroundings (an evenly brighter side is not one). It is mapped
 *    so washed-out paper there becomes white and its sparkle is cleaned up.
 * 4. **Faint ink.** Where the darkest ink nearby is only slightly below the
 *    paper (glare, deep shadow), its contrast is stretched back — but only
 *    where it stands clearly above the paper's own measured noise, so blank
 *    paper stays blank, and never past the depth of ordinary coloured text,
 *    so colours stay true.
 * 5. **Tone.** Paper within about three noise spreads of white becomes pure
 *    white (no grain left), ink keeps its depth.
 * 6. **Specks.** Small, faint, isolated dots (sensor specks, paper texture,
 *    sparkle around a reflection) are removed; strokes are larger or darker.
 *
 * Sizes follow the image, so a 220-pixel preview looks like a small copy of
 * the full page. All work is on the caller's thread.
 */
internal object DocumentEnhancer {

    class Result(
        /** The enhanced page, RGB CV_32FC3 in 0..1. */
        val rgb: Mat,
        /** How much each pixel lies in a reflection, CV_32FC1 in 0..1. */
        val glare: Mat,
    )

    fun enhance(rgba: Mat, style: EnhanceStyle, mats: Mats): Result {
        val longEdge = max(rgba.cols(), rgba.rows())
        val rgb8 = mats.new()
        Imgproc.cvtColor(rgba, rgb8, Imgproc.COLOR_RGBA2RGB)
        val image = mats.new()
        rgb8.convertTo(image, CvType.CV_32FC3, 1.0 / 255)

        val sigma = noiseSigma(image, mats)
        val denoised = denoise(image, longEdge, sigma, mats)

        // 2. Lighting, per channel.
        val channels = mats.split(denoised)
        val backgrounds = channels.map { background(it, mats) }
        val normalised = channels.mapIndexed { index, channel ->
            mats.new().also { out ->
                Core.divide(channel, backgrounds[index], out)
                Imgproc.threshold(out, out, MAX_NORMALISED, 0.0, Imgproc.THRESH_TRUNC)
            }
        }

        // 3. Reflections.
        val glare = glareMap(backgrounds, mats)

        // 4. Faint ink, measured on a small copy of the evened-out page.
        val merged = mats.new()
        Core.merge(normalised, merged)
        val gray = mats.new()
        Imgproc.cvtColor(merged, gray, Imgproc.COLOR_RGB2GRAY)
        val graySmall = mats.smallOf(gray, WORK_LONG_EDGE)
        val paper = EnhanceMath.paper(mats.floats(graySmall))
        val gain = faintInkGain(graySmall, paper, gray, mats)
        val level = Scalar(paper.level.toDouble())
        for (channel in normalised) {
            // paper − (paper − value) × gain
            Core.subtract(channel, level, channel)
            Core.multiply(channel, gain, channel)
            Core.add(channel, level, channel)
        }

        // 5. Tone: white point lowered inside reflections, so their washed-out paper is paper.
        val white = paper.whitePoint.toDouble()
        val range = mats.new()
        Core.multiply(glare, Scalar(-GLARE_WHITE_DROP), range)
        Core.add(range, Scalar(white - style.black), range)
        for (channel in normalised) {
            Core.subtract(channel, Scalar(style.black), channel)
            Core.divide(channel, range, channel)
            Imgproc.threshold(channel, channel, 1.0, 0.0, Imgproc.THRESH_TRUNC)
            Imgproc.threshold(channel, channel, 0.0, 0.0, Imgproc.THRESH_TOZERO)
            if (style.gamma != 1.0) Core.pow(channel, style.gamma, channel)
        }
        val result = mats.new()
        Core.merge(normalised, result)

        // 6. Specks.
        if (longEdge >= MIN_DESPECKLE_LONG_EDGE) despeckle(result, glare, longEdge, mats)
        return Result(result, glare)
    }

    /** Measured noise level of the brightness (0..1 units) of a CV_32FC3 image. */
    private fun noiseSigma(image: Mat, mats: Mats): Float {
        // Brightness in floating point: the weighted sum keeps detail below one 8-bit level.
        val gray = mats.new()
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGB2GRAY)
        val median = mats.new()
        Imgproc.medianBlur(gray, median, 3)
        val residual = mats.new()
        Core.absdiff(gray, median, residual)
        // Quarter-level bins: the residual of a clean page is often below one level.
        val scaled = mats.new()
        residual.convertTo(scaled, CvType.CV_8U, 255.0 * RESIDUAL_BINS_PER_LEVEL)
        val histogram = mats.new()
        Imgproc.calcHist(
            listOf(scaled),
            mats.track(MatOfInt(0)) as MatOfInt,
            mats.new(),
            histogram,
            mats.track(MatOfInt(HISTOGRAM_BINS)) as MatOfInt,
            mats.track(MatOfFloat(0f, 256f)) as MatOfFloat,
        )
        val counts = FloatArray(HISTOGRAM_BINS)
        histogram.get(0, 0, counts)
        return EnhanceMath.noiseSigma(counts, binsPerUnit = 255f * RESIDUAL_BINS_PER_LEVEL)
    }

    /** Colour planes smoothed always; brightness by non-local means when there is grain worth removing. */
    private fun denoise(image: Mat, longEdge: Int, sigma: Float, mats: Mats): Mat {
        val ycc = mats.new()
        Imgproc.cvtColor(image, ycc, Imgproc.COLOR_RGB2YCrCb)
        val planes = mats.split(ycc)
        val chromaSigma = CHROMA_SIGMA * max(1.0, longEdge / CHROMA_REFERENCE_EDGE)
        Imgproc.GaussianBlur(planes[1], planes[1], Size(0.0, 0.0), chromaSigma)
        Imgproc.GaussianBlur(planes[2], planes[2], Size(0.0, 0.0), chromaSigma)
        if (sigma > MIN_LUMA_NOISE) {
            val luma8 = mats.new()
            planes[0].convertTo(luma8, CvType.CV_8U, 255.0)
            val strength = (sigma * 255f * NLM_STRENGTH).coerceIn(NLM_MIN_H, NLM_MAX_H)
            Photo.fastNlMeansDenoising(luma8, luma8, strength, NLM_TEMPLATE, NLM_SEARCH)
            luma8.convertTo(planes[0], CvType.CV_32F, 1.0 / 255)
        }
        Core.merge(planes, ycc)
        val out = mats.new()
        Imgproc.cvtColor(ycc, out, Imgproc.COLOR_YCrCb2RGB)
        return out
    }

    /** The bare paper's brightness in one channel: ink filled in by a closing, then smoothed. */
    private fun background(channel: Mat, mats: Mats): Mat {
        val small = mats.smallOf(channel, WORK_LONG_EDGE)
        val longSide = max(small.cols(), small.rows())
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, mats.ellipse(odd(longSide / 40)))
        Imgproc.dilate(small, small, mats.ellipse(odd(longSide / 90)))
        fillSolidAreas(small, longSide, mats)
        // Wide medians need 8-bit input.
        val small8 = mats.new()
        small.convertTo(small8, CvType.CV_8U, 255.0)
        Imgproc.medianBlur(small8, small8, odd(longSide / 30))
        small8.convertTo(small, CvType.CV_32F, 1.0 / 255)
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), longSide / 120.0)
        val background = mats.upTo(small, channel)
        Core.max(background, Scalar(MIN_BACKGROUND), background)
        return background
    }

    /**
     * Large solid areas (stamps, logos, photos, filled table headers) are too big
     * for the closing to fill, so the estimate would follow them and brighten
     * them. Where the estimate is far below the paper around it, the paper is
     * taken from the surroundings instead (normalised convolution).
     */
    private fun fillSolidAreas(small: Mat, longSide: Int, mats: Mats) {
        val envelope = mats.new()
        Imgproc.dilate(small, envelope, mats.ellipse(odd(longSide / 12)))
        Imgproc.GaussianBlur(envelope, envelope, Size(0.0, 0.0), longSide / 30.0)
        Core.multiply(envelope, Scalar(SOLID_AREA_RATIO), envelope)
        val paper = mats.new()
        Core.compare(small, envelope, paper, Core.CMP_GE)
        val solid = mats.new()
        Core.bitwise_not(paper, solid)
        if (Core.countNonZero(solid) == 0) return
        val weight = mats.new()
        paper.convertTo(weight, CvType.CV_32F, 1.0 / 255)
        val weighted = mats.new()
        Core.multiply(small, weight, weighted)
        val spread = longSide / 25.0
        Imgproc.GaussianBlur(weighted, weighted, Size(0.0, 0.0), spread)
        Imgproc.GaussianBlur(weight, weight, Size(0.0, 0.0), spread)
        Core.max(weight, Scalar(1e-3), weight)
        Core.divide(weighted, weight, weighted)
        weighted.copyTo(small, solid)
    }

    /** 0..1: how much each pixel sits in a reflection (paper brighter than its own surroundings). */
    private fun glareMap(backgrounds: List<Mat>, mats: Mats): Mat {
        val mergedSmall = mats.new()
        Core.merge(backgrounds.map { mats.smallOf(it, WORK_LONG_EDGE) }, mergedSmall)
        val paperLight = mats.new()
        Imgproc.cvtColor(mergedSmall, paperLight, Imgproc.COLOR_RGB2GRAY)
        val longSide = max(paperLight.cols(), paperLight.rows())
        val surroundings = mats.new()
        Imgproc.GaussianBlur(paperLight, surroundings, Size(0.0, 0.0), longSide / 10.0)
        val light = mats.floats(paperLight)
        val around = mats.floats(surroundings)
        val glare = FloatArray(light.size) { index ->
            EnhanceMath.smoothstep(GLARE_FROM, GLARE_FULL, light[index] / max(around[index], 1e-3f))
        }
        val small = mats.ofFloats(glare, paperLight.rows(), paperLight.cols())
        // Include the reflection's rim, then soften the edge.
        Imgproc.dilate(small, small, mats.ellipse(odd(longSide / 40)))
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), longSide / 120.0)
        val full = mats.upTo(small, backgrounds.first(), Imgproc.INTER_LINEAR)
        Imgproc.threshold(full, full, 1.0, 0.0, Imgproc.THRESH_TRUNC)
        return full
    }

    /** Per-pixel ink gain (≥ 1), from the darkest ink near each pixel. */
    private fun faintInkGain(graySmall: Mat, paper: EnhanceMath.Paper, full: Mat, mats: Mats): Mat {
        val longSide = max(graySmall.cols(), graySmall.rows())
        val ink = mats.new()
        Imgproc.GaussianBlur(graySmall, ink, Size(0.0, 0.0), max(0.6, longSide / 900.0))
        Imgproc.erode(ink, ink, mats.ellipse(odd(longSide / 25)))
        Imgproc.GaussianBlur(ink, ink, Size(0.0, 0.0), longSide / 60.0)
        val darkest = mats.floats(ink)
        val gain = FloatArray(darkest.size) { index ->
            EnhanceMath.faintInkGain((paper.level - darkest[index]).coerceIn(0f, 1f), paper)
        }
        val gainFull = mats.upTo(mats.ofFloats(gain, ink.rows(), ink.cols()), full)
        Core.max(gainFull, Scalar(1.0), gainFull)
        return gainFull
    }

    /** Whitens small, faint, isolated dots (see [EnhanceMath.isSpeck]). */
    private fun despeckle(rgb: Mat, glare: Mat, longEdge: Int, mats: Mats) {
        val lightness8 = mats.new()
        val rgb8 = mats.new()
        rgb.convertTo(rgb8, CvType.CV_8UC3, 255.0)
        Imgproc.cvtColor(rgb8, lightness8, Imgproc.COLOR_RGB2GRAY)
        val dark = mats.new()
        Imgproc.threshold(lightness8, dark, SPECK_PAPER_LEVEL, 255.0, Imgproc.THRESH_BINARY_INV)
        val labels = mats.new()
        val stats = mats.new()
        val centroids = mats.new()
        val count = Imgproc.connectedComponentsWithStats(dark, labels, stats, centroids, 8, CvType.CV_32S)
        if (count <= 1) return
        val values = IntArray(count * Imgproc.CC_STAT_MAX)
        stats.get(0, 0, values)
        val glare8 = mats.new()
        glare.convertTo(glare8, CvType.CV_8U, 255.0)
        val unit = (longEdge / 1000.0) * (longEdge / 1000.0)
        val component = mats.new()
        val white = Scalar(1.0, 1.0, 1.0)
        for (label in 1 until minOf(count, MAX_SPECK_CANDIDATES)) {
            val base = label * Imgproc.CC_STAT_MAX
            val area = values[base + Imgproc.CC_STAT_AREA]
            if (area >= SPECK_MAX_AREA_UNITS * unit) continue
            val box = Rect(
                values[base + Imgproc.CC_STAT_LEFT],
                values[base + Imgproc.CC_STAT_TOP],
                values[base + Imgproc.CC_STAT_WIDTH],
                values[base + Imgproc.CC_STAT_HEIGHT],
            )
            val labelBox = labels.submat(box)
            val lightBox = lightness8.submat(box)
            val glareBox = glare8.submat(box)
            Core.compare(labelBox, Scalar(label.toDouble()), component, Core.CMP_EQ)
            val darkest = Core.minMaxLoc(lightBox, component).minVal.toFloat() / 255f
            val inGlare = Core.minMaxLoc(glareBox, component).maxVal.toFloat() / 255f
            if (EnhanceMath.isSpeck(area, darkest, inGlare, longEdge)) {
                val pageBox = rgb.submat(box)
                pageBox.setTo(white, component)
                pageBox.release()
            }
            labelBox.release()
            lightBox.release()
            glareBox.release()
        }
    }

    /**
     * Whitens isolated black dots smaller than [maxArea] pixels in a black-and-white
     * page (CV_8UC1, ink 0, paper 255): specks that thresholding turns into dirt.
     */
    fun removeDots(bw: Mat, maxArea: Int, mats: Mats) {
        if (maxArea < 1) return
        val ink = mats.new()
        Imgproc.threshold(bw, ink, 127.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val labels = mats.new()
        val stats = mats.new()
        val centroids = mats.new()
        val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
        if (count <= 1) return
        val values = IntArray(count * Imgproc.CC_STAT_MAX)
        stats.get(0, 0, values)
        val component = mats.new()
        for (label in 1 until minOf(count, MAX_SPECK_CANDIDATES)) {
            val base = label * Imgproc.CC_STAT_MAX
            if (values[base + Imgproc.CC_STAT_AREA] > maxArea) continue
            val box = Rect(
                values[base + Imgproc.CC_STAT_LEFT],
                values[base + Imgproc.CC_STAT_TOP],
                values[base + Imgproc.CC_STAT_WIDTH],
                values[base + Imgproc.CC_STAT_HEIGHT],
            )
            val labelBox = labels.submat(box)
            val pageBox = bw.submat(box)
            Core.compare(labelBox, Scalar(label.toDouble()), component, Core.CMP_EQ)
            pageBox.setTo(Scalar(255.0), component)
            labelBox.release()
            pageBox.release()
        }
    }

    private const val WORK_LONG_EDGE = 640
    private const val MAX_NORMALISED = 1.25
    private const val MIN_BACKGROUND = 0.08

    /** The paper estimate counts as "inside a solid area" below this share of the paper around it. */
    private const val SOLID_AREA_RATIO = 0.72
    private const val GLARE_FROM = 1.025f
    private const val GLARE_FULL = 1.10f
    private const val GLARE_WHITE_DROP = 0.16
    private const val MIN_LUMA_NOISE = 0.006f
    private const val NLM_STRENGTH = 1.1f
    private const val NLM_MIN_H = 3f
    private const val NLM_MAX_H = 25f
    private const val NLM_TEMPLATE = 7
    private const val NLM_SEARCH = 15
    private const val CHROMA_SIGMA = 1.6
    private const val CHROMA_REFERENCE_EDGE = 1100.0
    private const val RESIDUAL_BINS_PER_LEVEL = 4f
    private const val HISTOGRAM_BINS = 256
    private const val SPECK_PAPER_LEVEL = 244.0
    private const val SPECK_MAX_AREA_UNITS = 80.0
    private const val MAX_SPECK_CANDIDATES = 200_000
    private const val MIN_DESPECKLE_LONG_EDGE = 500
}
