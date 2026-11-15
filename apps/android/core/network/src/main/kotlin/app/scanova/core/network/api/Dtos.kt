package app.scanova.core.network.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire shapes from docs/ARCHITECTURE.md §7. Field names are the contract.
// Classes that carry credentials override toString so they cannot be logged.

/** The envelope every JSON response uses: exactly one of [data] and [error] is set. */
@Serializable
data class Envelope<T>(
    val data: T? = null,
    val error: ErrorDto? = null,
    val meta: MetaDto? = null,
)

@Serializable
data class ErrorDto(
    val code: String? = null,
    val message: String? = null,
    val details: JsonElement? = null,
)

@Serializable
data class MetaDto(
    val nextCursor: String? = null,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String,
    val userType: String,
    val status: String,
    val mustChangePassword: Boolean = false,
    val storageUsedBytes: Long = 0,
    val storageQuotaBytes: Long? = null,
    val createdAt: String,
)

@Serializable
data class TokensDto(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
    val user: UserDto,
) {
    override fun toString(): String = "TokensDto(redacted)"
}

@Serializable
data class SessionDto(
    val id: String,
    val deviceName: String? = null,
    val platform: String? = null,
    val model: String? = null,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val current: Boolean = false,
)

@Serializable
data class DocumentDto(
    val id: String,
    val title: String,
    val tags: List<String> = emptyList(),
    val pageCount: Int = 0,
    val sizeBytes: Long? = null,
    val contentSha256: String? = null,
    val status: String,
    val ocrStatus: String = "NONE",
    val hasSearchablePdf: Boolean = false,
    val capturedAt: String,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
    /** Last change to the page list or a page's original; absent on servers without page backup. */
    val pagesUpdatedAt: String? = null,
)

/** One page of `PUT documents/:id/pages`. [edit] is the page's recipe as JSON, left out when there is none. */
@Serializable
data class PageInputDto(
    val id: String,
    val position: Int,
    val width: Int,
    val height: Int,
    val edit: JsonElement? = null,
)

@Serializable
data class ReplacePagesRequest(val pages: List<PageInputDto>)

@Serializable
data class DocumentPageDto(
    val id: String,
    val position: Int,
    val width: Int,
    val height: Int,
    val edit: JsonElement? = null,
    val hasOriginal: Boolean = false,
    val originalSha256: String? = null,
    val originalSizeBytes: Long? = null,
    val updatedAt: String,
)

/** `GET` / `PUT documents/:id/pages`: the page list in order. */
@Serializable
data class DocumentPagesDto(
    val pagesUpdatedAt: String? = null,
    val pages: List<DocumentPageDto> = emptyList(),
)

@Serializable
data class ServerInfoDto(
    val name: String,
    val apiVersion: String,
    val serverVersion: String,
    val features: ServerFeaturesDto = ServerFeaturesDto(),
)

@Serializable
data class ServerFeaturesDto(
    val serverOcr: Boolean = false,
    val ai: Boolean = false,
    /** The server accepts new accounts from the app (`POST auth/register`). */
    val signup: Boolean = false,
)

@Serializable
data class DeviceDto(
    val name: String,
    val platform: String = "android",
    val model: String,
    val appVersion: String,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val client: String = "mobile",
    val device: DeviceDto,
) {
    override fun toString(): String = "LoginRequest(redacted)"
}

@Serializable
data class RegisterRequest(
    val email: String,
    val name: String,
    val password: String,
    val client: String = "mobile",
    val device: DeviceDto,
) {
    override fun toString(): String = "RegisterRequest(redacted)"
}

@Serializable
data class RefreshTokenRequest(val refreshToken: String) {
    override fun toString(): String = "RefreshTokenRequest(redacted)"
}

@Serializable
data class UpdateProfileRequest(val name: String)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
) {
    override fun toString(): String = "ChangePasswordRequest(redacted)"
}

@Serializable
data class CreateDocumentRequest(
    val id: String,
    val title: String,
    val pageCount: Int,
    val capturedAt: String,
    val tags: List<String>? = null,
    val clientOcrText: String? = null,
)

@Serializable
data class UpdateDocumentRequest(
    val title: String? = null,
    val tags: List<String>? = null,
)

@Serializable
data class SuccessDto(val success: Boolean = true)

@Serializable
data class DocumentTextDto(
    val text: String,
    val source: String,
)
