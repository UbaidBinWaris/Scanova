package app.scanova.core.data.serverai

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.result.getOrNull
import app.scanova.core.data.model.AuthState
import app.scanova.core.network.ScanovaRemote
import app.scanova.core.network.api.AiRuntimeDto
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/** The owner's switches on the server (§11.8); [wire] is the API's `?group=`. */
enum class ServerAiGroup(val wire: String) {
    /** The chat and embedding models. */
    TEXT("text"),

    /** The page clean-up model ("Server clean-up" in the scan look). */
    IMAGE("image"),
}

/** Whether this account gets the switches at all. */
sealed interface ServerAiAccess {
    /** Signed out, or not asked yet. */
    data object Unknown : ServerAiAccess

    /** Signed in, but not an owner of the server: no switch. */
    data object NotOwner : ServerAiAccess

    /**
     * An owner: [status] is the text AI switch, [cleanup] the page clean-up
     * switch, null on a server from before it (which has no such switch) or
     * when the server did not answer for it on the last refresh.
     */
    data class Owner(val status: ServerAiStatus, val cleanup: ServerAiStatus? = null) : ServerAiAccess
}

/** The switch for [group]: [ServerAiAccess.Owner.status] or [ServerAiAccess.Owner.cleanup]. Null when not an owner. */
fun ServerAiAccess.statusOf(group: ServerAiGroup): ServerAiStatus? {
    val owner = this as? ServerAiAccess.Owner ?: return null
    return when (group) {
        ServerAiGroup.TEXT -> owner.status
        ServerAiGroup.IMAGE -> owner.cleanup
    }
}

/**
 * The owner's switches for the AI on the server (text AI and page clean-up).
 * Loading a model costs the shared server gigabytes of RAM, so it is only
 * loaded while an owner wants it: the server turns each switch off again 15
 * minutes after the last check-in for it from an open app.
 */
interface ServerAiRepository {
    val access: StateFlow<ServerAiAccess>

    /**
     * Asks for both switches. The text switch decides whether this account is an
     * owner at all; the clean-up switch is only shown when the server answers for
     * it (a server from before it has none, and a failure hides it for now).
     */
    suspend fun refresh(): AppResult<ServerAiAccess>

    /** Answers at once; while the model loads the state is [ServerAiState.LOADING]. */
    suspend fun setOn(on: Boolean, group: ServerAiGroup = ServerAiGroup.TEXT): AppResult<ServerAiStatus>

    /**
     * Runs while the app is on screen (the caller cancels it when the app goes
     * to the background). Each switch on its own: checks in for it every
     * `heartbeatSeconds` while it is on, and looks again every couple of seconds
     * while it is loading or unloading. A closed app checks in no more, and the
     * server turns the switches off by itself.
     */
    suspend fun keepAliveWhileVisible()
}

/** The three server calls, behind an interface so the repository is tested without a server. */
internal interface ServerAiApi {
    suspend fun status(group: ServerAiGroup): AppResult<AiRuntimeDto>

    suspend fun set(group: ServerAiGroup, on: Boolean): AppResult<AiRuntimeDto>

    suspend fun heartbeat(group: ServerAiGroup): AppResult<AiRuntimeDto>
}

internal class RemoteServerAiApi(private val remote: ScanovaRemote) : ServerAiApi {
    override suspend fun status(group: ServerAiGroup): AppResult<AiRuntimeDto> = remote.aiRuntime(group.wire)

    override suspend fun set(group: ServerAiGroup, on: Boolean): AppResult<AiRuntimeDto> =
        remote.setAiRuntime(group.wire, on)

    override suspend fun heartbeat(group: ServerAiGroup): AppResult<AiRuntimeDto> = remote.aiRuntimeHeartbeat(group.wire)
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
        when (val text = remote.status(ServerAiGroup.TEXT)) {
            is AppResult.Success -> {
                // Only an owner gets this far, so the clean-up switch is asked for only then.
                // An old server (no image switch) or a refusal hides it; a failed check (offline
                // for a moment) keeps what was known, so a switch that is on keeps checking in.
                val known = (state.value as? ServerAiAccess.Owner)?.cleanup
                val cleanup = when (val image = remote.status(ServerAiGroup.IMAGE)) {
                    is AppResult.Success -> image.value.toCleanup()
                    is AppResult.Failure -> if (image.error.isForbidden) null else known
                }
                val owner = ServerAiAccess.Owner(text.value.toDomain(), cleanup)
                state.value = owner
                AppResult.Success(owner)
            }
            is AppResult.Failure -> {
                if (text.error.isForbidden) {
                    state.value = ServerAiAccess.NotOwner
                    AppResult.Success(ServerAiAccess.NotOwner)
                } else {
                    text
                }
            }
        }
    }

    override suspend fun setOn(on: Boolean, group: ServerAiGroup): AppResult<ServerAiStatus> = withContext(dispatchers.io) {
        when (val result = remote.set(group, on)) {
            is AppResult.Success -> answered(group, result.value)
            is AppResult.Failure -> {
                when {
                    result.error.isForbidden -> forbidden(group)
                    // The status carries how much memory is free and how much loading needs.
                    result.error.code == ApiErrorCode.NOT_ENOUGH_MEMORY -> refresh()
                }
                result
            }
        }
    }

    /**
     * The server's answer to a switch flip. A server from before the clean-up
     * switch ignores `?group=` and flips the text AI instead: that is shown as it
     * is, and the clean-up flip is reported as not understood.
     */
    private fun answered(group: ServerAiGroup, dto: AiRuntimeDto): AppResult<ServerAiStatus> {
        if (group == ServerAiGroup.IMAGE && dto.group != ServerAiGroup.IMAGE.wire) {
            publish(ServerAiGroup.TEXT, dto)
            state.update { access -> (access as? ServerAiAccess.Owner)?.copy(cleanup = null) ?: access }
            return AppResult.Failure(AppError.UnexpectedResponse)
        }
        val status = dto.toDomain()
        publish(group, dto)
        return AppResult.Success(status)
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
                // One loop per switch, so a loading clean-up model never delays the text AI's check-in.
                coroutineScope {
                    for (group in ServerAiGroup.entries) launch { checkInWhileOn(group) }
                }
            }
    }

    /** Keeps the [group] switch alive until this account is no longer known as an owner. */
    private suspend fun checkInWhileOn(group: ServerAiGroup) {
        while (true) {
            val access = state.value
            if (access !is ServerAiAccess.Owner) return
            val status = access.statusOf(group)
            if (status == null || !status.needsCheckIn) {
                // Off and settled (or no such switch on this server): nothing to keep alive until it is turned on.
                state.first { next -> next !is ServerAiAccess.Owner || next.statusOf(group)?.needsCheckIn == true }
                continue
            }
            if (status.on) heartbeat(group) else look(group)
            val latest = state.value.statusOf(group) ?: continue
            delay(if (latest.state.isChanging) changingInterval else latest.heartbeatSeconds.coerceAtLeast(5).seconds)
        }
    }

    private suspend fun heartbeat(group: ServerAiGroup) {
        withContext(dispatchers.io) {
            when (val result = remote.heartbeat(group)) {
                is AppResult.Success -> publish(group, result.value)
                // Offline for a moment: the next check-in may get through before the server gives up.
                is AppResult.Failure -> if (result.error.isForbidden) forbidden(group)
            }
        }
    }

    /** One switch again while it loads or unloads; the other one is left as it is. */
    private suspend fun look(group: ServerAiGroup) {
        withContext(dispatchers.io) {
            when (val result = remote.status(group)) {
                is AppResult.Success -> publish(group, result.value)
                // Offline for a moment: the next look may get through.
                is AppResult.Failure -> if (result.error.isForbidden) forbidden(group)
            }
        }
    }

    /**
     * Puts one switch's answer into [state], leaving the other switch as it is.
     * A clean-up answer only counts once the text switch said this is an owner.
     */
    private fun publish(group: ServerAiGroup, dto: AiRuntimeDto) {
        state.update { access ->
            val owner = access as? ServerAiAccess.Owner
            when (group) {
                ServerAiGroup.TEXT -> ServerAiAccess.Owner(dto.toDomain(), owner?.cleanup)
                ServerAiGroup.IMAGE -> owner?.copy(cleanup = dto.toCleanup()) ?: access
            }
        }
    }

    /** Only the text switch decides who is an owner; a refused clean-up switch is just not shown. */
    private fun forbidden(group: ServerAiGroup) {
        when (group) {
            ServerAiGroup.TEXT -> state.value = ServerAiAccess.NotOwner
            ServerAiGroup.IMAGE -> state.update { access -> (access as? ServerAiAccess.Owner)?.copy(cleanup = null) ?: access }
        }
    }

    private companion object {
        val CHANGING_INTERVAL = 2.seconds
    }
}

/** On, or moving between off and ready: an open app has to check in for it. */
private val ServerAiStatus.needsCheckIn: Boolean
    get() = on || state.isChanging

/** The clean-up switch from an answer, or null when the server answered for another switch (one from before it). */
private fun AiRuntimeDto.toCleanup(): ServerAiStatus? = if (group == ServerAiGroup.IMAGE.wire) toDomain() else null

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
