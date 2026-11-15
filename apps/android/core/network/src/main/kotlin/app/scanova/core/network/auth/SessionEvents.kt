package app.scanova.core.network.auth

import app.scanova.core.network.api.UserDto
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Things that happen to the session inside the network layer, for the data layer to react to. */
sealed interface SessionEvent {
    /** Tokens were rotated by a refresh; [user] is the server's current view of the account. */
    data class Refreshed(val user: UserDto) : SessionEvent

    /** The server ended the session (revoked, refresh token spent or expired). Tokens are already cleared. */
    data object Ended : SessionEvent
}

class SessionEvents {
    private val mutableEvents = MutableSharedFlow<SessionEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<SessionEvent> = mutableEvents.asSharedFlow()

    internal fun emit(event: SessionEvent) {
        mutableEvents.tryEmit(event)
    }
}
