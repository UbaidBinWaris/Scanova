package app.scanova.core.data.sync

import app.scanova.core.data.model.SyncState
import app.scanova.core.data.model.SyncState.FAILED
import app.scanova.core.data.model.SyncState.LOCAL_ONLY
import app.scanova.core.data.model.SyncState.PENDING_DELETE
import app.scanova.core.data.model.SyncState.SYNCED
import app.scanova.core.data.model.SyncState.UPLOADING
import app.scanova.core.data.sync.SyncEvent.CONTENT_CHANGED
import app.scanova.core.data.sync.SyncEvent.DELETE_REQUESTED
import app.scanova.core.data.sync.SyncEvent.RETRY_REQUESTED
import app.scanova.core.data.sync.SyncEvent.UPLOAD_DEFERRED
import app.scanova.core.data.sync.SyncEvent.UPLOAD_REJECTED
import app.scanova.core.data.sync.SyncEvent.UPLOAD_STARTED
import app.scanova.core.data.sync.SyncEvent.UPLOAD_SUCCEEDED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncStateMachineTest {

    /** The complete transition table. Every pair not listed here must be refused. */
    private val allowed: Map<Pair<SyncState, SyncEvent>, SyncState> = mapOf(
        (LOCAL_ONLY to UPLOAD_STARTED) to UPLOADING,
        (LOCAL_ONLY to DELETE_REQUESTED) to PENDING_DELETE,
        (UPLOADING to UPLOAD_STARTED) to UPLOADING,
        (UPLOADING to UPLOAD_SUCCEEDED) to SYNCED,
        (UPLOADING to UPLOAD_DEFERRED) to LOCAL_ONLY,
        (UPLOADING to UPLOAD_REJECTED) to FAILED,
        (UPLOADING to DELETE_REQUESTED) to PENDING_DELETE,
        (SYNCED to DELETE_REQUESTED) to PENDING_DELETE,
        (FAILED to RETRY_REQUESTED) to LOCAL_ONLY,
        (FAILED to DELETE_REQUESTED) to PENDING_DELETE,
        (LOCAL_ONLY to CONTENT_CHANGED) to LOCAL_ONLY,
        (UPLOADING to CONTENT_CHANGED) to LOCAL_ONLY,
        (SYNCED to CONTENT_CHANGED) to LOCAL_ONLY,
        (FAILED to CONTENT_CHANGED) to LOCAL_ONLY,
    )

    @Test
    fun `every state and event pair behaves as specified`() {
        for (state in SyncState.entries) {
            for (event in SyncEvent.entries) {
                assertEquals("$state + $event", allowed[state to event], SyncStateMachine.next(state, event))
            }
        }
    }

    @Test
    fun `the happy path goes local, uploading, synced`() {
        val uploading = SyncStateMachine.next(LOCAL_ONLY, UPLOAD_STARTED)!!
        val synced = SyncStateMachine.next(uploading, UPLOAD_SUCCEEDED)

        assertEquals(UPLOADING, uploading)
        assertEquals(SYNCED, synced)
    }

    @Test
    fun `a deferred upload goes back to the queue and can start again`() {
        val deferred = SyncStateMachine.next(UPLOADING, UPLOAD_DEFERRED)!!

        assertEquals(LOCAL_ONLY, deferred)
        assertEquals(UPLOADING, SyncStateMachine.next(deferred, UPLOAD_STARTED))
    }

    @Test
    fun `a rejected upload needs an explicit retry`() {
        val failed = SyncStateMachine.next(UPLOADING, UPLOAD_REJECTED)!!

        assertEquals(FAILED, failed)
        // The engine cannot pick a failed document up by itself.
        assertNull(SyncStateMachine.next(failed, UPLOAD_STARTED))
        assertEquals(LOCAL_ONLY, SyncStateMachine.next(failed, RETRY_REQUESTED))
    }

    @Test
    fun `an upload interrupted by process death is resumed`() {
        assertEquals(UPLOADING, SyncStateMachine.next(UPLOADING, UPLOAD_STARTED))
    }

    @Test
    fun `a deleted document can never come back`() {
        for (event in SyncEvent.entries) {
            assertNull("PENDING_DELETE + $event", SyncStateMachine.next(PENDING_DELETE, event))
        }
    }

    @Test
    fun `a synced document ignores stray upload results`() {
        assertNull(SyncStateMachine.next(SYNCED, UPLOAD_SUCCEEDED))
        assertNull(SyncStateMachine.next(SYNCED, UPLOAD_REJECTED))
        assertNull(SyncStateMachine.next(SYNCED, UPLOAD_STARTED))
    }

    @Test
    fun `an edited document goes back to the upload queue from any live state`() {
        for (state in SyncState.entries.filter { it != PENDING_DELETE }) {
            val edited = SyncStateMachine.next(state, CONTENT_CHANGED)
            assertEquals("$state + CONTENT_CHANGED", LOCAL_ONLY, edited)
            // …and the engine picks it up again on its own.
            assertEquals(UPLOADING, SyncStateMachine.next(edited!!, UPLOAD_STARTED))
        }
    }

    @Test
    fun `every state except pending delete can be deleted`() {
        for (state in SyncState.entries.filter { it != PENDING_DELETE }) {
            assertEquals(PENDING_DELETE, SyncStateMachine.next(state, DELETE_REQUESTED))
        }
    }
}
