package app.scanova.core.network.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** Marks a call that must be sent without the bearer token. Stripped before sending. */
internal const val NO_AUTH_HEADER = "X-Scanova-No-Auth"
private const val NO_AUTH = "$NO_AUTH_HEADER: 1"

/**
 * The client API (docs/ARCHITECTURE.md §7.3), relative to `https://<host>/api/v1/`.
 * Every call returns the raw HTTP response so the envelope, status and headers
 * can be mapped in one place ([ApiCallExecutor]).
 */
internal interface ScanovaApi {

    @Headers(NO_AUTH)
    @GET("server-info")
    suspend fun serverInfo(): Response<Envelope<ServerInfoDto>>

    @Headers(NO_AUTH)
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): Response<Envelope<TokensDto>>

    @Headers(NO_AUTH)
    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshTokenRequest): Response<Envelope<SuccessDto>>

    @GET("me")
    suspend fun me(): Response<Envelope<UserDto>>

    @PATCH("me")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): Response<Envelope<UserDto>>

    @POST("me/password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): Response<Envelope<TokensDto>>

    @GET("me/sessions")
    suspend fun sessions(): Response<Envelope<List<SessionDto>>>

    @DELETE("me/sessions/{id}")
    suspend fun revokeSession(@Path("id") id: String): Response<Envelope<SuccessDto>>

    @GET("documents")
    suspend fun listDocuments(
        @Query("limit") limit: Int,
        @Query("cursor") cursor: String?,
        @Query("updatedSince") updatedSince: String?,
    ): Response<Envelope<List<DocumentDto>>>

    @POST("documents")
    suspend fun createDocument(@Body body: CreateDocumentRequest): Response<Envelope<DocumentDto>>

    @PATCH("documents/{id}")
    suspend fun updateDocument(
        @Path("id") id: String,
        @Body body: UpdateDocumentRequest,
    ): Response<Envelope<DocumentDto>>

    @DELETE("documents/{id}")
    suspend fun deleteDocument(@Path("id") id: String): Response<Envelope<SuccessDto>>

    @GET("documents/{id}/text")
    suspend fun documentText(@Path("id") id: String): Response<Envelope<DocumentTextDto>>

    @PUT("documents/{id}/file")
    suspend fun uploadFile(
        @Path("id") id: String,
        @Header("X-Content-SHA256") sha256Hex: String,
        @Body body: RequestBody,
    ): Response<Envelope<DocumentDto>>

    @Streaming
    @GET("documents/{id}/file")
    suspend fun downloadFile(
        @Path("id") id: String,
        @Query("variant") variant: String,
    ): Response<ResponseBody>

    // ── App updates: static files served by the edge next to the API (not enveloped) ──

    /** The newest published Android build. 404 while none has been published. */
    @GET("/downloads/android/latest.json")
    suspend fun latestAndroidRelease(): Response<ResponseBody>

    @Streaming
    @GET("/downloads/android/{file}")
    suspend fun downloadAndroidBuild(@Path("file") file: String): Response<ResponseBody>
}
