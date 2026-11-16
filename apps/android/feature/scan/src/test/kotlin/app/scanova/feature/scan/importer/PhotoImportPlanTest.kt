package app.scanova.feature.scan.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoImportPlanTest {

    @Test
    fun `photos are taken in the order picked, each once`() {
        val plan = PhotoImportPlan.of(listOf("content://a", "content://b", "content://a", " ", "content://c"))
        assertEquals(listOf("content://a", "content://b", "content://c"), plan.photos)
        assertEquals(0, plan.overLimit)
    }

    @Test
    fun `photos over the limit are counted, not dropped silently`() {
        val picked = (1..7).map { "content://photo/$it" }
        val plan = PhotoImportPlan.of(picked, limit = 5)
        assertEquals(picked.take(5), plan.photos)
        assertEquals(2, plan.overLimit)
    }

    @Test
    fun `every photo added goes straight on to the review`() {
        assertEquals(PhotoImportOutcome.AllAdded, PhotoImportOutcome.of(added = 3, problems = emptyList(), overLimit = 0))
    }

    @Test
    fun `some photos added are summarised before the review`() {
        val problems = listOf(
            PhotoProblem("a.heic", PhotoProblemKind.UNREADABLE),
            PhotoProblem(null, PhotoProblemKind.STORAGE),
            PhotoProblem("big.png", PhotoProblemKind.TOO_LARGE),
            PhotoProblem(null, PhotoProblemKind.UNREADABLE),
            PhotoProblem("b.jpg", PhotoProblemKind.UNREADABLE),
        )
        val outcome = PhotoImportOutcome.of(added = 2, problems = problems, overLimit = 0)
        assertEquals(
            PhotoImportOutcome.SomeAdded(
                added = 2,
                problems = listOf(
                    PhotoProblemGroup(PhotoProblemKind.UNREADABLE, names = listOf("a.heic", "b.jpg"), unnamed = 1),
                    PhotoProblemGroup(PhotoProblemKind.TOO_LARGE, names = listOf("big.png"), unnamed = 0),
                    PhotoProblemGroup(PhotoProblemKind.STORAGE, names = emptyList(), unnamed = 1),
                ),
                overLimit = 0,
            ),
            outcome,
        )
        assertEquals(3, (outcome as PhotoImportOutcome.SomeAdded).problems.first().count)
    }

    @Test
    fun `photos left out over the limit are reported even when the rest worked`() {
        assertEquals(
            PhotoImportOutcome.SomeAdded(added = 100, problems = emptyList(), overLimit = 4),
            PhotoImportOutcome.of(added = 100, problems = emptyList(), overLimit = 4),
        )
    }

    @Test
    fun `nothing added is a failure with the reasons`() {
        val outcome = PhotoImportOutcome.of(added = 0, problems = listOf(PhotoProblem("x.tif", PhotoProblemKind.UNREADABLE)), overLimit = 0)
        assertEquals(
            PhotoImportOutcome.NoneAdded(listOf(PhotoProblemGroup(PhotoProblemKind.UNREADABLE, listOf("x.tif"), 0))),
            outcome,
        )
    }

    @Test
    fun `leaving goes to the review only when the scan has pages`() {
        assertEquals(PhotoImportExit.REVIEW, PhotoImportExit.leaving(pagesInScan = 1))
        assertEquals(PhotoImportExit.CLOSE, PhotoImportExit.leaving(pagesInScan = 0))
    }
}
