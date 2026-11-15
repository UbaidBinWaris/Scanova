package app.scanova.feature.scan.idcard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap

/**
 * Puts the two flattened sides of a card on one white A4 page: front in the
 * upper half, back in the lower half, each centred at the card's real size
 * ([IdCardLayout]).
 *
 * The result is the page's *original*: it is stored as such, with a recipe
 * whose crop is null (the whole composed page), so the page can be filtered,
 * rotated and cropped again later like any other. The two raw camera photos
 * are not kept.
 */
internal object IdCardComposer {
    /** Long side of the composed page: the same as a scanned page (about 205 dpi on A4). */
    const val PAGE_LONG_EDGE = 2400

    /** Long side each side is flattened to before it is placed (a true-size card is about 700 px wide). */
    const val SIDE_LONG_EDGE = 1200

    fun compose(front: Bitmap, back: Bitmap): Bitmap {
        val size = IdCardLayout.pageSize(PAGE_LONG_EDGE)
        val page = createBitmap(size.width, size.height)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        listOf(front to true, back to false).forEach { (side, upper) ->
            val slot = IdCardLayout.place(size.width, size.height, IdCardLayout.aspectOf(side.width, side.height), upper)
            val target = RectF(
                slot.left.toFloat(),
                slot.top.toFloat(),
                (slot.left + slot.width).toFloat(),
                (slot.top + slot.height).toFloat(),
            )
            canvas.drawBitmap(side, null, target, paint)
        }
        return page
    }
}
