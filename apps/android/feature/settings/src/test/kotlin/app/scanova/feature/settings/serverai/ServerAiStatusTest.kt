package app.scanova.feature.settings.serverai

import app.scanova.core.data.serverai.ServerAiState
import app.scanova.core.data.serverai.ServerAiStatus
import app.scanova.feature.settings.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAiStatusTest {

    private fun status(state: ServerAiState, on: Boolean) = ServerAiStatus(
        state = state,
        on = on,
        autoOffAt = null,
        autoOffMinutes = 15,
        heartbeatSeconds = 60,
        availableBytes = null,
        neededBytes = null,
    )

    @Test
    fun `ready says so, with the ready mark`() {
        assertEquals(
            StatusLine(R.string.feature_settings_server_ai_ready, StatusTone.READY),
            status(ServerAiState.READY, on = true).line(),
        )
    }

    @Test
    fun `loading and unloading show progress`() {
        assertEquals(StatusTone.BUSY, status(ServerAiState.LOADING, on = true).line().tone)
        assertEquals(StatusTone.BUSY, status(ServerAiState.UNLOADING, on = false).line().tone)
    }

    @Test
    fun `problems are marked as problems, not only by colour`() {
        for (state in listOf(ServerAiState.FAILED, ServerAiState.NOT_INSTALLED, ServerAiState.UNAVAILABLE, ServerAiState.UNKNOWN)) {
            assertEquals(state.name, StatusTone.PROBLEM, status(state, on = false).line().tone)
        }
    }

    @Test
    fun `turning off always works, so the memory can always be freed`() {
        for (state in ServerAiState.entries) {
            assertTrue(state.name, status(state, on = true).switchEnabled(switching = false))
        }
    }

    @Test
    fun `there is nothing to turn on without a model, or while it is still unloading`() {
        assertFalse(status(ServerAiState.NOT_INSTALLED, on = false).switchEnabled(switching = false))
        assertFalse(status(ServerAiState.DISABLED, on = false).switchEnabled(switching = false))
        assertFalse(status(ServerAiState.UNLOADING, on = false).switchEnabled(switching = false))
        assertTrue(status(ServerAiState.OFF, on = false).switchEnabled(switching = false))
        assertTrue(status(ServerAiState.UNAVAILABLE, on = false).switchEnabled(switching = false))
    }

    @Test
    fun `the switch waits for the answer to the last flip`() {
        assertFalse(status(ServerAiState.OFF, on = false).switchEnabled(switching = true))
        assertFalse(status(ServerAiState.READY, on = true).switchEnabled(switching = true))
    }
}
