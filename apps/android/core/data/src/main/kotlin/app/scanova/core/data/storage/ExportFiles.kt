package app.scanova.core.data.storage

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * The only place where document content exists on disk unencrypted: a PDF,
 * page images or text written to the app's private cache so another app can
 * receive them through a share. Each export lives in its own random directory
 * and is deleted as soon as the share is over; anything left (the app was
 * killed mid-share) is removed at the next app start.
 */
class ExportFiles internal constructor(
    private val context: Context,
) {
    /** Directory exposed through the FileProvider (`res/xml` path name `shared`). */
    private val root: File get() = File(context.cacheDir, DIRECTORY)

    internal fun newFile(displayName: String): File = File(newDirectory(), displayName)

    /**
     * A fresh, empty directory for one export that may hold several files (all
     * pages of a document, several documents). Remove it with [deleteDirectory]
     * as soon as the share is over.
     */
    fun newDirectory(): File {
        val directory = File(root, UUID.randomUUID().toString())
        if (!directory.mkdirs()) error("Cannot create export directory")
        return directory
    }

    /** Deletes one export and its directory. Safe to call twice. */
    fun delete(file: File) {
        val parent = file.parentFile
        file.delete()
        // Only ever remove directories this class created.
        if (parent != null && parent.parentFile == root) parent.deleteRecursively()
    }

    /** Deletes a directory made by [newDirectory] and everything in it. Safe to call twice. */
    fun deleteDirectory(directory: File) {
        // Only ever remove directories this class created.
        if (directory.parentFile == root) directory.deleteRecursively()
    }

    /** Deletes every export. */
    fun deleteAll() {
        root.deleteRecursively()
    }

    companion object {
        const val DIRECTORY = "shared"
    }
}
