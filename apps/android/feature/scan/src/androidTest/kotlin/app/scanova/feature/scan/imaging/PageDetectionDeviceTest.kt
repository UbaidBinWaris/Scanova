package app.scanova.feature.scan.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import kotlin.math.hypot
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * The page model on a real Android runtime: the model loads from the app's
 * assets, OpenCV's dnn module runs it, and the decoded outline lands on the
 * page. Two real phone frames from SmartDoc 2015 Challenge 1 (CC BY 4.0, Burie
 * et al., ICDAR 2015) with their ground-truth corners: a cluttered background
 * (background05) and a plain one (background02).
 */
@RunWith(AndroidJUnit4::class)
class PageDetectionDeviceTest {

    private data class Frame(val asset: String, val truth: Quad)

    // Ground truth from SmartDoc's metadata (tl, tr, br, bl), in pixels of the 1920 × 1080 frame.
    private val frames = listOf(
        Frame(
            "smartdoc/background05-letter002-0040.jpg",
            Quad(Point2(750.139, 298.091), Point2(1164.47, 288.93), Point2(1278.49, 845.457), Point2(782.811, 882.339)),
        ),
        Frame(
            "smartdoc/background02-magazine003-0100.jpg",
            Quad(Point2(918.943, 198.508), Point2(1297.89, 220.994), Point2(1336.3, 702.025), Point2(881.551, 673.381)),
        ),
    )

    @Before
    fun setUp() {
        assertTrue("OpenCV did not load", OpenCvSupport.isAvailable)
        AiPageDetector.install(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun bitmap(asset: String): Bitmap =
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use(BitmapFactory::decodeStream)

    /** Largest corner gap as a fraction of the frame diagonal (the benchmark's "within 2 %"). */
    private fun error(found: Quad, truth: Quad, width: Int, height: Int): Double {
        val pixels = found.scale(width.toDouble(), height.toDouble())
        return pixels.maxCornerDistance(truth) / hypot(width.toDouble(), height.toDouble())
    }

    @Test
    fun theModelFindsBothPagesWithinTwoPercent() {
        for (frame in frames) {
            val photo = bitmap(frame.asset)
            val rgba = Mat()
            val bgr = Mat()
            try {
                Utils.bitmapToMat(photo, rgba)
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                val found = AiPageDetector.detect(bgr)
                assertNotNull("no page in ${frame.asset}", found)
                val gap = error(found!!, frame.truth, photo.width, photo.height)
                assertTrue("${frame.asset}: corner gap ${"%.4f".format(gap)} of the diagonal", gap < 0.02)
            } finally {
                rgba.release()
                bgr.release()
            }
        }
    }

    @Test
    fun photosGetTheModelsOutlineThroughTheDetector() {
        for (frame in frames) {
            val photo = bitmap(frame.asset)
            val found = DocumentDetector().detect(photo)
            assertNotNull("no page in ${frame.asset}", found)
            val gap = error(found!!, frame.truth, photo.width, photo.height)
            assertTrue("${frame.asset}: corner gap ${"%.4f".format(gap)} of the diagonal", gap < 0.02)
        }
    }

    @Test
    fun liveFramesFallBackToTheModelOnGrey() {
        val frame = frames.first() // the cluttered desk, where the edge detector finds nothing
        val photo = bitmap(frame.asset)
        val rgba = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(photo, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val luminance = ByteArray(gray.cols() * gray.rows())
            gray.get(0, 0, luminance)
            val found = DocumentDetector().detect(luminance, gray.cols(), gray.rows())
            assertNotNull("no page in the grey frame", found)
            val gap = error(found!!, frame.truth, photo.width, photo.height)
            assertTrue("grey frame: corner gap ${"%.4f".format(gap)} of the diagonal", gap < 0.03)
        } finally {
            rgba.release()
            gray.release()
        }
    }
}
