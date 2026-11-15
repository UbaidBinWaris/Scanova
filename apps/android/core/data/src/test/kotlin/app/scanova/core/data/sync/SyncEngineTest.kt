package app.scanova.core.data.sync

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.model.SyncState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    private val api = FakeSyncApi()

    private fun engine(store: FakeSyncStore, vararg files: String) = SyncEngine(store, api, FakeSyncFiles(*files))

    // ── Push ────────────────────────────────────────────────────────────

    @Test
    fun `a new document is created, uploaded and marked synced`() = runTest {
        val store = FakeSyncStore(localDocument("a"))

        val report = engine(store, "pdf/a.pdf").run()

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(listOf("create a", "upload a", "list cursor=null since=null"), api.calls)
        assertEquals(SyncState.SYNCED, store.state("a"))
        assertTrue(store.documents.getValue("a").remoteCreated)
        assertTrue(store.documents.getValue("a").fileUploaded)
        assertEquals("recognised text of a", api.createdOcrText["a"])
        assertEquals("%PDF-1.7 content of pdf/a.pdf", api.uploadedBytes.getValue("a").toString(Charsets.UTF_8))
    }

    @Test
    fun `blank OCR text is not sent and long text is cut to one mebibyte`() = runTest {
        val long = "é".repeat(700_000) // 1.4 MB as UTF-8
        val store = FakeSyncStore(localDocument("blank", ocrText = "  "), localDocument("long", ocrText = long))

        engine(store, "pdf/blank.pdf", "pdf/long.pdf").run()

        assertNull(api.createdOcrText["blank"])
        val sent = api.createdOcrText.getValue("long")!!
        assertEquals(1024 * 1024, sent.toByteArray(Charsets.UTF_8).size)
        assertTrue(long.startsWith(sent))
    }

    @Test
    fun `an upload interrupted after the create step does not create again`() = runTest {
        // The app died after POST /documents succeeded: the row says UPLOADING + remoteCreated.
        val store = FakeSyncStore(localDocument("a", state = SyncState.UPLOADING, remoteCreated = true))

        engine(store, "pdf/a.pdf").run()

        assertEquals(listOf("upload a", "list cursor=null since=null"), api.calls)
        assertEquals(SyncState.SYNCED, store.state("a"))
    }

    @Test
    fun `being offline defers the upload and asks for a retry`() = runTest {
        val store = FakeSyncStore(localDocument("a"), localDocument("b"))
        api.createResult = { AppResult.Failure(AppError.NoConnection) }

        val report = engine(store, "pdf/a.pdf", "pdf/b.pdf").run()

        assertEquals(SyncReport(SyncOutcome.RETRY_LATER, AppError.NoConnection), report)
        // Stopped at the first document: no point hammering an unreachable server.
        assertEquals(listOf("create a"), api.calls)
        assertEquals(SyncState.LOCAL_ONLY, store.state("a"))
        assertEquals(SyncState.LOCAL_ONLY, store.state("b"))
        assertEquals(1, store.attempts["a"])
        assertNull(store.documents.getValue("a").lastErrorCode)
    }

    @Test
    fun `server trouble during the file upload keeps the created flag for the next run`() = runTest {
        val store = FakeSyncStore(localDocument("a"))
        api.uploadResult = { api(ApiErrorCode.SERVICE_UNAVAILABLE, 503) }

        val first = engine(store, "pdf/a.pdf").run()

        assertEquals(SyncOutcome.RETRY_LATER, first.outcome)
        assertEquals(SyncState.LOCAL_ONLY, store.state("a"))
        assertTrue(store.documents.getValue("a").remoteCreated)

        api.calls.clear()
        api.uploadResult = { AppResult.Success(remoteDocument(it)) }
        val second = engine(store, "pdf/a.pdf").run()

        assertEquals(SyncOutcome.COMPLETE, second.outcome)
        assertEquals(listOf("upload a", "list cursor=null since=null"), api.calls)
        assertEquals(SyncState.SYNCED, store.state("a"))
    }

    @Test
    fun `a document the server rejects is marked failed and does not block the others`() = runTest {
        val store = FakeSyncStore(localDocument("big"), localDocument("ok"))
        api.uploadResult = { id -> if (id == "big") api(ApiErrorCode.QUOTA_EXCEEDED, 413) else AppResult.Success(remoteDocument(id)) }

        val report = engine(store, "pdf/big.pdf", "pdf/ok.pdf").run()

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(SyncState.FAILED, store.state("big"))
        assertEquals("API/QUOTA_EXCEEDED/413", store.documents.getValue("big").lastErrorCode)
        assertEquals(SyncState.SYNCED, store.state("ok"))
    }

    @Test
    fun `a failed document is left alone until the user retries`() = runTest {
        val store = FakeSyncStore(localDocument("a", state = SyncState.FAILED, remoteCreated = true))

        engine(store, "pdf/a.pdf").run()
        assertEquals(listOf("list cursor=null since=null"), api.calls)
        assertEquals(SyncState.FAILED, store.state("a"))

        // RETRY_REQUESTED puts it back in the queue.
        store.documents["a"] = store.documents.getValue("a").copy(state = SyncStateMachine.next(SyncState.FAILED, SyncEvent.RETRY_REQUESTED)!!)
        api.calls.clear()
        engine(store, "pdf/a.pdf").run()

        assertEquals(listOf("upload a", "list cursor=null since=null"), api.calls)
        assertEquals(SyncState.SYNCED, store.state("a"))
    }

    @Test
    fun `a missing local file is a failure of that document, not of the run`() = runTest {
        val store = FakeSyncStore(localDocument("a"))

        val report = engine(store /* no files */).run()

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(SyncState.FAILED, store.state("a"))
        assertEquals("LOCAL_STORAGE", store.documents.getValue("a").lastErrorCode)
        assertFalse("upload a" in api.calls)
    }

    @Test
    fun `an expired session stops the run without marking documents failed`() = runTest {
        val store = FakeSyncStore(localDocument("a"), localDocument("b"))
        api.createResult = { api(ApiErrorCode.UNAUTHENTICATED, 401) }

        val report = engine(store, "pdf/a.pdf", "pdf/b.pdf").run()

        assertEquals(SyncOutcome.AUTH_REQUIRED, report.outcome)
        assertEquals(listOf("create a"), api.calls)
        assertEquals(SyncState.LOCAL_ONLY, store.state("a"))
        assertEquals(0, store.attempts["a"] ?: 0)
    }

    @Test
    fun `a forced password change stops the run`() = runTest {
        val store = FakeSyncStore(localDocument("a"))
        api.createResult = { api(ApiErrorCode.PASSWORD_CHANGE_REQUIRED, 403) }

        assertEquals(SyncOutcome.AUTH_REQUIRED, engine(store, "pdf/a.pdf").run().outcome)
        assertEquals(SyncState.LOCAL_ONLY, store.state("a"))
    }

    @Test
    fun `a rename made while the create request was in flight is sent afterwards`() = runTest {
        val store = FakeSyncStore(localDocument("a", title = "Old title"))
        // The user renames the document after the server already received the old title.
        api.afterCreate = { id -> store.documents[id] = store.documents.getValue(id).copy(title = "New title") }

        engine(store, "pdf/a.pdf").run()

        assertEquals(listOf("create a", "upload a", "patch a", "list cursor=null since=null"), api.calls)
        assertEquals("New title" to emptyList<String>(), api.patched["a"])
        assertEquals(SyncState.SYNCED, store.state("a"))
        assertFalse(store.documents.getValue("a").metadataDirty)
    }

    @Test
    fun `an edit to a synced document is sent as an update`() = runTest {
        val store = FakeSyncStore(
            localDocument("a", state = SyncState.SYNCED, title = "Renamed", tags = listOf("tax"), remoteCreated = true, fileUploaded = true, metadataDirty = true),
        )

        engine(store).run()

        assertEquals("Renamed" to listOf("tax"), api.patched["a"])
        assertFalse(store.documents.getValue("a").metadataDirty)
        assertEquals(SyncState.SYNCED, store.state("a"))
    }

    @Test
    fun `an edit to a document deleted elsewhere removes it here`() = runTest {
        val store = FakeSyncStore(
            localDocument("a", state = SyncState.SYNCED, remoteCreated = true, fileUploaded = true, metadataDirty = true),
        )
        api.patchResult = { api(ApiErrorCode.NOT_FOUND, 404) }

        engine(store).run()

        assertEquals(listOf("a"), store.removed)
    }

    // ── Deletes ─────────────────────────────────────────────────────────

    @Test
    fun `a pending delete is confirmed by the server and then removed`() = runTest {
        val store = FakeSyncStore(localDocument("a", state = SyncState.PENDING_DELETE, remoteCreated = true, fileUploaded = true))

        engine(store).run()

        assertEquals(listOf("delete a", "list cursor=null since=null"), api.calls)
        assertEquals(listOf("a"), store.removed)
    }

    @Test
    fun `a document already gone on the server counts as deleted`() = runTest {
        val store = FakeSyncStore(localDocument("a", state = SyncState.PENDING_DELETE, remoteCreated = true))
        api.deleteResult = { api(ApiErrorCode.NOT_FOUND, 404) }

        engine(store).run()

        assertEquals(listOf("a"), store.removed)
    }

    @Test
    fun `a delete that cannot reach the server stays pending`() = runTest {
        val store = FakeSyncStore(localDocument("a", state = SyncState.PENDING_DELETE, remoteCreated = true))
        api.deleteResult = { AppResult.Failure(AppError.NoConnection) }

        val report = engine(store).run()

        assertEquals(SyncOutcome.RETRY_LATER, report.outcome)
        assertEquals(SyncState.PENDING_DELETE, store.state("a"))
        assertTrue(store.removed.isEmpty())
    }

    @Test
    fun `a pending delete the server never saw is removed without a request`() = runTest {
        val store = FakeSyncStore(localDocument("a", state = SyncState.PENDING_DELETE, remoteCreated = false))

        engine(store).run()

        assertFalse("delete a" in api.calls)
        assertEquals(listOf("a"), store.removed)
    }

    @Test
    fun `deleting during an upload wins over the upload's result`() = runTest {
        val store = FakeSyncStore(localDocument("a"))
        // The user deletes the document while POST /documents is in flight.
        api.afterCreate = { id ->
            store.documents[id] = store.documents.getValue(id)
                .copy(state = SyncStateMachine.next(SyncState.UPLOADING, SyncEvent.DELETE_REQUESTED)!!)
        }

        engine(store /* files were deleted with the document */).run()

        // The late upload result must not resurrect it…
        assertEquals(SyncState.PENDING_DELETE, store.state("a"))
        // …and the create that did reach the server is remembered, so the next run deletes it there.
        assertTrue(store.documents.getValue("a").remoteCreated)

        api.calls.clear()
        engine(store).run()
        assertEquals(listOf("delete a", "list cursor=null since=null"), api.calls)
        assertEquals(listOf("a"), store.removed)
    }

    // ── Pull ────────────────────────────────────────────────────────────

    @Test
    fun `pull adds documents from other devices and advances the cursor`() = runTest {
        val store = FakeSyncStore()
        api.listPages = mutableListOf(
            AppResult.Success(RemotePage(listOf(remoteDocument("r1", updatedAt = "2026-11-10T09:05:00.000Z")), "page-2")),
            AppResult.Success(RemotePage(listOf(remoteDocument("r2", updatedAt = "2026-11-12T08:00:00.000Z", ocrStatus = "DONE")), null)),
        )
        api.textResult = { AppResult.Success("text recognised by the server") }

        val report = engine(store).run()

        assertEquals(SyncOutcome.COMPLETE, report.outcome)
        assertEquals(
            listOf("list cursor=null since=null", "list cursor=page-2 since=null", "text r2"),
            api.calls,
        )
        assertEquals(setOf("r1", "r2"), store.documents.keys)
        assertEquals(SyncState.SYNCED, store.state("r1"))
        assertNull(store.documents.getValue("r1").pdfFile)
        assertEquals("text recognised by the server", store.documents.getValue("r2").ocrText)
        assertEquals("2026-11-12T08:00:00.000Z", store.cursor)
    }

    @Test
    fun `the next pull asks only for changes since the cursor`() = runTest {
        val store = FakeSyncStore().apply { cursor = "2026-11-12T08:00:00.000Z" }

        engine(store).run()

        assertEquals(listOf("list cursor=null since=2026-11-12T08:00:00.000Z"), api.calls)
        assertEquals("2026-11-12T08:00:00.000Z", store.cursor)
    }

    @Test
    fun `a failed pull keeps the old cursor so nothing is skipped`() = runTest {
        val store = FakeSyncStore().apply { cursor = "2026-11-01T00:00:00.000Z" }
        api.listPages = mutableListOf(
            AppResult.Success(RemotePage(listOf(remoteDocument("r1", updatedAt = "2026-11-10T09:05:00.000Z")), "page-2")),
            AppResult.Failure(AppError.NoConnection),
        )

        val report = engine(store).run()

        assertEquals(SyncReport(SyncOutcome.RETRY_LATER, AppError.NoConnection), report)
        assertEquals("2026-11-01T00:00:00.000Z", store.cursor)
    }

    @Test
    fun `pull removes documents deleted on another device`() = runTest {
        val store = FakeSyncStore(localDocument("a", state = SyncState.SYNCED, remoteCreated = true, fileUploaded = true))
        api.listPages = mutableListOf(AppResult.Success(RemotePage(listOf(remoteDocument("a", deleted = true)), null)))

        engine(store).run()

        assertEquals(listOf("a"), store.removed)
    }

    @Test
    fun `pull does not overwrite local work that is not pushed yet`() = runTest {
        val store = FakeSyncStore(
            localDocument("dirty", state = SyncState.SYNCED, title = "My title", remoteCreated = true, fileUploaded = true, metadataDirty = true),
            localDocument("failed", state = SyncState.FAILED, title = "Local", remoteCreated = true),
        )
        api.patchResult = { AppResult.Failure(AppError.NoConnection) }
        // Skip the push phase's early exit by making the pull the only thing that runs.
        val pullOnly = SyncEngine(
            object : SyncStore by store {
                override suspend fun pendingPush(): List<SyncDocument> = emptyList()
            },
            api,
            FakeSyncFiles(),
        )
        api.listPages = mutableListOf(
            AppResult.Success(
                RemotePage(listOf(remoteDocument("dirty", title = "Server title"), remoteDocument("failed", title = "Server")), null),
            ),
        )

        pullOnly.run()

        assertEquals("My title", store.documents.getValue("dirty").title)
        assertEquals("Local", store.documents.getValue("failed").title)
        assertEquals(SyncState.FAILED, store.state("failed"))
    }

    @Test
    fun `pull takes the server's title for a clean synced document`() = runTest {
        val store = FakeSyncStore(localDocument("a", state = SyncState.SYNCED, title = "Old", remoteCreated = true, fileUploaded = true))
        api.listPages = mutableListOf(AppResult.Success(RemotePage(listOf(remoteDocument("a", title = "Renamed on the web")), null)))

        engine(store).run()

        assertEquals("Renamed on the web", store.documents.getValue("a").title)
    }

    @Test
    fun `documents another device is still uploading are not shown yet`() = runTest {
        val store = FakeSyncStore()
        api.listPages = mutableListOf(AppResult.Success(RemotePage(listOf(remoteDocument("r1", ready = false)), null)))

        engine(store).run()

        assertTrue(store.documents.isEmpty())
    }
}
