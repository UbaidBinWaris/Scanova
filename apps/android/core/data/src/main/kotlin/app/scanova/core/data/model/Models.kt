package app.scanova.core.data.model

import app.scanova.core.common.result.AppError
import java.time.Instant

/** Where a document stands between this phone and the server (docs/ARCHITECTURE.md §9). */
enum class SyncState {
    /** On this phone only; waiting to be uploaded. */
    LOCAL_ONLY,

    /** Being uploaded right now. */
    UPLOADING,

    /** The server has it. */
    SYNCED,

    /** Uploading failed in a way that waiting will not fix; see [Document.syncError]. */
    FAILED,

    /** Deleted on this phone; the server has not confirmed yet. Never shown in the library. */
    PENDING_DELETE,
}

data class Document(
    val id: String,
    val title: String,
    val tags: List<String>,
    val pageCount: Int,
    val sizeBytes: Long?,
    val capturedAt: Instant,
    val updatedAt: Instant,
    val syncState: SyncState,
    /** Title or tags were changed here and the change has not reached the server yet. */
    val hasUnsyncedChanges: Boolean,
    /** Why the last upload failed, when [syncState] is [SyncState.FAILED]. */
    val syncError: AppError?,
    /** The PDF is stored (encrypted) on this phone and can be opened without a connection. */
    val storedOnDevice: Boolean,
)

data class DocumentPage(
    val id: String,
    val position: Int,
    val width: Int,
    val height: Int,
)

data class User(
    val id: String,
    val email: String,
    val name: String,
    val mustChangePassword: Boolean,
    val storageUsedBytes: Long,
    val storageQuotaBytes: Long?,
)

/** A device signed in to the account. */
data class DeviceSession(
    val id: String,
    val deviceName: String,
    val model: String,
    val createdAt: Instant?,
    val lastUsedAt: Instant?,
    val isThisDevice: Boolean,
)

/** The server this phone is paired with, and what it said about itself. */
data class PairedServer(
    val url: String,
    /** SHA-256 of the pinned CA certificate; null when the system trust store is used. */
    val caFingerprint: String?,
    val name: String?,
    val serverVersion: String?,
    val serverOcr: Boolean,
    val ai: Boolean,
)

/** What a pairing QR code contains. */
data class PairingCode(
    val serverUrl: String,
    val caFingerprint: String?,
)

sealed interface AuthState {
    data object SignedOut : AuthState

    /** Signed in with a temporary password: nothing works until it is changed. */
    data class PasswordChangeRequired(val user: User) : AuthState

    data class SignedIn(val user: User) : AuthState
}
