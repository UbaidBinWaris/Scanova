package app.scanova.feature.settings.serverai

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.serverai.ServerAiAccess
import app.scanova.core.data.serverai.ServerAiGroup
import app.scanova.core.data.serverai.ServerAiRepository
import app.scanova.core.data.serverai.ServerAiState
import app.scanova.core.data.serverai.ServerAiStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerAiViewModelTest {

    /** The repository: [access] is set by the test, each flip waits until the test answers it. */
    private class FakeServerAi : ServerAiRepository {
        val state = MutableStateFlow<ServerAiAccess>(
            ServerAiAccess.Owner(status(ServerAiState.OFF), cleanup = status(ServerAiState.OFF)),
        )
        override val access: StateFlow<ServerAiAccess> = state
        val flips = mutableListOf<Pair<ServerAiGroup, Boolean>>()
        private val answers = mutableMapOf<ServerAiGroup, CompletableDeferred<AppResult<ServerAiStatus>>>()

        fun answer(group: ServerAiGroup, result: AppResult<ServerAiStatus>) {
            answers.getValue(group).complete(result)
        }

        override suspend fun refresh(): AppResult<ServerAiAccess> = AppResult.Success(state.value)

        override suspend fun setOn(on: Boolean, group: ServerAiGroup): AppResult<ServerAiStatus> {
            flips += group to on
            return CompletableDeferred<AppResult<ServerAiStatus>>().also { answers[group] = it }.await()
        }

        override suspend fun keepAliveWhileVisible() = Unit
    }

    private val serverAi = FakeServerAi()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The view model with its state collected, as the screen does. */
    private fun TestScope.viewModel(): ServerAiViewModel {
        val viewModel = ServerAiViewModel(serverAi)
        backgroundScope.launch { viewModel.state.collect {} }
        runCurrent()
        return viewModel
    }

    @Test
    fun `the switch is shown only to an owner whose server has page clean-up`() {
        val text = status(ServerAiState.DISABLED)
        val owner = serverAiUiState(ServerAiAccess.Owner(text, status(ServerAiState.OFF)), switching = false, error = null)
        assertEquals(ServerAiState.OFF, owner.cleanup?.state)

        val olderServer = serverAiUiState(ServerAiAccess.Owner(text, cleanup = null), switching = false, error = null)
        assertNull(olderServer.cleanup)

        val member = serverAiUiState(ServerAiAccess.NotOwner, switching = false, error = null)
        assertNull(member.cleanup)
    }

    @Test
    fun `flipping the switch asks the server for page clean-up only`() = runTest {
        val viewModel = viewModel()
        viewModel.onSwitch(true)
        runCurrent()
        assertEquals(listOf(ServerAiGroup.IMAGE to true), serverAi.flips)
        assertTrue(viewModel.state.value.switching)
        assertFalse(viewModel.state.value.cleanup!!.switchEnabled(viewModel.state.value.switching))

        serverAi.answer(ServerAiGroup.IMAGE, AppResult.Success(status(ServerAiState.LOADING, on = true)))
        runCurrent()
        assertFalse(viewModel.state.value.switching)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `a second flip waits for the first answer`() = runTest {
        val viewModel = viewModel()
        viewModel.onSwitch(true)
        viewModel.onSwitch(false)
        runCurrent()
        assertEquals(listOf(ServerAiGroup.IMAGE to true), serverAi.flips)
    }

    @Test
    fun `not enough memory names the clean-up model's numbers`() = runTest {
        serverAi.state.value = ServerAiAccess.Owner(
            status = status(ServerAiState.DISABLED),
            cleanup = status(ServerAiState.OFF, free = 1_500_000_000, needed = 2_500_000_000),
        )
        val viewModel = viewModel()
        viewModel.onSwitch(true)
        runCurrent()
        serverAi.answer(ServerAiGroup.IMAGE, AppResult.Failure(AppError.Api(ApiErrorCode.NOT_ENOUGH_MEMORY, 503)))
        runCurrent()

        val state = viewModel.state.value
        assertEquals(2_500_000_000L to 1_500_000_000L, state.memoryShortfall())
        assertFalse(state.switching)
    }

    @Test
    fun `any other failure, or one without numbers, gets the general message`() {
        val owner = ServerAiAccess.Owner(status(ServerAiState.DISABLED), status(ServerAiState.OFF, free = 1, needed = 2))
        val offline = serverAiUiState(owner, switching = false, error = AppError.NoConnection)
        assertNull(offline.memoryShortfall())

        val noNumbers = serverAiUiState(
            ServerAiAccess.Owner(status(ServerAiState.DISABLED), status(ServerAiState.OFF)),
            switching = false,
            error = AppError.Api(ApiErrorCode.NOT_ENOUGH_MEMORY, 503),
        )
        assertNull(noNumbers.memoryShortfall())
    }

    @Test
    fun `a new flip clears the last error`() = runTest {
        val viewModel = viewModel()
        viewModel.onSwitch(true)
        runCurrent()
        serverAi.answer(ServerAiGroup.IMAGE, AppResult.Failure(AppError.NoConnection))
        runCurrent()
        assertEquals(AppError.NoConnection, viewModel.state.value.error)

        viewModel.onSwitch(true)
        runCurrent()
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `a switch the server has not set up cannot be turned on and shows no timer`() {
        for (state in listOf(ServerAiState.DISABLED, ServerAiState.NOT_INSTALLED)) {
            val off = status(state)
            assertFalse(state.name, off.isSetUp)
            assertFalse(state.name, off.switchEnabled(switching = false))
        }
        for (state in listOf(ServerAiState.OFF, ServerAiState.READY, ServerAiState.FAILED, ServerAiState.UNAVAILABLE)) {
            assertTrue(state.name, status(state).isSetUp)
        }
        // Turning off always works, so the memory can be freed whatever the server says.
        assertTrue(status(ServerAiState.DISABLED, on = true).switchEnabled(switching = false))
    }

    private companion object {
        fun status(state: ServerAiState, on: Boolean = false, free: Long? = null, needed: Long? = null) = ServerAiStatus(
            state = state,
            on = on,
            autoOffAt = null,
            autoOffMinutes = 15,
            heartbeatSeconds = 60,
            availableBytes = free,
            neededBytes = needed,
        )
    }
}
