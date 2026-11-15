package app.scanova.core.network.api

import kotlinx.serialization.Serializable

/**
 * `/downloads/android/latest.json` on the paired server: the newest published
 * build (written by infra/deploy/publish-apk.sh). Not an API envelope.
 */
@Serializable
data class AndroidReleaseDto(
    val schemaVersion: Int = 1,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val file: String,
    val sizeBytes: Long,
    val sha256: String,
    val signingCertSha256: String,
    val minSupportedVersionCode: Long = 0,
    val publishedAt: String = "",
    val notes: List<String> = emptyList(),
)
