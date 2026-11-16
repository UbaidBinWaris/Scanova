package app.scanova.feature.scan.cleanup

import android.graphics.Bitmap
import android.graphics.Matrix
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.serverai.PageCleanupRepository
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.render.GeometryKey
import app.scanova.feature.scan.render.geometryKey
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Makes the cleaned layer of a page with the Server clean-up look
 * ([ServerCleanup][app.scanova.feature.scan.filter.ServerCleanup]): the
 * flattened page goes to the owner's server ([PageCleanupRepository]) and the
 * answer is kept in [CleanedPages], so a page is cleaned once per geometry and
 * every later look change reuses it.
 *
 * Asked before rendering, never by the renderer itself: the scan's page
 * processor and the editor get the layer here and hand it to the renderer. A
 * page is only ever sent while this account can clean pages ([available]).
 */
internal class PageCleaner @Inject constructor(
    private val repository: PageCleanupRepository,
    private val pages: CleanedPages,
    private val dispatchers: AppDispatchers,
) {
    /** True while this account can clean pages: an owner whose "Page clean-up" switch is on and ready. */
    val available: Flow<Boolean> get() = repository.available

    /**
     * The cleaned layer of page [pageId] for [edit]'s geometry, at most
     * [maxLongEdge] on its longer side: the one kept, or else a new one — then
     * [flat] makes the page's geometry without a look (a new bitmap, recycled
     * here), which is sent to the server (about 7 s for a full page).
     *
     * Returns a new bitmap the caller recycles, or null when the page's look
     * is not Server clean-up, or clean-up is not available or failed (switched
     * off, no connection, server error, out of memory): the page then renders
     * with the stand-in look. Never retries on its own.
     */
    suspend fun layer(pageId: String, edit: PageEdit, maxLongEdge: Int, flat: () -> Bitmap?): Bitmap? {
        val geometry = edit.geometryKey
        if (!pages.needsCleaning(pageId, edit)) {
            val kept = pages.get(pageId, geometry) ?: return null
            return decode(kept, maxLongEdge)
        }
        // Nothing is sent unless this account can clean pages right now.
        if (!repository.available.first()) return null
        val upload = withContext(dispatchers.default) {
            val page = try {
                flat()
            } catch (e: OutOfMemoryError) {
                null
            } ?: return@withContext null
            try {
                Bitmaps.toJpeg(page, UPLOAD_JPEG_QUALITY)
            } catch (e: OutOfMemoryError) {
                null
            } finally {
                page.recycle()
            }
        } ?: return null
        val cleaned = when (val answer = repository.clean(upload)) {
            is AppResult.Success -> answer.value
            is AppResult.Failure -> {
                ScanovaLog.warn(TAG, "cleanup_failed_${answer.error.kind}")
                return null
            }
        }
        val image = decode(cleaned, maxLongEdge)
        if (image == null) {
            ScanovaLog.warn(TAG, "cleanup_unreadable")
            return null
        }
        pages.put(pageId, geometry, cleaned)
        return image
    }

    /**
     * Keeps [image] (page [pageId]'s cleaned layer) turned [degrees] clockwise
     * as its layer for [geometry]: when text recognition turns a page upright,
     * the cleaned layer turns with it instead of being made again. [image] is
     * left alone; nothing is kept when memory is short.
     */
    suspend fun keepTurned(pageId: String, geometry: GeometryKey, image: Bitmap, degrees: Int) {
        val jpeg = withContext(dispatchers.default) {
            try {
                val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                val turned = Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
                try {
                    Bitmaps.toJpeg(turned, UPLOAD_JPEG_QUALITY)
                } finally {
                    if (turned !== image) turned.recycle()
                }
            } catch (e: OutOfMemoryError) {
                null
            }
        } ?: return
        pages.put(pageId, geometry, jpeg)
    }

    /** Drops what is kept for [pageId] (it was deleted while being cleaned). */
    fun forget(pageId: String) = pages.forget(pageId)

    private suspend fun decode(jpeg: ByteArray, maxLongEdge: Int): Bitmap? =
        withContext(dispatchers.default) { Bitmaps.decodeSampled(jpeg, maxLongEdge) }

    /** What went wrong, for the log: an error code or kind, never content. */
    private val AppError.kind: String
        get() = when (this) {
            is AppError.Api -> code.name.lowercase()
            else -> javaClass.simpleName.lowercase()
        }

    private companion object {
        const val TAG = "PageCleaner"

        /** The flattened page as sent: the server answers with a page of the same size. */
        const val UPLOAD_JPEG_QUALITY = 92
    }
}
