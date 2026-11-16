package app.scanova.core.data.serverai

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.model.AuthState
import app.scanova.core.data.model.User
import app.scanova.core.network.api.AiMemoryDto
import app.scanova.core.network.api.AiRuntimeDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerAiRepositoryTest {

    /** The server: answers each switch with [text] or [image], records every call as "<call> <group>". */
    private class FakeApi : ServerAiApi {
        var text: AppResult<AiRuntimeDto> = AppResult.Success(dto("OFF", on = false))
        var image: AppResult<AiRuntimeDto> = AppResult.Success(dto("OFF", on = false, group = "image"))
        var setAnswer: ((ServerAiGroup, Boolean) -> AppResult<AiRuntimeDto>)? = null
        val calls = mutableListOf<String>()

        fun count(call: String): Int = calls.count { it == call }

        private fun answer(group: ServerAiGroup): AppResult<AiRuntimeDto> = when (group) {
            ServerAiGroup.TEXT -> text
            ServerAiGroup.IMAGE -> image
        }

        override suspend fun status(group: ServerAiGroup): AppResult<AiRuntimeDto> =
            answer(group).also { calls += "status ${group.wire}" }

        override suspend fun set(group: ServerAiGroup, on: Boolean): AppResult<AiRuntimeDto> {
            calls += "set ${group.wire} $on"
            setAnswer?.let { return it(group, on) }
            val next = AppResult.Success(dto(if (on) "LOADING" else "OFF", on = on, group = group.wire))
            when (group) {
                ServerAiGroup.TEXT -> text = next
                ServerAiGroup.IMAGE -> image = next
            }
            return next
        }

        override suspend fun heartbeat(group: ServerAiGroup): AppResult<AiRuntimeDto> =
            answer(group).also { calls += "heartbeat ${group.wire}" }
    }

    private class TestDispatchers(dispatcher: CoroutineDispatcher) : AppDispatchers {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

    private val api = FakeApi()
    private val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(USER))

    private fun TestScope.repository() =
        ServerAiRepositoryImpl(api, auth, TestDispatchers(StandardTestDispatcher(testScheduler)))

    @Test
    fun `an owner gets the status, anyone else is told they are not an owner`() = runTest {
        val repository = repository()
        assertEquals(ServerAiState.OFF, (repository.refresh().value() as ServerAiAccess.Owner).status.state)

        api.calls.clear()
        api.text = AppResult.Failure(AppError.Api(ApiErrorCode.FORBIDDEN, 403))
        assertEquals(ServerAiAccess.NotOwner, repository.refresh().value())
        assertEquals(ServerAiAccess.NotOwner, repository.access.value)
        // Not an owner: the clean-up switch is not asked for either.
        assertEquals(listOf("status text"), api.calls)
    }

    @Test
    fun `a refresh asks for both switches`() = runTest {
        api.text = AppResult.Success(dto("READY", on = true))
        api.image = AppResult.Success(dto("LOADING", on = true, group = "image"))
        val repository = repository()
        val owner = repository.refresh().value() as ServerAiAccess.Owner
        assertEquals(listOf("status text", "status image"), api.calls)
        assertEquals(ServerAiState.READY, owner.status.state)
        assertEquals(ServerAiState.LOADING, owner.cleanup?.state)
        assertEquals(owner, repository.access.value)
    }

    @Test
    fun `a server from before the clean-up switch answers for the text AI, so there is no clean-up switch`() = runTest {
        // It ignores ?group=image and sends the text status, without a group field.
        api.image = AppResult.Success(dto("READY", on = true))
        val repository = repository()
        val owner = repository.refresh().value() as ServerAiAccess.Owner
        assertEquals(ServerAiState.OFF, owner.status.state)
        assertNull(owner.cleanup)
    }

    @Test
    fun `a failed clean-up check keeps what was known and leaves the text AI alone`() = runTest {
        api.text = AppResult.Success(dto("READY", on = true))
        api.image = AppResult.Failure(AppError.NoConnection)
        val repository = repository()
        val unknown = repository.refresh().value() as ServerAiAccess.Owner
        assertEquals(ServerAiState.READY, unknown.status.state)
        // Never answered yet: no clean-up switch to show.
        assertNull(unknown.cleanup)

        api.image = AppResult.Success(dto("READY", on = true, group = "image"))
        repository.refresh()
        // Offline for a moment: the switch that is on stays, so it keeps checking in.
        api.image = AppResult.Failure(AppError.NoConnection)
        val blip = repository.refresh().value() as ServerAiAccess.Owner
        assertEquals(ServerAiState.READY, blip.cleanup?.state)

        // Refused for clean-up only: still an owner, just without that switch.
        api.image = AppResult.Failure(AppError.Api(ApiErrorCode.FORBIDDEN, 403))
        val again = repository.refresh().value() as ServerAiAccess.Owner
        assertEquals(ServerAiState.READY, again.status.state)
        assertNull(again.cleanup)
    }

    @Test
    fun `turning page clean-up on flips the image switch only`() = runTest {
        val repository = repository()
        repository.refresh()
        val result = repository.setOn(true, ServerAiGroup.IMAGE)
        assertEquals(ServerAiState.LOADING, result.value().state)
        assertEquals(listOf("status text", "status image", "set image true"), api.calls)
        val owner = repository.access.value as ServerAiAccess.Owner
        assertEquals(ServerAiState.LOADING, owner.cleanup?.state)
        assertEquals(ServerAiState.OFF, owner.status.state)
    }

    @Test
    fun `a server from before clean-up that flips the text AI instead is shown as it is and reported`() = runTest {
        val repository = repository()
        repository.refresh()
        api.setAnswer = { _, on -> AppResult.Success(dto("LOADING", on = on)) }
        val result = repository.setOn(true, ServerAiGroup.IMAGE)
        assertEquals(AppError.UnexpectedResponse, (result as AppResult.Failure).error)
        val owner = repository.access.value as ServerAiAccess.Owner
        assertEquals(ServerAiState.LOADING, owner.status.state)
        assertNull(owner.cleanup)
    }

    @Test
    fun `while on and on screen it checks in every heartbeatSeconds`() = runTest {
        api.text = AppResult.Success(dto("READY", on = true))
        val repository = repository()
        val visible = launch { repository.keepAliveWhileVisible() }
        runCurrent()
        assertEquals(listOf("status text", "status image", "heartbeat text"), api.calls)

        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(1, api.count("heartbeat text"))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, api.count("heartbeat text"))

        // The app goes to the background: no more check-ins, so the server turns it off by itself.
        visible.cancel()
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        assertEquals(2, api.count("heartbeat text"))
    }

    @Test
    fun `while loading it looks again every two seconds, then settles to the heartbeat`() = runTest {
        api.text = AppResult.Success(dto("LOADING", on = true))
        val repository = repository()
        val visible = launch { repository.keepAliveWhileVisible() }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, api.count("heartbeat text"))

        api.text = AppResult.Success(dto("READY", on = true))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, api.count("heartbeat text"))
        assertEquals(ServerAiState.READY, (repository.access.value as ServerAiAccess.Owner).status.state)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, api.count("heartbeat text"))
        visible.cancel()
    }

    @Test
    fun `while off it sends nothing until the owner turns it on`() = runTest {
        val repository = repository()
        val visible = launch { repository.keepAliveWhileVisible() }
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(listOf("status text", "status image"), api.calls)

        repository.setOn(true)
        runCurrent()
        assertEquals(listOf("status text", "status image", "set text true", "heartbeat text"), api.calls)
        visible.cancel()
    }

    @Test
    fun `only the switch that is on checks in`() = runTest {
        api.image = AppResult.Success(dto("READY", on = true, group = "image"))
        val repository = repository()
        val visible = launch { repository.keepAliveWhileVisible() }
        runCurrent()
        assertEquals(listOf("status text", "status image", "heartbeat image"), api.calls)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, api.count("heartbeat image"))
        assertEquals(0, api.count("heartbeat text"))

        // Turning the text AI on starts its own check-ins at once, not at clean-up's next one.
        repository.setOn(true)
        runCurrent()
        assertEquals(1, api.count("heartbeat text"))
        assertEquals(2, api.count("heartbeat image"))
        visible.cancel()
    }

    @Test
    fun `each switch keeps its own pace, so one that is unloading does not hold up the other`() = runTest {
        api.text = AppResult.Success(dto("READY", on = true))
        api.image = AppResult.Success(dto("UNLOADING", on = false, group = "image"))
        val repository = repository()
        val visible = launch { repository.keepAliveWhileVisible() }
        runCurrent()
        assertEquals(1, api.count("heartbeat text"))
        // The refresh, then a first look while it unloads.
        assertEquals(2, api.count("status image"))

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, api.count("status image"))

        api.image = AppResult.Success(dto("OFF", on = false, group = "image"))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(4, api.count("status image"))
        assertEquals(ServerAiState.OFF, (repository.access.value as ServerAiAccess.Owner).cleanup?.state)

        // Clean-up is off and settled: it sends nothing more, while the text AI checks in on time.
        advanceTimeBy(56_000)
        runCurrent()
        assertEquals(4, api.count("status image"))
        assertEquals(0, api.count("heartbeat image"))
        assertEquals(2, api.count("heartbeat text"))
        // The text AI is on, so it only checks in: its status was asked for once, by the refresh.
        assertEquals(1, api.count("status text"))
        visible.cancel()
    }

    @Test
    fun `a member who is not an owner is asked once per account, not on every return to the app`() = runTest {
        api.text = AppResult.Failure(AppError.Api(ApiErrorCode.FORBIDDEN, 403))
        val repository = repository()
        launch { repository.keepAliveWhileVisible() }.also { runCurrent() }.cancel()
        launch { repository.keepAliveWhileVisible() }.also { runCurrent() }.cancel()
        assertEquals(listOf("status text"), api.calls)
    }

    @Test
    fun `signed out, it never calls the server`() = runTest {
        auth.value = AuthState.SignedOut
        val repository = repository()
        launch { repository.keepAliveWhileVisible() }.also { runCurrent() }.cancel()
        assertTrue(api.calls.isEmpty())
        assertEquals(ServerAiAccess.Unknown, repository.access.value)
    }

    @Test
    fun `not enough memory fetches the numbers to show, and reports the failure`() = runTest {
        api.setAnswer = { _, _ -> AppResult.Failure(AppError.Api(ApiErrorCode.NOT_ENOUGH_MEMORY, 503)) }
        api.text = AppResult.Success(dto("OFF", on = false, available = 3_000_000_000, needed = 7_000_000_000))
        val repository = repository()
        val result = repository.setOn(true)
        assertEquals(ApiErrorCode.NOT_ENOUGH_MEMORY, ((result as AppResult.Failure).error as AppError.Api).code)
        val status = (repository.access.value as ServerAiAccess.Owner).status
        assertEquals(3_000_000_000, status.availableBytes)
        assertEquals(7_000_000_000, status.neededBytes)
    }

    @Test
    fun `not enough memory for clean-up fetches its numbers too`() = runTest {
        api.setAnswer = { _, _ -> AppResult.Failure(AppError.Api(ApiErrorCode.NOT_ENOUGH_MEMORY, 503)) }
        api.image = AppResult.Success(
            dto("OFF", on = false, group = "image", available = 1_500_000_000, needed = 2_500_000_000),
        )
        val repository = repository()
        val result = repository.setOn(true, ServerAiGroup.IMAGE)
        assertEquals(ApiErrorCode.NOT_ENOUGH_MEMORY, ((result as AppResult.Failure).error as AppError.Api).code)
        assertEquals(listOf("set image true", "status text", "status image"), api.calls)
        val cleanup = (repository.access.value as ServerAiAccess.Owner).cleanup
        assertEquals(1_500_000_000L, cleanup?.availableBytes)
        assertEquals(2_500_000_000L, cleanup?.neededBytes)
    }

    @Test
    fun `a state from a newer server is unknown, not a crash`() {
        assertEquals(ServerAiState.UNKNOWN, ServerAiState.fromWire("HIBERNATING"))
        assertEquals(ServerAiState.READY, ServerAiState.fromWire("READY"))
    }

    private fun <T> AppResult<T>.value(): T = (this as AppResult.Success).value

    private companion object {
        val USER = User(
            id = "user-1",
            email = "owner@example.com",
            name = "Owner",
            mustChangePassword = false,
            storageUsedBytes = 0,
            storageQuotaBytes = null,
        )

        fun dto(
            state: String,
            on: Boolean,
            group: String = "text",
            available: Long? = null,
            needed: Long? = null,
        ) = AiRuntimeDto(
            group = group,
            state = state,
            on = on,
            autoOffAt = if (on) "2026-11-16T10:15:00.000Z" else null,
            memory = AiMemoryDto(availableBytes = available, neededBytes = needed),
        )
    }
}
