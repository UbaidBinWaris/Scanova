package app.scanova.core.data.indexing

import app.scanova.core.common.log.ScanovaLog
import app.scanova.core.common.util.TimeSource
import app.scanova.core.data.sync.SyncScheduler
import app.scanova.core.database.dao.TextIndexDao
import app.scanova.core.ml.insight.DocumentInsights
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex

/** A page's current image as the indexer finds it in the encrypted store. */
internal sealed interface PageImage {
    class Found(val bytes: ByteArray) : PageImage

    /** Not on the phone (removed, or about to be replaced by a restore): nothing to read in it. */
    data object Missing : PageImage

    /** Could not be read right now (storage or keys): try again on a later run. */
    data object Unreadable : PageImage
}

/** What one recognition of a page image gave. */
internal sealed interface PageText {
    class Read(val text: String) : PageText

    /** The image is not a picture the phone can decode: it will never give text. */
    data object Undecodable : PageText

    /** Text recognition itself is not working right now. */
    data object RecognizerUnavailable : PageText
}

/** How a run of the indexer went. */
internal data class IndexReport(
    val pagesRead: Int,
    val documentsAnalysed: Int,
    val documentsChanged: Int,
    /** Work is left over (the run's budget ran out): schedule another run. */
    val moreWork: Boolean,
    /** Text recognition was unavailable: try the run again later. */
    val recognizerUnavailable: Boolean,
)

/**
 * Makes every document searchable and gives it a type and, while it still
 * has an automatic one, a title — on the phone, in the background.
 *
 * 1. **Text.** Pages whose text is empty and whose current image has not been
 *    read yet (imported PDFs, merged or split documents, documents restored
 *    from the backup) are read with on-device text recognition, which also
 *    finds text on sideways pages. The page's text is stored and the
 *    document's text (and so its search index) follows. Each page is written
 *    on its own, compare-and-set on its image, so an edit or a restore that
 *    lands meanwhile always wins, and an interrupted run resumes where it
 *    stopped. The sync lock is never taken: sync never waits for this.
 * 2. **Type and title.** Documents whose text the rules have not looked at
 *    yet get [DocumentInsights] applied through [IndexRules.analysed].
 *
 * Each run is bounded ([maxPages], [budget]); [IndexReport.moreWork] says when
 * another run is needed. Nothing leaves the phone except through the normal,
 * encrypted sync of titles and tags.
 */
internal class TextIndexer(
    private val dao: TextIndexDao,
    private val images: suspend (imageFile: String) -> PageImage,
    private val reader: suspend (image: ByteArray) -> PageText,
    private val syncScheduler: SyncScheduler,
    private val clock: TimeSource,
    private val maxPages: Int = MAX_PAGES_PER_RUN,
    private val maxDocuments: Int = MAX_DOCUMENTS_PER_RUN,
    private val budget: Duration = RUN_BUDGET,
) {
    private val running = Mutex()

    /**
     * [run], unless a run is already in progress (the periodic and the
     * requested run can be started together): then null, as that run reads
     * everything there is.
     */
    suspend fun runExclusive(): IndexReport? {
        if (!running.tryLock()) return null
        return try {
            run()
        } finally {
            running.unlock()
        }
    }

    suspend fun run(): IndexReport {
        val deadline = clock.now().plus(budget)
        var pagesRead = 0
        // Pages looked at, read or not: what the run's budget counts.
        var handled = 0
        var moreWork = false
        var recognizerUnavailable = false
        val skipped = HashSet<String>()

        // Pages first, in batches, until nothing is left or the budget is spent; documents
        // saved while this runs are picked up by the next batch.
        pages@ while (true) {
            val batch = dao.pagesNeedingText(BATCH + skipped.size).filter { it.id !in skipped }.take(BATCH)
            if (batch.isEmpty()) break
            for (page in batch) {
                if (handled >= maxPages || !clock.now().isBefore(deadline)) {
                    moreWork = true
                    break@pages
                }
                handled++
                val document = dao.document(page.documentId)
                if (document != null && IndexRules.hasTextFromElsewhere(document, dao.pages(page.documentId))) {
                    // The server's text stays the document's text; nothing to read here.
                    dao.recordPageText(page.id, page.imageFile, "", IndexRules::withPageText)
                    continue
                }
                val text = when (val image = images(page.imageFile)) {
                    PageImage.Missing -> ""
                    PageImage.Unreadable -> {
                        skipped += page.id
                        continue
                    }
                    is PageImage.Found -> when (val read = reader(image.bytes)) {
                        is PageText.Read -> read.text
                        PageText.Undecodable -> ""
                        PageText.RecognizerUnavailable -> {
                            recognizerUnavailable = true
                            break@pages
                        }
                    }
                }
                dao.recordPageText(page.id, page.imageFile, text, IndexRules::withPageText)
                pagesRead++
            }
        }
        if (skipped.isNotEmpty()) {
            ScanovaLog.warn(TAG, "page_images_unreadable count=${skipped.size}")
            moreWork = true
        }

        // Then type and title, for documents whose pages all have their text by now.
        var analysed = 0
        var changed = 0
        var syncNeeded = false
        val documents = dao.documentsToAnalyse(IndexRules.ANALYSIS_VERSION, maxDocuments + 1)
        if (documents.size > maxDocuments) moreWork = true
        for (document in documents.take(maxDocuments)) {
            val insight = try {
                DocumentInsights.analyze(document.ocrText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // A rule that trips over some text must not stop the others; the document is retried next run.
                ScanovaLog.warn(TAG, "analysis_failed", e)
                continue
            }
            val stored = dao.modifyDocument(document.id) { current ->
                // Text changed meanwhile, or analysed elsewhere: the next run looks again.
                if (current.ocrText != document.ocrText || current.analysisVersion >= IndexRules.ANALYSIS_VERSION) {
                    null
                } else {
                    IndexRules.analysed(current, insight, clock.now().toEpochMilli())
                }
            } ?: continue
            analysed++
            if (stored.title != document.title || stored.tags != document.tags) {
                changed++
                if (stored.metadataDirty) syncNeeded = true
            }
        }
        if (syncNeeded) syncScheduler.requestSync()
        return IndexReport(pagesRead, analysed, changed, moreWork && !recognizerUnavailable, recognizerUnavailable)
    }

    private companion object {
        const val TAG = "TextIndexer"
        const val BATCH = 10
        const val MAX_PAGES_PER_RUN = 40
        const val MAX_DOCUMENTS_PER_RUN = 50
        val RUN_BUDGET: Duration = Duration.ofMinutes(3)
    }
}
