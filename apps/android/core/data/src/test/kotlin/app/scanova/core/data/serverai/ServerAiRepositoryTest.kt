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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerAiRepositoryTest {

    /** The server: answers with [current], records every call. */
    private class FakeApi : ServerAiApi {
        var current: AppResult<AiRuntimeDto> = AppResult.Success(dto("OFF", on = false))
        var setAnswer: ((Boolean) -> AppResult<AiRuntimeDto>)? = null
        val calls = mutableListOf<String>()

        override suspend fun status(): AppResult<AiRuntimeDto> = current.also { calls += "status" }

        override suspend fun set(on: Boolean): AppResult<AiRuntimeDto> {
            calls += "set $on"
            setAnswer?.let { return it(on) }
            current = AppResult.Success(dto(if (on) "LOADING" else "OFF", on = on))
            return current
        }

        override suspend fun heartbeat(): AppResult<AiRuntimeDto> = current.also { calls += "heartbeat" }
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

        api.current = AppResult.Failure(AppError.Api(ApiErrorCode.FORBIDDEN, 403))
        assertEquals(ServerAiAccess.NotOwner, repository.refresh().value())
        assertEquals(ServerAiAccess.NotOwner, repository.access.value)
    }

    @Test
    fun `while on and on screen it checks in every heartbeatSeconds`() = runTest {
        api.current = AppResult.Success(dto("READY", on = true))
        val repository = repository()
        val visible = launch { repository.keepAliveWhileVisible() }
        runCurrent()
        assertEquals(listOf("status", "heartbeat"), api.calls)

        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(1, api.calls.count { it == "heartbeat" })
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, api.calls.count { it == "heartbeat" })

        // The app goes to the background: no more check-ins, so the server turns it off by itself.
        visible.cancel()
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        assertEquals(2, api.calls.count { it == "heartbeat" })
    }

    @Test
    fun `while loading it looks again every two seconds, then settles to the heartbeat`() = runTest {
        api.current = AppResult.Success(dto("LOADING", on = true))
        val repository = repository()
        val visible = launch { repository.keepAliveWhileVisible() }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, api.calls.count { it == "heartbeat" })

        api.current = AppResult.Success(dto("READY", on = true))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, api.calls.count { it == "heartbeat" })
        assertEquals(ServerAiState.READY, (repository.access.value as ServerAiAccess.Owner).status.state)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, api.calls.count { it == "heartbeat" })
        visible.cancel()
    }

    @Test
    fun `while off it sends nothing until the owner turns it on`() = runTest {
        val repository = repository()
        val visible = launch { repository.keepAliveWhileVisible() }
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(listOf("status"), api.calls)

        repository.setOn(true)
        runCurrent()
        assertEquals(listOf("status", "set true", "heartbeat"), api.calls)
        visible.cancel()
    }

    @Test
    fun `a member who is not an owner is asked once per account, not on every return to the app`() = runTest {
        api.current = AppResult.Failure(AppError.Api(ApiErrorCode.FORBIDDEN, 403))
        val repository = repository()
        launch { repository.keepAliveWhileVisible() }.also { runCurrent() }.cancel()
        launch { repository.keepAliveWhileVisible() }.also { runCurrent() }.cancel()
        assertEquals(listOf("status"), api.calls)
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
        api.setAnswer = { AppResult.Failure(AppError.Api(ApiErrorCode.NOT_ENOUGH_MEMORY, 503)) }
        api.current = AppResult.Success(dto("OFF", on = false, available = 3_000_000_000, needed = 7_000_000_000))
        val repository = repository()
        val result = repository.setOn(true)
        assertEquals(ApiErrorCode.NOT_ENOUGH_MEMORY, ((result as AppResult.Failure).error as AppError.Api).code)
        val status = (repository.access.value as ServerAiAccess.Owner).status
        assertEquals(3_000_000_000, status.availableBytes)
        assertEquals(7_000_000_000, status.neededBytes)
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

        fun dto(state: String, on: Boolean, available: Long? = null, needed: Long? = null) = AiRuntimeDto(
            state = state,
            on = on,
            autoOffAt = if (on) "2026-11-16T10:15:00.000Z" else null,
            memory = AiMemoryDto(availableBytes = available, neededBytes = needed),
        )
    }
}
