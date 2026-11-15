package app.scanova.core.data.auth

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.coroutines.ApplicationScope
import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.backup.BackupRepository
import app.scanova.core.data.model.AuthState
import app.scanova.core.data.model.DeviceSession
import app.scanova.core.data.model.User
import app.scanova.core.data.storage.LocalDataCleaner
import app.scanova.core.data.sync.SyncScheduler
import app.scanova.core.data.util.parseInstantOrNull
import app.scanova.core.network.ScanovaRemote
import app.scanova.core.network.api.SessionDto
import app.scanova.core.network.api.TokensDto
import app.scanova.core.network.api.UserDto
import app.scanova.core.network.auth.SessionEvent
import app.scanova.core.network.auth.SessionEvents
import app.scanova.core.network.auth.toStored
import app.scanova.core.security.store.EncryptedKeyValueStore
import app.scanova.core.security.store.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The account signed in on this phone. Members only: administrators use the web console. */
interface AuthRepository {
    val authState: Flow<AuthState>

    suspend fun signIn(email: String, password: String): AppResult<User>

    /**
     * Creates an account on the server (open sign-up) and signs it in. Documents
     * already on this phone are then backed up to it automatically.
     */
    suspend fun signUp(name: String, email: String, password: String): AppResult<User>

    /** Changes the password. On success the server revokes every other session. */
    suspend fun changePassword(currentPassword: String, newPassword: String): AppResult<User>

    /** Fetches the account from the server (name, storage use). */
    suspend fun refreshUser(): AppResult<User>

    suspend fun updateName(name: String): AppResult<User>

    suspend fun sessions(): AppResult<List<DeviceSession>>

    suspend fun revokeSession(id: String): AppResult<Unit>

    /**
     * Signs out and erases this account's documents from the phone. Documents
     * that were not uploaded yet are lost; the caller warns the user first.
     */
    suspend fun signOut()
}

internal class AuthRepositoryImpl(
    private val remote: ScanovaRemote,
    private val tokenStore: TokenStore,
    private val values: EncryptedKeyValueStore,
    private val deviceInfo: DeviceInfoProvider,
    private val cleaner: LocalDataCleaner,
    private val syncScheduler: SyncScheduler,
    private val dispatchers: AppDispatchers,
    sessionEvents: SessionEvents,
    @ApplicationScope scope: CoroutineScope,
) : AuthRepository {

    init {
        // Keep the cached account in step with what a token refresh returned.
        scope.launch(dispatchers.io) {
            sessionEvents.events.collect { event ->
                if (event is SessionEvent.Refreshed) cacheUser(event.user)
            }
        }
    }

    override val authState: Flow<AuthState> =
        combine(tokenStore.observeSignedIn(), values.observe(KEY_USER)) { signedIn, userJson ->
            val user = userJson?.let(::decodeUser)
            when {
                !signedIn || user == null -> AuthState.SignedOut
                user.mustChangePassword -> AuthState.PasswordChangeRequired(user.toDomain())
                else -> AuthState.SignedIn(user.toDomain())
            }
        }.distinctUntilChanged().flowOn(dispatchers.io)

    override suspend fun signIn(email: String, password: String): AppResult<User> = withContext(dispatchers.io) {
        when (val result = remote.login(email.trim(), password, deviceInfo.current())) {
            is AppResult.Failure -> result
            is AppResult.Success -> adopt(result.value)
        }
    }

    override suspend fun signUp(name: String, email: String, password: String): AppResult<User> =
        withContext(dispatchers.io) {
            when (val result = remote.register(email.trim(), name.trim(), password, deviceInfo.current())) {
                is AppResult.Failure -> result
                is AppResult.Success -> adopt(result.value)
            }
        }

    /** Makes [tokens] the session on this phone (after sign-in or sign-up). */
    private suspend fun adopt(tokens: TokensDto): AppResult<User> {
        if (tokens.user.userType != USER_TYPE_MEMBER) {
            // The server refuses administrators itself; this guards the same rule on
            // the phone and drops the session at once.
            remote.logout(tokens.refreshToken)
            return AppResult.Failure(AppError.Api(ApiErrorCode.ADMIN_WEB_ONLY, HTTP_FORBIDDEN))
        }
        // Another account's documents must never be visible to this one. Documents
        // made while signed out belong to nobody yet and are backed up to this account.
        val previousUserId = values.get(KEY_LAST_USER_ID)
        if (previousUserId != null && previousUserId != tokens.user.id) cleaner.wipeAccountData()
        startSession(tokens)
        return AppResult.Success(tokens.user.toCached().toDomain())
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String): AppResult<User> =
        withContext(dispatchers.io) {
            when (val result = remote.changePassword(currentPassword, newPassword)) {
                is AppResult.Failure -> result
                is AppResult.Success -> {
                    startSession(result.value)
                    AppResult.Success(result.value.user.toCached().toDomain())
                }
            }
        }

    override suspend fun refreshUser(): AppResult<User> = withContext(dispatchers.io) {
        when (val result = remote.me()) {
            is AppResult.Success -> {
                cacheUser(result.value)
                AppResult.Success(result.value.toCached().toDomain())
            }
            is AppResult.Failure -> {
                notePasswordChangeRequired(result.error)
                result
            }
        }
    }

    override suspend fun updateName(name: String): AppResult<User> = withContext(dispatchers.io) {
        when (val result = remote.updateProfile(name.trim())) {
            is AppResult.Success -> {
                cacheUser(result.value)
                AppResult.Success(result.value.toCached().toDomain())
            }
            is AppResult.Failure -> {
                notePasswordChangeRequired(result.error)
                result
            }
        }
    }

    override suspend fun sessions(): AppResult<List<DeviceSession>> = withContext(dispatchers.io) {
        when (val result = remote.sessions()) {
            is AppResult.Success -> AppResult.Success(
                result.value.map { it.toDomain() }.sortedWith(
                    compareByDescending<DeviceSession> { it.isThisDevice }.thenByDescending { it.lastUsedAt },
                ),
            )
            is AppResult.Failure -> {
                notePasswordChangeRequired(result.error)
                result
            }
        }
    }

    override suspend fun revokeSession(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        when (val result = remote.revokeSession(id)) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> result
        }
    }

    override suspend fun signOut() = withContext(dispatchers.io) {
        // Tokens first: from here no request, refresh or sync can act for this account,
        // and a refresh already on the network cannot store new tokens (see TokenStore.replace).
        val tokens = tokenStore.current()
        tokenStore.clear()
        // Best effort: the session is ended locally even when the server cannot be told.
        // The server also ends the session for a token a racing refresh already exchanged.
        tokens?.let { remote.logout(it.refreshToken) }
        cleaner.wipeAccountData()
        // Forgotten only after the wipe: if the wipe is interrupted, the next sign-in by
        // another account still sees a different last user and wipes again.
        values.edit { stored ->
            stored.remove(KEY_USER)
            stored.remove(KEY_LAST_USER_ID)
            stored.remove(BackupRepository.KEY_LAST_BACKUP)
        }
    }

    private fun startSession(tokens: TokensDto) {
        // The account is cached before the tokens appear, so observers never
        // see "signed in" without knowing who is signed in.
        values.edit { stored ->
            stored[KEY_USER] = JSON.encodeToString(CachedUser.serializer(), tokens.user.toCached())
            stored[KEY_LAST_USER_ID] = tokens.user.id
        }
        tokenStore.save(tokens.toStored())
        if (!tokens.user.mustChangePassword) {
            syncScheduler.startPeriodicSync()
            syncScheduler.requestSync()
        }
    }

    private fun cacheUser(user: UserDto) {
        values.put(KEY_USER, JSON.encodeToString(CachedUser.serializer(), user.toCached()))
    }

    /** The server can demand a password change at any time (an administrator reset it). */
    private fun notePasswordChangeRequired(error: AppError) {
        if (error is AppError.Api && error.code == ApiErrorCode.PASSWORD_CHANGE_REQUIRED) {
            val cached = values.get(KEY_USER)?.let(::decodeUser) ?: return
            if (!cached.mustChangePassword) {
                values.put(KEY_USER, JSON.encodeToString(CachedUser.serializer(), cached.copy(mustChangePassword = true)))
            }
        }
    }

    private fun decodeUser(json: String): CachedUser? =
        runCatching { JSON.decodeFromString(CachedUser.serializer(), json) }.getOrNull()

    private fun UserDto.toCached() = CachedUser(
        id = id,
        email = email,
        name = name,
        mustChangePassword = mustChangePassword,
        storageUsedBytes = storageUsedBytes,
        storageQuotaBytes = storageQuotaBytes,
    )

    private fun CachedUser.toDomain() = User(
        id = id,
        email = email,
        name = name,
        mustChangePassword = mustChangePassword,
        storageUsedBytes = storageUsedBytes,
        storageQuotaBytes = storageQuotaBytes,
    )

    private fun SessionDto.toDomain() = DeviceSession(
        id = id,
        deviceName = deviceName.orEmpty(),
        model = model.orEmpty(),
        createdAt = parseInstantOrNull(createdAt),
        lastUsedAt = parseInstantOrNull(lastUsedAt),
        isThisDevice = current,
    )

    /** The account as kept (encrypted) on the phone between app starts. */
    @Serializable
    private data class CachedUser(
        val id: String,
        val email: String,
        val name: String,
        val mustChangePassword: Boolean,
        val storageUsedBytes: Long,
        val storageQuotaBytes: Long?,
    )

    private companion object {
        const val KEY_USER = "session.user"
        const val KEY_LAST_USER_ID = "session.last_user_id"
        const val USER_TYPE_MEMBER = "MEMBER"
        const val HTTP_FORBIDDEN = 403
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
