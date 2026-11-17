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
 * Turns a photographed page into a clean scan. On the synthetic tests in
 * `feature/scan/eval/enhance_eval.py` the "Auto" look restores a page
 * photographed with uneven light, a soft shadow, a reflection, a colour cast,
 * blur and sensor noise to SSIM 0.978 / PSNR 27 dB (the photo: 0.60 / 10 dB),
 * and a page with the hard-edged, brownish shadow of a hand and a phone across
 * it to 0.977 / 27 dB (the photo: 0.66 / 10 dB) with the paper left white.
 * That script is the reference implementation: every step here matches it,
 * so change both together and keep the scores from dropping.
 *
 * 1. **Noise.** The noise level is measured on the page itself. Colour noise
 *    is removed by smoothing only the colour planes (cheap, invisible); grain
 *    in the brightness, when there is any, by non-local means at a strength
 *    that follows the measured level — so the lighting step below never
 *    amplifies it.
 * 2. **Lighting.** Each colour channel is divided by an estimate of the bare
 *    paper, made on a small copy: ink removed by a closing, then smoothed.
 *    Large solid areas (stamps, logos, photos, filled boxes) are too big for
 *    the closing: where the estimate is far below the paper nearby, the paper
 *    is taken from around them instead, decided once for all three channels.
 *    A hard shadow is not a solid area: a region of paper in less light (every
 *    channel dimmed by a similar factor, green between red and blue) that
 *    reaches the page's edge, is large, and inside is as smooth and evenly
 *    coloured as paper ([EnhanceMath.shadows]; photos, printed colour and
 *    textured desks fail this and are left alone). Near such a shadow the
 *    estimate is smoothed by a filter that keeps steps sharp, so its edge is
 *    followed instead of being blurred into a dark rim. Shadows, a lamp on one
 *    side and colour casts disappear; paper becomes ≈ 1 everywhere, stamps,
 *    logos and pictures stay solid.
 * 3. **Reflections.** A reflection is a compact spot of paper brighter than
 *    the paper around it (an evenly brighter side, or the lit side of a
 *    shadow, is not one). It is mapped so washed-out paper there becomes white
 *    and its sparkle is cleaned up.
 * 4. **Faint ink.** Where the darkest ink nearby is only slightly below the
 *    paper (glare, deep shadow), its contrast is stretched back — but only
 *    where it stands clearly above the paper's own measured noise, so blank
 *    paper stays blank, never past the depth of ordinary coloured text, and
 *    not inside solid areas. The stretch changes the brightness only (the same
 *    amount in every channel), so it cannot create or strengthen a colour: a
 *    faint tint left by a shadow stays faint instead of turning orange.
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
        val paperMaps = paperMaps(denoised, mats)
        val backgrounds = paperMaps.channels
        val normalised = mats.split(denoised).mapIndexed { index, channel ->
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
        val gain = faintInkGain(graySmall, paper, paperMaps.solid, gray, mats)
        // Brightness paper − (paper − value) × gain, by the same change in every channel: no colour is made stronger.
        val change = mats.new()
        Core.subtract(gray, Scalar(paper.level.toDouble()), change)
        Core.subtract(gain, Scalar(1.0), gain)
        Core.multiply(change, gain, change)
        for (channel in normalised) Core.add(channel, change, channel)

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

    /** The bare paper of each channel (full size), and where solid areas are ([solid]: CV_32FC1 at the working size, 1 inside). */
    private class PaperMaps(val channels: List<Mat>, val solid: Mat)

    /**
     * The bare paper's brightness in each channel: ink removed by a closing on a
     * small copy, solid areas filled in from the paper around them (one decision
     * for all three channels), then smoothed. Shadows found by
     * [EnhanceMath.shadows] are not solid areas, and near them the estimate is
     * made so that their edges stay sharp.
     */
    private fun paperMaps(image: Mat, mats: Mats): PaperMaps {
        val small = mats.smallOf(image, WORK_LONG_EDGE)
        val cols = small.cols()
        val rows = small.rows()
        val longSide = max(cols, rows)
        // Ink removed by a closing; a slight dilation puts the estimate at the paper's top, above its noise.
        val sharp = mats.split(small).map { plane ->
            mats.new().also { Imgproc.morphologyEx(plane, it, Imgproc.MORPH_CLOSE, mats.ellipse(odd(longSide / 40))) }
        }
        val closed = sharp.map { plane -> mats.new().also { Imgproc.dilate(plane, it, mats.ellipse(odd(longSide / 90))) } }
        // Each channel against the paper nearby (from a slightly smoothed copy, so noise does not lift it).
        val farBelow = mats.new()
        val calm = closed.map { plane -> mats.new().also { Imgproc.GaussianBlur(plane, it, Size(0.0, 0.0), longSide / 160.0) } }
        val ratios = closed.indices.map { index ->
            val nearby = mats.new()
            Imgproc.dilate(calm[index], nearby, mats.ellipse(odd(longSide / 12)))
            Imgproc.GaussianBlur(nearby, nearby, Size(0.0, 0.0), longSide / 30.0)
            val limit = mats.new()
            Core.multiply(nearby, Scalar(SOLID_AREA_RATIO), limit)
            val below = mats.new()
            Core.compare(closed[index], limit, below, Core.CMP_LT)
            if (farBelow.empty()) below.copyTo(farBelow) else Core.bitwise_or(farBelow, below, farBelow)
            Core.max(nearby, Scalar(1e-3), nearby)
            val ratio = mats.new()
            Core.divide(calm[index], nearby, ratio)
            mats.floats(ratio)
        }
        val farBelowBytes = ByteArray(cols * rows)
        farBelow.get(0, 0, farBelowBytes)
        val candidates = BooleanArray(farBelowBytes.size) {
            farBelowBytes[it] != 0.toByte() && EnhanceMath.isDimmedPaper(ratios[0][it], ratios[1][it], ratios[2][it])
        }
        val shadow = shadowsOf(candidates, closed, calm, cols, rows, mats)
        val solid = BooleanArray(candidates.size) { farBelowBytes[it] != 0.toByte() && !shadow[it] }

        val solidMap = mats.ofFloats(FloatArray(solid.size) { if (solid[it]) 1f else 0f }, rows, cols)
        val valid = mats.ofFloats(FloatArray(solid.size) { if (solid[it]) 0f else 1f }, rows, cols)
        val solidMask = mats.new()
        solidMap.convertTo(solidMask, CvType.CV_8U, 255.0)
        val anySolid = solid.any { it }
        val spread = longSide / 25.0
        val weight = mats.new()
        if (anySolid) {
            Imgproc.GaussianBlur(valid, weight, Size(0.0, 0.0), spread)
            Core.max(weight, Scalar(1e-3), weight)
        }
        // Normalised convolution: the paper around a solid area, carried into it.
        fun fill(plane: Mat) {
            if (!anySolid) return
            val filled = mats.new()
            Core.multiply(plane, valid, filled)
            Imgproc.GaussianBlur(filled, filled, Size(0.0, 0.0), spread)
            Core.divide(filled, weight, filled)
            filled.copyTo(plane, solidMask)
        }
        // Wide medians need 8-bit input.
        fun median8(plane: Mat, size: Int) {
            val plane8 = mats.new()
            plane.convertTo(plane8, CvType.CV_8U, 255.0)
            Imgproc.medianBlur(plane8, plane8, odd(size))
            plane8.convertTo(plane, CvType.CV_32F, 1.0 / 255)
        }
        // Near a shadow the estimate must keep the shadow's edge: no dilation, a small median, an edge-keeping smoothing.
        val near = if (shadow.any { it }) {
            mats.ofFloats(FloatArray(shadow.size) { if (shadow[it]) 1f else 0f }, rows, cols).also { map ->
                Imgproc.dilate(map, map, mats.ellipse(odd(longSide / 16)))
                Imgproc.GaussianBlur(map, map, Size(0.0, 0.0), longSide / 120.0)
            }
        } else {
            null
        }
        val channels = closed.indices.map { index ->
            val plane = closed[index]
            fill(plane)
            median8(plane, longSide / 30)
            Imgproc.GaussianBlur(plane, plane, Size(0.0, 0.0), longSide / 120.0)
            if (near != null) {
                val edgePlane = sharp[index]
                fill(edgePlane)
                median8(edgePlane, longSide / 60)
                val edge = smoothKeepingEdges(edgePlane, max(1, longSide / 80), EDGE_KEEPING_EPS, mats)
                // near · edge + (1 − near) · plane
                Core.subtract(edge, plane, edge)
                Core.multiply(edge, near, edge)
                Core.add(plane, edge, plane)
            }
            val background = mats.upTo(plane, image)
            Core.max(background, Scalar(MIN_BACKGROUND), background)
            background
        }
        return PaperMaps(channels, solidMap)
    }

    /**
     * The shadow pixels among [candidates] (far below the paper nearby, and
     * dimmed paper), at the working size: the statistics [EnhanceMath.shadows]
     * judges regions by, measured on the closed estimate ([closed], RGB planes)
     * and its smoothed copy ([calm]).
     */
    private fun shadowsOf(candidates: BooleanArray, closed: List<Mat>, calm: List<Mat>, cols: Int, rows: Int, mats: Mats): BooleanArray {
        val longSide = max(cols, rows)
        val merged = mats.new()
        Core.merge(closed, merged)
        val brightness = mats.new()
        Imgproc.cvtColor(merged, brightness, Imgproc.COLOR_RGB2GRAY)
        val dx = mats.new()
        val dy = mats.new()
        Imgproc.Sobel(brightness, dx, CvType.CV_32F, 1, 0, 3, 1.0 / 8)
        Imgproc.Sobel(brightness, dy, CvType.CV_32F, 0, 1, 3, 1.0 / 8)
        val gradient = mats.new()
        Core.magnitude(dx, dy, gradient)
        Core.max(brightness, Scalar(0.05), brightness)
        Core.divide(gradient, brightness, gradient)
        val logs = calm.map { plane ->
            mats.new().also {
                Core.max(plane, Scalar(1e-3), it)
                Core.log(it, it)
            }
        }
        val hueRedGreen = mats.new()
        Core.subtract(logs[0], logs[1], hueRedGreen)
        val hueBlueGreen = mats.new()
        Core.subtract(logs[2], logs[1], hueBlueGreen)
        // Inside a region: away from its own edge.
        val candidates8 = mats.track(Mat(rows, cols, CvType.CV_8UC1))
        val bytes = ByteArray(candidates.size) { if (candidates[it]) 1 else 0 }
        candidates8.put(0, 0, bytes)
        Imgproc.erode(candidates8, candidates8, mats.ellipse(odd(2 * max(1, longSide / 80) + 1)))
        candidates8.get(0, 0, bytes)
        return EnhanceMath.shadows(
            candidates = candidates,
            inside = BooleanArray(bytes.size) { bytes[it] != 0.toByte() },
            gradient = mats.floats(gradient),
            hueRedGreen = mats.floats(hueRedGreen),
            hueBlueGreen = mats.floats(hueBlueGreen),
            cols = cols,
            rows = rows,
        )
    }

    /**
     * A guided filter with [map] as its own guide (built from box filters, as
     * OpenCV's ximgproc module is not in the Android build): within each
     * window of 2·[radius]+1 pixels, flat parts (variance well below [eps])
     * are averaged and steps such as a shadow's edge are kept.
     */
    private fun smoothKeepingEdges(map: Mat, radius: Int, eps: Double, mats: Mats): Mat {
        val window = Size((2 * radius + 1).toDouble(), (2 * radius + 1).toDouble())
        fun boxed(source: Mat): Mat = mats.new().also { Imgproc.boxFilter(source, it, -1, window) }
        val mean = boxed(map)
        val squares = mats.new()
        Core.multiply(map, map, squares)
        val variance = boxed(squares)
        val meanSquared = mats.new()
        Core.multiply(mean, mean, meanSquared)
        Core.subtract(variance, meanSquared, variance)
        Core.max(variance, Scalar(0.0), variance)
        // q = a·map + b, with a = variance / (variance + eps) and b = mean · (1 − a), each averaged over the window.
        val a = mats.new()
        Core.add(variance, Scalar(eps), a)
        Core.divide(variance, a, a)
        val b = mats.new()
        Core.multiply(a, mean, b)
        Core.subtract(mean, b, b)
        val out = boxed(a)
        Core.multiply(out, map, out)
        Core.add(out, boxed(b), out)
        return out
    }

    /** 0..1: how much each pixel sits in a reflection (paper brighter than the paper around it). */
    private fun glareMap(backgrounds: List<Mat>, mats: Mats): Mat {
        val mergedSmall = mats.new()
        Core.merge(backgrounds.map { mats.smallOf(it, WORK_LONG_EDGE) }, mergedSmall)
        val paperLight = mats.new()
        Imgproc.cvtColor(mergedSmall, paperLight, Imgproc.COLOR_RGB2GRAY)
        val longSide = max(paperLight.cols(), paperLight.rows())
        val surroundings = mats.new()
        Imgproc.GaussianBlur(paperLight, surroundings, Size(0.0, 0.0), longSide / 10.0)
        // An opening keeps large lit areas and removes compact bright spots: next to a shadow it is the lit paper,
        // so the lit side of a shadow is not taken for a reflection.
        val opened = mats.new()
        Imgproc.morphologyEx(paperLight, opened, Imgproc.MORPH_OPEN, mats.square(odd(longSide / 4)))
        Imgproc.GaussianBlur(opened, opened, Size(0.0, 0.0), longSide / 30.0)
        Core.max(surroundings, opened, surroundings)
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

    /** Per-pixel ink gain (≥ 1), from the darkest ink near each pixel; none inside solid areas ([solidSmall], same size as [graySmall]). */
    private fun faintInkGain(graySmall: Mat, paper: EnhanceMath.Paper, solidSmall: Mat, full: Mat, mats: Mats): Mat {
        val longSide = max(graySmall.cols(), graySmall.rows())
        val ink = mats.new()
        Imgproc.GaussianBlur(graySmall, ink, Size(0.0, 0.0), max(0.6, longSide / 900.0))
        Imgproc.erode(ink, ink, mats.ellipse(odd(longSide / 25)))
        Imgproc.GaussianBlur(ink, ink, Size(0.0, 0.0), longSide / 60.0)
        val darkest = mats.floats(ink)
        val solidNear = mats.new()
        Imgproc.dilate(solidSmall, solidNear, mats.ellipse(odd(longSide / 60)))
        Imgproc.GaussianBlur(solidNear, solidNear, Size(0.0, 0.0), longSide / 120.0)
        val inSolid = if (solidNear.size() == ink.size()) mats.floats(solidNear) else FloatArray(darkest.size)
        val gain = FloatArray(darkest.size) { index ->
            EnhanceMath.faintInkGain((paper.level - darkest[index]).coerceIn(0f, 1f), paper, inSolid[index])
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

    /** Variance (in 0..1 units²) below which the paper estimate is smoothed; steps well above it are kept. */
    private const val EDGE_KEEPING_EPS = 1e-4
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
