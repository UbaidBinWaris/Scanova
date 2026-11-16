package app.scanova.feature.scan.intake

import android.graphics.Bitmap
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.model.PageEdit
import app.scanova.core.data.model.PageFilters
import app.scanova.core.data.scan.ScanRepository
import app.scanova.core.data.settings.AppSettings
import app.scanova.feature.scan.filter.FilterCatalogue
import app.scanova.feature.scan.geometry.Quad
import app.scanova.feature.scan.imaging.Bitmaps
import app.scanova.feature.scan.render.PageLook
import app.scanova.feature.scan.render.PageRecipeRenderer
import app.scanova.feature.scan.render.RecipeGeometry
import app.scanova.feature.scan.session.DraftPage
import app.scanova.feature.scan.session.PageProcessor
import app.scanova.feature.scan.session.ScanSession
import javax.inject.Inject

/**
 * Brings a new page into the scan in progress, the same way for a camera
 * capture and for a photo imported from the gallery: its original and its
 * upright working copy are stored encrypted, a quick preview is made, the
 * page joins the [ScanSession] and is queued for rendering ([PageProcessor]).
 */
internal class PageIntake @Inject constructor(
    private val session: ScanSession,
    private val processor: PageProcessor,
    private val scanRepository: ScanRepository,
    private val renderer: PageRecipeRenderer,
) {
    fun newPageId(): String = scanRepository.newPageId()

    /**
     * Stores page [pageId]'s untouched [original] and its upright, reduced
     * [working] copy (both JPEG, both encrypted). When either fails, nothing
     * of the page is left behind and false is returned.
     */
    suspend fun store(pageId: String, original: ByteArray, working: ByteArray): Boolean {
        val stored = scanRepository.storeOriginal(pageId, original) is AppResult.Success &&
            scanRepository.storeCapture(pageId, working) is AppResult.Success
        if (!stored) scanRepository.discardPage(pageId)
        return stored
    }

    /** Adds [page] at the end (or in place of the page being retaken) and queues it for rendering. */
    suspend fun add(page: DraftPage) {
        val replaced = session.addOrReplace(page)
        replaced?.let { scanRepository.discardPage(it) }
        processor.enqueue(page.id)
    }

    /** Takes page [pageId] out of the scan again and deletes its files. */
    suspend fun remove(pageId: String) {
        session.remove(pageId)
        scanRepository.discardPage(pageId)
    }

    /** Deletes the files of a page that never made it into the scan. */
    suspend fun discard(pageId: String) = scanRepository.discardPage(pageId)

    /** A small flattened, unfiltered image of the page ([outline] null: the whole picture). */
    fun quickPreview(upright: Bitmap, outline: Quad?): Bitmap {
        val small = Bitmaps.thumbnail(upright, QUICK_SOURCE_LONG_EDGE)
        try {
            return renderer.geometry(small, PageEdit(crop = RecipeGeometry.toCrop(outline)), QUICK_PREVIEW_LONG_EDGE)
        } finally {
            small.recycle()
        }
    }

    /** The look the next page starts with ([NewPageLook]), following the page before it. */
    fun newPageLook(settings: AppSettings): PageLook =
        NewPageLook.choose(settings.autoEnhance, settings.defaultFilter, previous = session.pages.value.lastOrNull()?.look)

    companion object {
        /** JPEG quality of the working copy a scan renders from. */
        const val WORKING_JPEG_QUALITY = 92

        private const val QUICK_SOURCE_LONG_EDGE = 720
        private const val QUICK_PREVIEW_LONG_EDGE = 360
    }
}

/**
 * The look a new page starts with: with auto-enhance on, the look of the
 * previous page (pages of one document usually want the same) or the default
 * filter; with it off, the original.
 */
internal object NewPageLook {
    fun choose(autoEnhance: Boolean, defaultFilter: String?, previous: PageLook?): PageLook {
        if (!autoEnhance) return PageLook(PageFilters.ORIGINAL)
        if (previous != null && previous.filterId != PageFilters.ORIGINAL) return previous
        val default = defaultFilter.takeIf { FilterCatalogue.isKnown(it) && it != PageFilters.ORIGINAL }
        return PageLook(default ?: PageFilters.ENHANCED)
    }
}
