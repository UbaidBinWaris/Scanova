package app.scanova.feature.scan.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.scanova.core.common.coroutines.AppDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.withContext

/** A picked photo, read into memory, or why it could not be read. */
internal sealed interface PhotoFile {
    /** The name the picker shows, when its provider tells it. */
    val name: String?

    class Loaded(override val name: String?, val bytes: ByteArray) : PhotoFile

    data class Unavailable(override val name: String?, val kind: PhotoProblemKind) : PhotoFile
}

/**
 * Reads photos the user picked. Only into memory: the file goes from the
 * picker's provider straight to [PhotoPreparer] and the encrypted store,
 * never through a plaintext file of ours.
 */
internal class PhotoFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: AppDispatchers,
) {
    suspend fun read(uri: Uri): PhotoFile = withContext(dispatchers.io) {
        val (name, size) = describe(uri)
        if (size != null && size > MAX_FILE_BYTES) return@withContext PhotoFile.Unavailable(name, PhotoProblemKind.TOO_LARGE)
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext PhotoFile.Unavailable(name, PhotoProblemKind.UNREADABLE)
            val bytes = stream.use { readAtMost(it, expected = size, limit = MAX_FILE_BYTES) }
            when {
                bytes == null -> PhotoFile.Unavailable(name, PhotoProblemKind.TOO_LARGE)
                bytes.isEmpty() -> PhotoFile.Unavailable(name, PhotoProblemKind.UNREADABLE)
                else -> PhotoFile.Loaded(name, bytes)
            }
        } catch (e: IOException) {
            PhotoFile.Unavailable(name, PhotoProblemKind.UNREADABLE)
        } catch (e: SecurityException) {
            // The picker's permission ran out (e.g. the app was restarted since).
            PhotoFile.Unavailable(name, PhotoProblemKind.UNREADABLE)
        } catch (e: IllegalArgumentException) {
            PhotoFile.Unavailable(name, PhotoProblemKind.UNREADABLE)
        } catch (e: OutOfMemoryError) {
            PhotoFile.Unavailable(name, PhotoProblemKind.TOO_LARGE)
        }
    }

    /** Display name and size from the provider; either may be unknown. */
    private fun describe(uri: Uri): Pair<String?, Long?> = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null to null
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameColumn >= 0 && !cursor.isNull(nameColumn)) cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } else null
            val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn).takeIf { it > 0 } else null
            name to size
        } ?: (null to null)
    } catch (e: RuntimeException) {
        null to null
    }

    /**
     * Reads the whole stream. When the provider told the size, the bytes land
     * in one array of exactly that size (no copy: photos can be tens of
     * megabytes). Returns null when the stream holds more than [limit] bytes.
     */
    private fun readAtMost(input: InputStream, expected: Long?, limit: Long): ByteArray? {
        var buffer = ByteArray((expected ?: DEFAULT_BUFFER.toLong()).coerceIn(1L, limit).toInt())
        var length = 0
        while (true) {
            if (length == buffer.size) {
                // Full: either the end, or more than expected.
                val next = input.read()
                if (next < 0) return buffer
                if (buffer.size >= limit) return null
                buffer = buffer.copyOf((buffer.size.toLong() * 2).coerceAtMost(limit).toInt())
                buffer[length++] = next.toByte()
                continue
            }
            val read = input.read(buffer, length, buffer.size - length)
            if (read < 0) return if (length == buffer.size) buffer else buffer.copyOf(length)
            length += read
        }
    }

    private companion object {
        /** Larger files are refused: a phone could not hold them and their decoded pixels at once. */
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        const val DEFAULT_BUFFER = 4 * 1024 * 1024
    }
}
