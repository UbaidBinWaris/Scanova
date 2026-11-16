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
    fun `the clean-up switch is shown only when the server has one`() {
        val text = status(ServerAiState.READY, on = true)
        val withCleanup = serverAiUiState(ServerAiAccess.Owner(text, status(ServerAiState.OFF)), emptySet(), null)
        assertEquals(text, withCleanup.status)
        assertEquals(ServerAiState.OFF, withCleanup.cleanup?.state)

        val olderServer = serverAiUiState(ServerAiAccess.Owner(text, cleanup = null), emptySet(), null)
        assertEquals(text, olderServer.status)
        assertNull(olderServer.cleanup)

        val member = serverAiUiState(ServerAiAccess.NotOwner, emptySet(), null)
        assertNull(member.status)
        assertNull(member.cleanup)
    }

    @Test
    fun `the text switch works as before`() = runTest {
        val viewModel = viewModel()
        viewModel.onSwitch(ServerAiGroup.TEXT, true)
        runCurrent()
        assertEquals(listOf(ServerAiGroup.TEXT to true), serverAi.flips)
        assertTrue(viewModel.state.value.isSwitching(ServerAiGroup.TEXT))

        serverAi.answer(ServerAiGroup.TEXT, AppResult.Success(status(ServerAiState.LOADING, on = true)))
        runCurrent()
        assertFalse(viewModel.state.value.isSwitching(ServerAiGroup.TEXT))
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `flipping page clean-up asks for the image switch only`() = runTest {
        val viewModel = viewModel()
        viewModel.onSwitch(ServerAiGroup.IMAGE, true)
        runCurrent()
        assertEquals(listOf(ServerAiGroup.IMAGE to true), serverAi.flips)
        val state = viewModel.state.value
        assertTrue(state.isSwitching(ServerAiGroup.IMAGE))
        assertFalse(state.isSwitching(ServerAiGroup.TEXT))
    }

    @Test
    fun `one switch waiting for its answer does not freeze the other`() = runTest {
        val viewModel = viewModel()
        viewModel.onSwitch(ServerAiGroup.IMAGE, true)
        runCurrent()
        val waiting = viewModel.state.value
        assertFalse(waiting.cleanup!!.switchEnabled(waiting.isSwitching(ServerAiGroup.IMAGE)))
        assertTrue(waiting.status!!.switchEnabled(waiting.isSwitching(ServerAiGroup.TEXT)))

        viewModel.onSwitch(ServerAiGroup.TEXT, true)
        runCurrent()
        assertEquals(listOf(ServerAiGroup.IMAGE to true, ServerAiGroup.TEXT to true), serverAi.flips)

        serverAi.answer(ServerAiGroup.IMAGE, AppResult.Success(status(ServerAiState.LOADING, on = true)))
        runCurrent()
        assertEquals(setOf(ServerAiGroup.TEXT), viewModel.state.value.switching)
    }

    @Test
    fun `a second flip of the same switch waits for the first answer`() = runTest {
        val viewModel = viewModel()
        viewModel.onSwitch(ServerAiGroup.IMAGE, true)
        viewModel.onSwitch(ServerAiGroup.IMAGE, false)
        runCurrent()
        assertEquals(listOf(ServerAiGroup.IMAGE to true), serverAi.flips)
    }

    @Test
    fun `not enough memory for clean-up names the clean-up model's numbers`() = runTest {
        serverAi.state.value = ServerAiAccess.Owner(
            status = status(ServerAiState.READY, on = true, free = 4_000_000_000, needed = 9_000_000_000),
            cleanup = status(ServerAiState.OFF, free = 1_500_000_000, needed = 2_500_000_000),
        )
        val viewModel = viewModel()
        viewModel.onSwitch(ServerAiGroup.IMAGE, true)
        runCurrent()
        serverAi.answer(ServerAiGroup.IMAGE, AppResult.Failure(AppError.Api(ApiErrorCode.NOT_ENOUGH_MEMORY, 503)))
        runCurrent()

        val state = viewModel.state.value
        assertEquals(ServerAiGroup.IMAGE, state.error?.group)
        assertEquals(2_500_000_000L to 1_500_000_000L, state.memoryShortfall())
        assertFalse(state.isSwitching(ServerAiGroup.IMAGE))
    }

    @Test
    fun `not enough memory for the text AI names the text model's numbers`() {
        val state = serverAiUiState(
            ServerAiAccess.Owner(
                status = status(ServerAiState.OFF, free = 4_000_000_000, needed = 9_000_000_000),
                cleanup = status(ServerAiState.OFF, free = 4_000_000_000, needed = 2_500_000_000),
            ),
            switching = emptySet(),
            error = SwitchFailure(ServerAiGroup.TEXT, AppError.Api(ApiErrorCode.NOT_ENOUGH_MEMORY, 503)),
        )
        assertEquals(9_000_000_000L to 4_000_000_000L, state.memoryShortfall())
    }

    @Test
    fun `any other failure, or one without numbers, gets the general message`() {
        val owner = ServerAiAccess.Owner(status(ServerAiState.OFF, free = 1, needed = 2), status(ServerAiState.OFF))
        val offline = serverAiUiState(owner, emptySet(), SwitchFailure(ServerAiGroup.TEXT, AppError.NoConnection))
        assertNull(offline.memoryShortfall())

        val noNumbers = serverAiUiState(
            owner,
            emptySet(),
            SwitchFailure(ServerAiGroup.IMAGE, AppError.Api(ApiErrorCode.NOT_ENOUGH_MEMORY, 503)),
        )
        assertNull(noNumbers.memoryShortfall())
    }

    @Test
    fun `a new flip clears the last error`() = runTest {
        val viewModel = viewModel()
        viewModel.onSwitch(ServerAiGroup.IMAGE, true)
        runCurrent()
        serverAi.answer(ServerAiGroup.IMAGE, AppResult.Failure(AppError.NoConnection))
        runCurrent()
        assertEquals(SwitchFailure(ServerAiGroup.IMAGE, AppError.NoConnection), viewModel.state.value.error)

        viewModel.onSwitch(ServerAiGroup.TEXT, true)
        runCurrent()
        assertNull(viewModel.state.value.error)
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
