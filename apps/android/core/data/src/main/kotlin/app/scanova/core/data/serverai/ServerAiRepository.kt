package app.scanova.core.data.serverai

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.model.AuthState
import app.scanova.core.network.ScanovaRemote
import app.scanova.core.network.api.AiRuntimeDto
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The server AI as an owner sees it (docs/ARCHITECTURE.md §11.8). */
enum class ServerAiState {
    OFF,
    LOADING,
    READY,
    UNLOADING,
    FAILED,
    NOT_INSTALLED,
    UNAVAILABLE,
    DISABLED,

    /** A state added by a newer server. */
    UNKNOWN,
    ;

    /** Moving between off and ready: worth checking again in a moment. */
    val isChanging: Boolean get() = this == LOADING || this == UNLOADING

    companion object {
        fun fromWire(value: String): ServerAiState = entries.firstOrNull { it != UNKNOWN && it.name == value } ?: UNKNOWN
    }
}

data class ServerAiStatus(
    val state: ServerAiState,
    /** An owner asked for it to be on (it may still be loading). */
    val on: Boolean,
    /** When the server turns it off unless an open app checks in first. */
    val autoOffAt: Instant?,
    val autoOffMinutes: Int,
    val heartbeatSeconds: Int,
    val availableBytes: Long?,
    val neededBytes: Long?,
)

/** Whether this account gets the switch at all. */
sealed interface ServerAiAccess {
    /** Signed out, or not asked yet. */
    data object Unknown : ServerAiAccess

    /** Signed in, but not an owner of the server: no switch. */
    data object NotOwner : ServerAiAccess

    data class Owner(val status: ServerAiStatus) : ServerAiAccess
}

/**
 * The owner's switch for the AI on the server. Loading a model costs the shared
 * server gigabytes of RAM, so it is only loaded while an owner wants it: the
 * server turns it off again 15 minutes after the last check-in from an open app.
 */
interface ServerAiRepository {
    val access: StateFlow<ServerAiAccess>

    suspend fun refresh(): AppResult<ServerAiAccess>

    /** Answers at once; while the models load the state is [ServerAiState.LOADING]. */
    suspend fun setOn(on: Boolean): AppResult<ServerAiStatus>

    /**
     * Runs while the app is on screen (the caller cancels it when the app goes
     * to the background): checks in every `heartbeatSeconds` while the AI is
     * on, and every couple of seconds while it is loading or unloading. A closed
     * app checks in no more, and the server turns the AI off by itself.
     */
    suspend fun keepAliveWhileVisible()
}

/** The three server calls, behind an interface so the repository is tested without a server. */
internal interface ServerAiApi {
    suspend fun status(): AppResult<AiRuntimeDto>

    suspend fun set(on: Boolean): AppResult<AiRuntimeDto>

    suspend fun heartbeat(): AppResult<AiRuntimeDto>
}

internal class RemoteServerAiApi(private val remote: ScanovaRemote) : ServerAiApi {
    override suspend fun status(): AppResult<AiRuntimeDto> = remote.aiRuntime()

    override suspend fun set(on: Boolean): AppResult<AiRuntimeDto> = remote.setAiRuntime(on)

    override suspend fun heartbeat(): AppResult<AiRuntimeDto> = remote.aiRuntimeHeartbeat()
}

internal class ServerAiRepositoryImpl(
    private val remote: ServerAiApi,
    private val authState: Flow<AuthState>,
    private val dispatchers: AppDispatchers,
    private val changingInterval: Duration = CHANGING_INTERVAL,
) : ServerAiRepository {

    private val state = MutableStateFlow<ServerAiAccess>(ServerAiAccess.Unknown)
    override val access: StateFlow<ServerAiAccess> = state.asStateFlow()

    /** The account the server already said is not an owner: not asked again in this process. */
    @Volatile private var notOwnerUserId: String? = null

    override suspend fun refresh(): AppResult<ServerAiAccess> = withContext(dispatchers.io) {
        when (val result = remote.status()) {
            is AppResult.Success -> AppResult.Success(publish(result.value))
            is AppResult.Failure -> {
                if (result.error.isForbidden) {
                    state.value = ServerAiAccess.NotOwner
                    AppResult.Success(ServerAiAccess.NotOwner)
                } else {
                    result
                }
            }
        }
    }

    override suspend fun setOn(on: Boolean): AppResult<ServerAiStatus> = withContext(dispatchers.io) {
        when (val result = remote.set(on)) {
            is AppResult.Success -> AppResult.Success((publish(result.value) as ServerAiAccess.Owner).status)
            is AppResult.Failure -> {
                when {
                    result.error.isForbidden -> state.value = ServerAiAccess.NotOwner
                    // The status carries how much memory is free and how much loading needs.
                    result.error.code == ApiErrorCode.NOT_ENOUGH_MEMORY -> refresh()
                }
                result
            }
        }
    }

    override suspend fun keepAliveWhileVisible() {
        authState
            .map { (it as? AuthState.SignedIn)?.user?.id }
            .distinctUntilChanged()
            .collectLatest { userId ->
                if (userId == null) {
                    state.value = ServerAiAccess.Unknown
                    notOwnerUserId = null
                    return@collectLatest
                }
                if (notOwnerUserId == userId) return@collectLatest
                if (refresh() == AppResult.Success(ServerAiAccess.NotOwner)) {
                    notOwnerUserId = userId
                    return@collectLatest
                }
                checkInWhileOn()
            }
    }

    private suspend fun checkInWhileOn() {
        while (true) {
            val status = (state.value as? ServerAiAccess.Owner)?.status ?: return
            if (!status.on && !status.state.isChanging) {
                // Off and settled: nothing to keep alive until the owner turns it on.
                state.first { access ->
                    val next = (access as? ServerAiAccess.Owner)?.status
                    next == null || next.on || next.state.isChanging
                }
                continue
            }
            if (status.on) heartbeat() else refresh()
            val latest = (state.value as? ServerAiAccess.Owner)?.status ?: return
            delay(if (latest.state.isChanging) changingInterval else latest.heartbeatSeconds.coerceAtLeast(5).seconds)
        }
    }

    private suspend fun heartbeat() {
        withContext(dispatchers.io) {
            when (val result = remote.heartbeat()) {
                is AppResult.Success -> publish(result.value)
                // Offline for a moment: the next check-in may get through before the server gives up.
                is AppResult.Failure -> if (result.error.isForbidden) state.value = ServerAiAccess.NotOwner
            }
        }
    }

    private fun publish(dto: AiRuntimeDto): ServerAiAccess =
        ServerAiAccess.Owner(dto.toDomain()).also { state.value = it }

    private companion object {
        val CHANGING_INTERVAL = 2.seconds
    }
}

private val AppError.isForbidden: Boolean
    get() = this is AppError.Api && code == ApiErrorCode.FORBIDDEN

private val AppError.code: ApiErrorCode?
    get() = (this as? AppError.Api)?.code

internal fun AiRuntimeDto.toDomain(): ServerAiStatus = ServerAiStatus(
    state = ServerAiState.fromWire(state),
    on = on,
    autoOffAt = autoOffAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
    autoOffMinutes = autoOffMinutes,
    heartbeatSeconds = heartbeatSeconds,
    availableBytes = memory.availableBytes,
    neededBytes = memory.neededBytes,
)
