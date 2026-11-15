package app.scanova.feature.tools.common

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Builds share-sheet intents for temporary export files. The files are offered
 * through the app's FileProvider (authority `<package>.share`, declared by the
 * documents feature, serving only `cache/shared/`): the receiving app gets
 * read access to exactly these URIs for this one share.
 */
internal object ShareIntents {

    /** A chooser for [files] of [mimeType]; null when a file cannot be offered. */
    fun forFiles(context: Context, files: List<File>, mimeType: String, chooserTitle: String): Intent? {
        if (files.isEmpty()) return null
        val uris = files.map { file ->
            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.share", file) }.getOrNull()
                ?: return null
        }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
        send.type = mimeType
        // Read access to these URIs only, for the app the user picks. ClipData carries the grant for every one.
        send.clipData = ClipData.newRawUri(null, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, chooserTitle)
    }

    /** A chooser that shares [text] itself (no file), titled [subject] where apps use one. */
    fun forText(text: String, subject: String, chooserTitle: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        return Intent.createChooser(send, chooserTitle)
    }

    /** Longest text shared inline; longer texts go as a `.txt` file (an intent must stay well under 1 MB). */
    const val MAX_INLINE_TEXT = 50_000
}
