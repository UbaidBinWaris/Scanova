package app.scanova.feature.documents.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.documents.DocumentRepository
import app.scanova.core.data.model.Document
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.withContext

/**
 * Small first-page previews for Home and Files, decrypted and decoded off the
 * main thread, kept in one memory cache the two tabs share (so switching tabs
 * never decodes a cover twice). Nothing is written to storage.
 *
 * A cover is cached per document version ([Document.updatedAt]), so a page
 * edited in the editor shows its new look on the next visit.
 */
@Singleton
class DocumentThumbnails @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val dispatchers: AppDispatchers,
) {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** The first page, small, or null for a document without page images on this phone. */
    suspend fun cover(document: Document): Bitmap? {
        val key = "${document.id}@${document.updatedAt.toEpochMilli()}"
        cache.get(key)?.let { return it }
        val jpeg = (documentRepository.loadCoverImage(document.id) as? AppResult.Success)?.value ?: return null
        val bitmap = withContext(dispatchers.default) {
            try {
                decodeSmall(jpeg)
            } catch (e: OutOfMemoryError) {
                null
            }
        } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun decodeSmall(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_LONG_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // Half the memory of ARGB; a thumbnail of paper shows no banding.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    }

    private companion object {
        const val COVER_LONG_EDGE = 320
        const val CACHE_BYTES = 16 * 1024 * 1024
    }
}
