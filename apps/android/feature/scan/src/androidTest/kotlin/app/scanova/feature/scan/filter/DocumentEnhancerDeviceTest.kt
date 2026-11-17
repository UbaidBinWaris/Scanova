package app.scanova.feature.scan.filter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scanova.core.data.model.PageFilters
import app.scanova.feature.scan.imaging.OpenCvSupport
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Auto look on the phone, against the reference implementation
 * (`feature/scan/eval/enhance_eval.py`) on the same synthetic photos (all
 * invented): a page with a hard-edged warm hand/phone shadow across text, a
 * logo and a seal (before the shadow fix it came out as an orange blotch), and
 * a page with a large photo running off its top and right edges (a first
 * version of the fix took such pictures for shadows and wiped them out).
 *
 * Fixtures in `androidTest/assets/enhance/`, 1,000 px: each photo (JPEG), the
 * clean page it was made from, the reference's Auto result for that JPEG, and
 * for the photo page a mask of the picture.
 */
@RunWith(AndroidJUnit4::class)
class DocumentEnhancerDeviceTest {

    @Before
    fun setUp() {
        assertTrue("OpenCV did not load", OpenCvSupport.isAvailable)
    }

    private fun asset(name: String): Bitmap =
        InstrumentationRegistry.getInstrumentation().context.assets.open("enhance/$name").use(BitmapFactory::decodeStream)

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    private fun channels(pixel: Int) = intArrayOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)

    /** Scores of the phone's Auto result for one fixture, against the reference and the clean page. */
    private class Scores(val meanDiff: Double, val bigShare: Double, val tint99: Int, val psnr: Double, val psnrPicture: Double, val referencePsnrPicture: Double)

    private fun run(case: String, pictureMask: String? = null): Scores {
        val photo = asset("${case}_photo.jpg")
        val reference = pixels(asset("${case}_auto_reference.png"))
        val clean = pixels(asset("${case}_clean.png"))
        val picture = pictureMask?.let { pixels(asset(it)) }

        val started = System.nanoTime()
        val result = FilterEngine().apply(photo, PageFilters.ENHANCED)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(photo.width, result.width)
        assertEquals(photo.height, result.height)
        val out = pixels(result)

        var sumDiff = 0L
        var bigDiffs = 0
        // Colour left on what is bare paper in the clean page (the shadow bug made it orange), and closeness to that page.
        val tints = ArrayList<Int>()
        var squared = 0.0
        var squaredPicture = 0.0
        var squaredPictureReference = 0.0
        var pictureCount = 0
        for (i in out.indices) {
            val o = channels(out[i])
            val r = channels(reference[i])
            val c = channels(clean[i])
            val inPicture = picture != null && (picture[i] and 0xFF) > 127
            for (k in 0..2) {
                val d = abs(o[k] - r[k])
                sumDiff += d
                if (d > 24) bigDiffs++
                squared += (o[k] - c[k]).toDouble().let { it * it }
                if (inPicture) {
                    squaredPicture += (o[k] - c[k]).toDouble().let { it * it }
                    squaredPictureReference += (r[k] - c[k]).toDouble().let { it * it }
                }
            }
            if (inPicture) pictureCount++
            if (min(c[0], min(c[1], c[2])) >= 245) tints += max(o[0], max(o[1], o[2])) - min(o[0], min(o[1], o[2]))
        }
        tints.sort()
        fun psnrOf(sum: Double, count: Int) = if (count == 0) Double.NaN else 10 * log10(255.0 * 255.0 / (sum / (count * 3)))
        val scores = Scores(
            meanDiff = sumDiff.toDouble() / (out.size * 3),
            bigShare = bigDiffs.toDouble() / (out.size * 3),
            tint99 = tints[(tints.size * 0.99).toInt().coerceAtMost(tints.size - 1)],
            psnr = psnrOf(squared, out.size),
            psnrPicture = psnrOf(squaredPicture, pictureCount),
            referencePsnrPicture = psnrOf(squaredPictureReference, pictureCount),
        )
        Log.i(
            TAG,
            "$case ${photo.width}x${photo.height}: $millis ms, mean |Δ| to reference %.2f, >24 levels %.4f, tint99 ${scores.tint99}, PSNR %.2f dB, picture %.2f dB (reference %.2f dB)"
                .format(scores.meanDiff, scores.bigShare, scores.psnr, scores.psnrPicture, scores.referencePsnrPicture),
        )
        return scores
    }

    @Test
    fun aHardWarmShadowBecomesCleanPaperAsInTheReference() {
        val scores = run("hard_warm_shadow")
        // The reference scores tint99 16 and 27.0 dB on this photo with this measure (the photo: 65 and 10.4 dB).
        assertTrue("mean difference from the reference: ${scores.meanDiff}", scores.meanDiff < 2.5)
        assertTrue("share of pixels far from the reference: ${scores.bigShare}", scores.bigShare < 0.01)
        assertTrue("colour left on the paper (tint99): ${scores.tint99}", scores.tint99 <= 20)
        assertTrue("PSNR against the clean page: ${scores.psnr}", scores.psnr > 24.0)
    }

    @Test
    fun aFullBleedPhotoIsNotTakenForAShadow() {
        val scores = run("full-bleed_photo", pictureMask = "full-bleed_photo_picture.png")
        // The reference: 14.6 dB on the page, 9.5 dB inside the picture (taking it for a shadow cost about 2 dB more).
        assertTrue("mean difference from the reference: ${scores.meanDiff}", scores.meanDiff < 2.5)
        assertTrue("share of pixels far from the reference: ${scores.bigShare}", scores.bigShare < 0.01)
        assertTrue(
            "picture: ${scores.psnrPicture} dB, reference ${scores.referencePsnrPicture} dB",
            scores.psnrPicture > scores.referencePsnrPicture - 1.0,
        )
    }

    private companion object {
        const val TAG = "EnhancerDeviceTest"
    }
}
