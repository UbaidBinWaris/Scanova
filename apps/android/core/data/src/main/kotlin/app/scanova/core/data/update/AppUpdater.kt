package app.scanova.core.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.network.ScanovaRemote
import app.scanova.core.network.api.AndroidReleaseDto
import app.scanova.core.security.store.EncryptedKeyValueStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/** A build newer than this one, published on the paired server. */
data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val file: String,
    val sizeBytes: Long,
    val sha256: String,
    val signingCertSha256: String,
    /** This build is older than the oldest the server still supports: no "Later". */
    val required: Boolean,
    val notes: List<String>,
)

/** Why an update could not be fetched or installed, in terms the screen can explain. */
enum class UpdateFailure {
    NO_CONNECTION,
    SERVER_PROBLEM,
    NOT_ENOUGH_SPACE,
    DAMAGED_DOWNLOAD,
    NOT_THIS_APP,
    NOT_NEWER,
    SIGNED_DIFFERENTLY,
}

sealed interface UpdateResult<out T> {
    data class Success<T>(val value: T) : UpdateResult<T>
    data class Failure(val reason: UpdateFailure) : UpdateResult<Nothing>
}

/**
 * Keeps the sideloaded app up to date from the server it is paired with.
 *
 * The release workflow publishes every signed build next to the API
 * (`/downloads/android/`, infra/deploy/publish-apk.sh). [check] compares the
 * newest one with this build, [download] fetches the whole APK into the cache
 * (resuming an interrupted download) and verifies its size and SHA-256, and
 * [installProblem] refuses anything Android would reject or that is not signed
 * with this app's key. The app then hands the file to Android's installer,
 * which always asks the user to confirm. Data and sign-in are kept.
 *
 * Only the paired server is contacted, over the same pinned connection as the
 * API. The APK is a public file, so the plaintext cache copy is not a secret;
 * it is deleted once installed or replaced.
 */
class AppUpdater internal constructor(
    private val context: Context,
    private val remote: ScanovaRemote,
    private val values: EncryptedKeyValueStore,
    private val dispatchers: AppDispatchers,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val manualChecks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** "Check for updates" pressed somewhere (settings); the app's update dialog answers it. */
    val manualCheckRequests: SharedFlow<Unit> = manualChecks.asSharedFlow()

    fun requestCheck() {
        manualChecks.tryEmit(Unit)
    }

    val currentVersionCode: Long by lazy { installedPackage(0).longVersion() }

    val currentVersionName: String by lazy { installedPackage(0).versionName.orEmpty() }

    /** Directory the downloaded APKs live in (shared with the installer through a FileProvider). */
    val updatesDir: File get() = File(context.cacheDir, UPDATES_DIR)

    /** At most one automatic check every few hours, so opening the app stays quick and quiet. */
    fun shouldAutoCheck(): Boolean =
        now() - (values.get(KEY_LAST_CHECK)?.toLongOrNull() ?: 0L) >= AUTO_CHECK_INTERVAL_MS

    /** The newest build when it is newer than this one, `null` when this one is current. */
    suspend fun check(): UpdateResult<AppRelease?> = withContext(dispatchers.io) {
        when (val result = remote.latestAndroidRelease()) {
            is AppResult.Failure -> UpdateResult.Failure(result.error.toFailure())
            is AppResult.Success -> {
                values.put(KEY_LAST_CHECK, now().toString())
                val dto = result.value ?: return@withContext UpdateResult.Success(null)
                if (!dto.isWellFormed()) return@withContext UpdateResult.Failure(UpdateFailure.SERVER_PROBLEM)
                if (dto.packageName != context.packageName) {
                    return@withContext UpdateResult.Failure(UpdateFailure.NOT_THIS_APP)
                }
                if (dto.versionCode <= currentVersionCode) {
                    UpdateResult.Success(null)
                } else {
                    UpdateResult.Success(dto.toRelease(required = dto.minSupportedVersionCode > currentVersionCode))
                }
            }
        }
    }

    fun wasSkipped(release: AppRelease): Boolean =
        !release.required && (values.get(KEY_SKIPPED)?.toLongOrNull() ?: 0L) >= release.versionCode

    /** "Later": not offered again until a newer build appears. */
    fun skip(release: AppRelease) {
        values.put(KEY_SKIPPED, release.versionCode.toString())
    }

    /** Downloads [release] (or reuses an intact earlier download) and verifies it. */
    suspend fun download(release: AppRelease, onProgress: (Float) -> Unit): UpdateResult<File> =
        withContext(dispatchers.io) {
            val dir = updatesDir.apply { mkdirs() }
            val target = File(dir, release.file)
            val part = File(dir, "${release.file}.part")
            dir.listFiles()?.filter { it != target && it != part }?.forEach { it.delete() }
            if (target.isFile && target.length() == release.sizeBytes && sha256Hex(target) == release.sha256) {
                return@withContext UpdateResult.Success(target)
            }
            target.delete()
            if (dir.usableSpace < release.sizeBytes + FREE_SPACE_MARGIN) {
                return@withContext UpdateResult.Failure(UpdateFailure.NOT_ENOUGH_SPACE)
            }
            part.delete()
            val downloaded = remote.downloadAndroidBuild(release.file) { input, length ->
                val total = if (length > 0) length else release.sizeBytes
                var copied = 0L
                part.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied > release.sizeBytes) break
                        onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
                copied
            }
            when (downloaded) {
                is AppResult.Failure -> {
                    part.delete()
                    UpdateResult.Failure(downloaded.error.toFailure())
                }
                is AppResult.Success -> {
                    if (downloaded.value != release.sizeBytes || sha256Hex(part) != release.sha256) {
                        part.delete()
                        UpdateResult.Failure(UpdateFailure.DAMAGED_DOWNLOAD)
                    } else if (!part.renameTo(target)) {
                        part.delete()
                        UpdateResult.Failure(UpdateFailure.DAMAGED_DOWNLOAD)
                    } else {
                        UpdateResult.Success(target)
                    }
                }
            }
        }

    /**
     * Why Android would refuse [apk], or null when it can install. Android itself
     * only says "App not installed", so check first: this app, a newer version,
     * and signed with exactly the key of the installed app and of [release].
     */
    fun installProblem(apk: File, release: AppRelease): UpdateFailure? {
        val archive = archivePackage(apk) ?: return UpdateFailure.DAMAGED_DOWNLOAD
        if (archive.packageName != context.packageName) return UpdateFailure.NOT_THIS_APP
        if (archive.longVersion() <= currentVersionCode) return UpdateFailure.NOT_NEWER
        val archiveSigners = signerDigests(archive)
        val installedSigners = signerDigests(installedPackage(signingFlags()))
        if (archiveSigners.isEmpty() || archiveSigners != installedSigners) return UpdateFailure.SIGNED_DIFFERENTLY
        if (release.signingCertSha256 !in archiveSigners) return UpdateFailure.SIGNED_DIFFERENTLY
        return null
    }

    /** Android 8+ asks once per app before it may install other APKs. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Removes downloads this version already replaced and abandoned partial files. */
    suspend fun cleanUp() = withContext(dispatchers.io) {
        updatesDir.listFiles()?.forEach { file ->
            val stale = when {
                file.name.endsWith(".part") -> now() - file.lastModified() > STALE_PART_MS
                file.name.endsWith(".apk") -> (archivePackage(file)?.longVersion() ?: 0L) <= currentVersionCode
                else -> true
            }
            if (stale) file.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedPackage(flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            context.packageManager.getPackageInfo(context.packageName, flags)
        }

    @Suppress("DEPRECATION")
    private fun archivePackage(apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
        } else {
            context.packageManager.getPackageArchiveInfo(apk.path, signingFlags())
        }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signers: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signers.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
    }

    private companion object {
        const val UPDATES_DIR = "updates"
        const val KEY_LAST_CHECK = "update.last_check"
        const val KEY_SKIPPED = "update.skipped_version"
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val STALE_PART_MS = 24 * 60 * 60 * 1000L
        const val FREE_SPACE_MARGIN = 50L * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024
        val FILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.apk$")
        val HEX_64 = Regex("^[0-9a-f]{64}$")

        fun AndroidReleaseDto.isWellFormed(): Boolean =
            FILE_NAME.matches(file) && HEX_64.matches(sha256) && HEX_64.matches(signingCertSha256) &&
                sizeBytes > 0 && versionCode > 0

        fun AndroidReleaseDto.toRelease(required: Boolean) = AppRelease(
            versionCode = versionCode,
            versionName = versionName,
            file = file,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            signingCertSha256 = signingCertSha256,
            required = required,
            notes = notes.take(12),
        )

        fun AppError.toFailure(): UpdateFailure = when (this) {
            AppError.NoConnection, AppError.LocalNetworkBlocked, AppError.UntrustedServer -> UpdateFailure.NO_CONNECTION
            else -> UpdateFailure.SERVER_PROBLEM
        }

        @Suppress("DEPRECATION")
        fun PackageInfo.longVersion(): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
