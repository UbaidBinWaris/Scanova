package app.scanova.core.security.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The signed-in session's credentials. `toString` is overridden so that an
 * accidental log statement or crash report can never print a token.
 */
@Serializable
data class StoredTokens(
    val accessToken: String,
    val accessTokenExpiresAtEpochMs: Long,
    val refreshToken: String,
    val refreshTokenExpiresAtEpochMs: Long,
) {
    override fun toString(): String = "StoredTokens(redacted)"
}

/** Where the session's tokens live between app starts. */
interface TokenStore {
    fun current(): StoredTokens?

    fun save(tokens: StoredTokens)

    fun clear()

    /**
     * Stores [tokens] only if the session still holds [expectedRefreshToken], in
     * one step. A token refresh uses this, so a sign-out (or a newer session) that
     * happened while the refresh was on the network is never undone by it.
     */
    fun replace(expectedRefreshToken: String, tokens: StoredTokens): Boolean = synchronized(this) {
        if (current()?.refreshToken != expectedRefreshToken) return false
        save(tokens)
        true
    }

    /** Emits whether a session exists, now and on every change. */
    fun observeSignedIn(): Flow<Boolean>
}

/** [TokenStore] on top of the encrypted key-value file. Nothing is kept in plaintext on disk. */
class EncryptedTokenStore(
    private val store: EncryptedKeyValueStore,
) : TokenStore {

    override fun current(): StoredTokens? =
        store.get(KEY)?.let { runCatching { JSON.decodeFromString<StoredTokens>(it) }.getOrNull() }

    // save and clear share the monitor that [TokenStore.replace] holds.
    override fun save(tokens: StoredTokens) = synchronized(this) {
        store.put(KEY, JSON.encodeToString(StoredTokens.serializer(), tokens))
    }

    override fun clear() = synchronized(this) {
        store.remove(KEY)
    }

    override fun observeSignedIn(): Flow<Boolean> = store.observe(KEY).map { it != null }

    private companion object {
        const val KEY = "session.tokens"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
