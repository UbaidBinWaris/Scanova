package app.scanova.feature.scan.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.scanova.feature.scan.geometry.Point2
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.render.PageFlattening
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * The flattening model on a real Android runtime: it loads from the app's
 * assets, OpenCV's dnn module runs it, a flat page is left alone and the same
 * page curled is found bent and flattened along its mesh. The flat page is a
 * real phone frame from SmartDoc 2015 (CC BY 4.0, Burie et al., ICDAR 2015);
 * the curl is made from it here, so its shape is known.
 */
@RunWith(AndroidJUnit4::class)
class PageFlatteningDeviceTest {

    // SmartDoc's ground truth for this frame (tl, tr, br, bl), in pixels of the 1920 × 1080 frame.
    private val asset = "smartdoc/background02-magazine003-0100.jpg"
    private val corners = listOf(Point2(918.943, 198.508), Point2(1297.89, 220.994), Point2(1336.3, 702.025), Point2(881.551, 673.381))

    @Before
    fun setUp() {
        assertTrue("OpenCV did not load", OpenCvSupport.isAvailable)
        PageFlattener.install(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun frame(): Bitmap =
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use(BitmapFactory::decodeStream)

    private fun outline(width: Int, height: Int) =
        Quad.fromUnordered(corners.map { Point2(it.x / width, it.y / height) })!!

    /**
     * [photo] with the page region's lower part bowed: every column of the box
     * around [outline] is pushed down by up to [amount] of the box's height,
     * most in the middle and at the bottom, like a page curling off the desk.
     */
    private fun curled(photo: Bitmap, outline: Quad, amount: Double): Bitmap {
        val w = photo.width
        val h = photo.height
        val left = outline.corners.minOf { it.x } * w
        val right = outline.corners.maxOf { it.x } * w
        val top = outline.corners.minOf { it.y } * h
        val bottom = outline.corners.maxOf { it.y } * h
        val mapX = Mat(h, w, CvType.CV_32FC1)
        val mapY = Mat(h, w, CvType.CV_32FC1)
        val rowX = FloatArray(w)
        val rowY = FloatArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val u = ((x - left) / (right - left)).coerceIn(0.0, 1.0)
                val v = ((y - top) / (bottom - top)).coerceIn(0.0, 1.0)
                rowX[x] = x.toFloat()
                rowY[x] = (y - amount * (bottom - top) * v * v * sin(PI * u)).toFloat()
            }
            mapX.put(y, 0, rowX)
            mapY.put(y, 0, rowY)
        }
        val input = Mat()
        val output = Mat()
        try {
            Utils.bitmapToMat(photo, input)
            Imgproc.remap(input, output, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            return createBitmap(w, h).also { Utils.matToBitmap(output, it) }
        } finally {
            input.release()
            output.release()
            mapX.release()
            mapY.release()
        }
    }

    @Test
    fun aFlatPageIsLeftAlone() {
        val photo = frame()
        val found = PageFlattener.flatten(photo, outline(photo.width, photo.height))
        assertNotNull("the model did not run", found)
        assertTrue("mesh ${found!!.mesh.rows} × ${found.mesh.cols}", found.mesh.isValid && found.mesh.rows == 45 && found.mesh.cols == 31)
        assertTrue("flat page scored ${found.bend}", found.bend < PageFlattening.BENT)
    }

    @Test
    fun theSamePageCurledIsFoundBentAndFlattened() {
        val photo = frame()
        val outline = outline(photo.width, photo.height)
        // 7 % of the page's height: the bulge stays inside the 5 % box the model reads.
        val amount = 0.07
        val curl = curled(photo, outline, amount)
        val found = PageFlattener.flatten(curl, outline)
        assertNotNull("the model did not run", found)
        assertTrue("curled page scored ${found!!.bend}", found.bend >= PageFlattening.BENT)

        // The mesh follows the curl itself: the middle of the page's bottom edge lies about as far
        // below the straight line between the bottom corners as the curl pushed it (at most
        // amount × the page box's height, where the bottom edge meets the curl's peak).
        val mesh = found.mesh
        val last = mesh.rows * mesh.cols - 1
        fun pixel(i: Int) = Point2(mesh.xs[i].toDouble() * photo.width, mesh.ys[i].toDouble() * photo.height)
        val left = pixel(last - mesh.cols + 1)
        val right = pixel(last)
        val middle = pixel(last - mesh.cols / 2)
        val chordY = left.y + (right.y - left.y) * (middle.x - left.x) / (right.x - left.x)
        val boxHeight = (outline.corners.maxOf { it.y } - outline.corners.minOf { it.y }) * photo.height
        val pushed = amount * boxHeight
        val sag = middle.y - chordY
        assertTrue("bottom edge sags ${"%.1f".format(sag)} px, the curl pushed it about ${"%.1f".format(pushed)} px", sag in 0.4 * pushed..1.6 * pushed)

        val flat = MeshWarpEngine.warp(curl, mesh, sourceRotation = 0, rotation = 90, maxLongEdge = 1200)
        assertNotNull("mesh warp failed", flat)
        // A portrait magazine page, turned a quarter: wider than tall, within the size asked for.
        assertTrue("${flat!!.width} × ${flat.height}", flat.width > flat.height && flat.width <= 1200 && flat.height >= 200)
    }
}
