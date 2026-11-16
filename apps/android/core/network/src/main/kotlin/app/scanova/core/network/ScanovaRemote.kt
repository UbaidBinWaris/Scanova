package app.scanova.core.network

import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.network.api.AiRuntimeDto
import app.scanova.core.network.api.AndroidReleaseDto
import app.scanova.core.network.api.ApiCallExecutor
import app.scanova.core.network.api.ApiJson
import app.scanova.core.network.api.ChangePasswordRequest
import app.scanova.core.network.api.CreateDocumentRequest
import app.scanova.core.network.api.DeviceDto
import app.scanova.core.network.api.DocumentDto
import app.scanova.core.network.api.DocumentPageDto
import app.scanova.core.network.api.DocumentPagesDto
import app.scanova.core.network.api.DocumentTextDto
import app.scanova.core.network.api.LoginRequest
import app.scanova.core.network.api.Page
import app.scanova.core.network.api.PageInputDto
import app.scanova.core.network.api.RefreshTokenRequest
import app.scanova.core.network.api.RegisterRequest
import app.scanova.core.network.api.ReplacePagesRequest
import app.scanova.core.network.api.ScanovaApi
import app.scanova.core.network.api.ServerInfoDto
import app.scanova.core.network.api.SessionDto
import app.scanova.core.network.api.SetAiRuntimeRequest
import app.scanova.core.network.api.SuccessDto
import app.scanova.core.network.api.TokensDto
import app.scanova.core.network.api.UpdateDocumentRequest
import app.scanova.core.network.api.UpdateProfileRequest
import app.scanova.core.network.api.UserDto
import java.io.InputStream
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import retrofit2.Response
import okio.BufferedSink
import okio.source

/**
 * The server, as the rest of the app sees it: one suspend function per
 * endpoint, each returning a typed [AppResult]. Calls go to the currently
 * paired server; without a pairing they fail with [AppError.NotPaired].
 */
class ScanovaRemote internal constructor(
    private val currentConfig: () -> ServerConfig?,
    private val clientFactory: ScanovaClientFactory,
    private val executor: ApiCallExecutor,
) {
    private val lock = Any()
    private var cachedConfig: ServerConfig? = null
    private var cachedApi: ScanovaApi? = null

    /** Talks to a server that is not (yet) the saved pairing; used to verify one while pairing. */
    suspend fun serverInfo(candidate: ServerConfig): AppResult<ServerInfoDto> {
        val api = clientFactory.create(candidate)
        return executor.data { api.serverInfo() }
    }

    suspend fun serverInfo(): AppResult<ServerInfoDto> = withApi { api -> executor.data { api.serverInfo() } }

    suspend fun login(email: String, password: String, device: DeviceDto): AppResult<TokensDto> =
        withApi { api -> executor.data { api.login(LoginRequest(email = email, password = password, device = device)) } }

    /** Creates an account (open sign-up) and signs it in on this phone. */
    suspend fun register(email: String, name: String, password: String, device: DeviceDto): AppResult<TokensDto> =
        withApi { api ->
            executor.data { api.register(RegisterRequest(email = email, name = name, password = password, device = device)) }
        }

    suspend fun logout(refreshToken: String): AppResult<SuccessDto> =
        withApi { api -> executor.data { api.logout(RefreshTokenRequest(refreshToken)) } }

    suspend fun me(): AppResult<UserDto> = withApi { api -> executor.data { api.me() } }

    suspend fun updateProfile(name: String): AppResult<UserDto> =
        withApi { api -> executor.data { api.updateProfile(UpdateProfileRequest(name)) } }

    suspend fun changePassword(currentPassword: String, newPassword: String): AppResult<TokensDto> =
        withApi { api -> executor.data { api.changePassword(ChangePasswordRequest(currentPassword, newPassword)) } }

    suspend fun sessions(): AppResult<List<SessionDto>> = withApi { api -> executor.data { api.sessions() } }

    /** The server AI switch. `FORBIDDEN` for everyone but an owner. */
    suspend fun aiRuntime(): AppResult<AiRuntimeDto> = withApi { api -> executor.data { api.aiRuntime() } }

    suspend fun setAiRuntime(on: Boolean): AppResult<AiRuntimeDto> =
        withApi { api -> executor.data { api.setAiRuntime(SetAiRuntimeRequest(on)) } }

    /** An open app checks in, so the server keeps the AI loaded. */
    suspend fun aiRuntimeHeartbeat(): AppResult<AiRuntimeDto> =
        withApi { api -> executor.data { api.aiRuntimeHeartbeat() } }

    suspend fun revokeSession(id: String): AppResult<SuccessDto> =
        withApi { api -> executor.data { api.revokeSession(id) } }

    suspend fun listDocuments(limit: Int, cursor: String?, updatedSince: String?): AppResult<Page<DocumentDto>> =
        withApi { api -> executor.page { api.listDocuments(limit, cursor, updatedSince) } }

    suspend fun createDocument(request: CreateDocumentRequest): AppResult<DocumentDto> =
        withApi { api -> executor.data { api.createDocument(request) } }

    suspend fun updateDocument(id: String, title: String?, tags: List<String>?): AppResult<DocumentDto> =
        withApi { api -> executor.data { api.updateDocument(id, UpdateDocumentRequest(title, tags)) } }

    suspend fun deleteDocument(id: String): AppResult<SuccessDto> =
        withApi { api -> executor.data { api.deleteDocument(id) } }

    suspend fun documentText(id: String): AppResult<DocumentTextDto> =
        withApi { api -> executor.data { api.documentText(id) } }

    /**
     * Uploads a PDF. [open] is called for each attempt (a call can be replayed
     * after a token refresh) and must return a fresh stream of exactly
     * [length] bytes whose SHA-256 is [sha256Hex].
     */
    suspend fun uploadFile(
        id: String,
        sha256Hex: String,
        length: Long,
        open: () -> InputStream,
    ): AppResult<DocumentDto> = withApi { api ->
        executor.data { api.uploadFile(id, sha256Hex, StreamingBody(PDF_MEDIA_TYPE, length, open)) }
    }

    /** The page list of a document: order, size, recipe and original of each page. */
    suspend fun documentPages(id: String): AppResult<DocumentPagesDto> =
        withApi { api -> executor.data { api.documentPages(id) } }

    /** Replaces the page list on the server. Pages left out are deleted there with their originals. */
    suspend fun replacePages(id: String, pages: List<PageInputDto>): AppResult<DocumentPagesDto> =
        withApi { api -> executor.data { api.replacePages(id, ReplacePagesRequest(pages)) } }

    /**
     * Uploads a page's original photo (JPEG). Like [uploadFile], [open] is called
     * for each attempt and must return exactly [length] bytes hashing to [sha256Hex].
     */
    suspend fun uploadPageOriginal(
        id: String,
        pageId: String,
        sha256Hex: String,
        length: Long,
        open: () -> InputStream,
    ): AppResult<DocumentPageDto> = withApi { api ->
        executor.data { api.uploadPageOriginal(id, pageId, sha256Hex, StreamingBody(JPEG_MEDIA_TYPE, length, open)) }
    }

    /** Downloads a page's original photo and hands the byte stream to [consume] without buffering it. */
    suspend fun <R> downloadPageOriginal(
        id: String,
        pageId: String,
        consume: suspend (InputStream) -> R,
    ): AppResult<R> = withApi { api ->
        executor.raw(
            call = { api.downloadPageOriginal(id, pageId) },
            onBody = { body -> consume(body.byteStream()) },
        )
    }

    /** Downloads a document's PDF and hands the byte stream to [consume] without buffering it. */
    suspend fun <R> downloadFile(
        id: String,
        searchable: Boolean,
        consume: suspend (InputStream) -> R,
    ): AppResult<R> = withApi { api ->
        executor.raw(
            call = { api.downloadFile(id, if (searchable) "searchable" else "original") },
            onBody = { body -> consume(body.byteStream()) },
        )
    }

    /**
     * The newest Android build published on the paired server, or `null` when none
     * has been published. Goes to the paired server only, over the pinned connection.
     */
    suspend fun latestAndroidRelease(): AppResult<AndroidReleaseDto?> = withApi { api ->
        executor.raw(
            // 404 here means "nothing published yet", not "this is not a Scanova server".
            call = { api.latestAndroidRelease().let { if (it.code() == 404) Response.success(ByteArray(0).toResponseBody(null)) else it } },
            onBody = { body ->
                val text = body.string()
                if (text.isEmpty()) null else ApiJson.decodeFromString(AndroidReleaseDto.serializer(), text)
            },
        )
    }

    /** Streams a published APK to [consume], with its length when the server sends one. */
    suspend fun <R> downloadAndroidBuild(file: String, consume: suspend (InputStream, Long) -> R): AppResult<R> =
        withApi { api ->
            executor.raw(
                call = { api.downloadAndroidBuild(file) },
                onBody = { body -> consume(body.byteStream(), body.contentLength()) },
            )
        }

    private suspend fun <T> withApi(block: suspend (ScanovaApi) -> AppResult<T>): AppResult<T> {
        val api = currentApi() ?: return AppResult.Failure(AppError.NotPaired)
        return block(api)
    }

    private fun currentApi(): ScanovaApi? = synchronized(lock) {
        val config = currentConfig()
        if (config == null) {
            cachedConfig = null
            cachedApi = null
            return null
        }
        cachedApi?.takeIf { cachedConfig == config } ?: clientFactory.create(config).also {
            cachedConfig = config
            cachedApi = it
        }
    }

    /** A request body read from [open] while it is sent, never held in memory. */
    private class StreamingBody(
        private val mediaType: MediaType,
        private val length: Long,
        private val open: () -> InputStream,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            open().use { input -> sink.writeAll(input.source()) }
        }
    }

    private companion object {
        val PDF_MEDIA_TYPE = "application/pdf".toMediaType()
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}
