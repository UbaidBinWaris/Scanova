package app.scanova.core.data.pairing

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.data.model.PairedServer
import app.scanova.core.data.model.PairingCode
import app.scanova.core.data.storage.LocalDataCleaner
import app.scanova.core.network.ScanovaRemote
import app.scanova.core.network.ServerConfig
import app.scanova.core.network.ServerConfigStore
import app.scanova.core.network.api.ServerInfoDto
import app.scanova.core.network.pairing.PairingUri
import app.scanova.core.network.pairing.ServerAddress
import app.scanova.core.network.tls.CaCertificateFetcher
import app.scanova.core.network.tls.CaFingerprint
import app.scanova.core.security.store.EncryptedKeyValueStore
import app.scanova.core.security.store.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Connecting this phone to a server (docs/ARCHITECTURE.md §9, "Pairing"). */
interface PairingRepository {
    /** The paired server, or null while the phone is not paired. */
    val pairedServer: Flow<PairedServer?>

    /** Reads a scanned QR code. Returns null when it is not a Scanova pairing code. */
    fun parsePairingCode(text: String): PairingCode?

    /** True when [fingerprint] is written like a SHA-256 fingerprint (separators and case are free). */
    fun isValidFingerprint(fingerprint: String): Boolean

    /** True when [serverUrl] is an address this app can pair with (HTTPS only). */
    fun isValidServerUrl(serverUrl: String): Boolean

    /**
     * Pairs with [serverUrl]. With a [caFingerprint] the server's CA
     * certificate is downloaded, checked against it and pinned; without one the
     * server must present a publicly trusted certificate.
     */
    suspend fun pair(serverUrl: String, caFingerprint: String?): AppResult<PairedServer>

    /** Asks the paired server for its current name, version and features. */
    suspend fun refreshServerInfo(): AppResult<PairedServer>

    /** Forgets the server and erases everything stored on this phone. */
    suspend fun unpair()
}

internal class PairingRepositoryImpl(
    private val configStore: ServerConfigStore,
    private val values: EncryptedKeyValueStore,
    private val tokenStore: TokenStore,
    private val remote: ScanovaRemote,
    private val caFetcher: CaCertificateFetcher,
    private val cleaner: LocalDataCleaner,
    private val dispatchers: AppDispatchers,
) : PairingRepository {

    override val pairedServer: Flow<PairedServer?> =
        combine(configStore.observe(), values.observeAll()) { config, stored -> config?.toPairedServer(stored) }
            .distinctUntilChanged()
            .flowOn(dispatchers.io)

    override fun parsePairingCode(text: String): PairingCode? =
        PairingUri.parse(text)?.let { PairingCode(it.address.baseUrl, it.caFingerprint) }

    override fun isValidFingerprint(fingerprint: String): Boolean = CaFingerprint.normalize(fingerprint) != null

    override fun isValidServerUrl(serverUrl: String): Boolean = ServerAddress.parse(serverUrl) != null

    override suspend fun pair(serverUrl: String, caFingerprint: String?): AppResult<PairedServer> =
        withContext(dispatchers.io) {
            val address = ServerAddress.parse(serverUrl)
                ?: return@withContext AppResult.Failure(AppError.InvalidServerAddress)

            val certificate = if (caFingerprint.isNullOrBlank()) {
                null
            } else {
                val fingerprint = CaFingerprint.normalize(caFingerprint)
                    ?: return@withContext AppResult.Failure(AppError.FingerprintMismatch)
                when (val fetched = caFetcher.fetchVerified(address.caCertificateUrl, fingerprint)) {
                    is AppResult.Success -> fetched.value
                    is AppResult.Failure -> return@withContext fetched
                }
            }

            // Prove the pairing works before saving it: this call uses the pinned
            // CA and full host-name verification, exactly like every later call.
            val candidate = ServerConfig(address, certificate)
            val info = when (val result = remote.serverInfo(candidate)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return@withContext result
            }
            if (info.apiVersion != SUPPORTED_API_VERSION) {
                return@withContext AppResult.Failure(AppError.UnexpectedResponse)
            }

            // A different server means a different account space: never carry a session over.
            if (configStore.current()?.address != address) tokenStore.clear()
            configStore.save(candidate)
            storeServerInfo(info)
            AppResult.Success(candidate.toPairedServer(values.observeAllSnapshot()))
        }

    override suspend fun refreshServerInfo(): AppResult<PairedServer> = withContext(dispatchers.io) {
        val config = configStore.current() ?: return@withContext AppResult.Failure(AppError.NotPaired)
        when (val result = remote.serverInfo()) {
            is AppResult.Success -> {
                storeServerInfo(result.value)
                AppResult.Success(config.toPairedServer(values.observeAllSnapshot()))
            }
            is AppResult.Failure -> result
        }
    }

    override suspend fun unpair() {
        cleaner.wipeEverything()
    }

    private fun storeServerInfo(info: ServerInfoDto) {
        values.edit { stored ->
            stored[KEY_NAME] = info.name
            stored[KEY_VERSION] = info.serverVersion
            stored[KEY_OCR] = info.features.serverOcr.toString()
            stored[KEY_AI] = info.features.ai.toString()
        }
    }

    private fun EncryptedKeyValueStore.observeAllSnapshot(): Map<String, String> =
        listOf(KEY_NAME, KEY_VERSION, KEY_OCR, KEY_AI).mapNotNull { key -> get(key)?.let { key to it } }.toMap()

    private fun ServerConfig.toPairedServer(stored: Map<String, String>): PairedServer = PairedServer(
        url = address.baseUrl,
        caFingerprint = caFingerprint,
        name = stored[KEY_NAME],
        serverVersion = stored[KEY_VERSION],
        serverOcr = stored[KEY_OCR].toBoolean(),
        ai = stored[KEY_AI].toBoolean(),
    )

    private companion object {
        const val SUPPORTED_API_VERSION = "1"
        const val KEY_NAME = "server.name"
        const val KEY_VERSION = "server.version"
        const val KEY_OCR = "server.feature.ocr"
        const val KEY_AI = "server.feature.ai"
    }
}
