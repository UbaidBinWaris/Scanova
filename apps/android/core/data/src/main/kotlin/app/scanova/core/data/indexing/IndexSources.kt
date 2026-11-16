package app.scanova.core.data.indexing

import app.scanova.core.common.coroutines.AppDispatchers
import app.scanova.core.ml.text.PageReader
import app.scanova.core.security.file.EncryptedFileStore
import app.scanova.core.security.keys.VaultUnrecoverableException
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.withContext

/** Reads a page's current image out of the encrypted store, into memory only. */
internal fun encryptedPageImages(files: EncryptedFileStore, dispatchers: AppDispatchers): suspend (String) -> PageImage = { name ->
    withContext(dispatchers.io) {
        try {
            if (!files.exists(name)) PageImage.Missing else PageImage.Found(files.readBytes(name))
        } catch (e: VaultUnrecoverableException) {
            // Not a problem with this page: the worker stops.
            throw e
        } catch (e: IOException) {
            PageImage.Unreadable
        } catch (e: GeneralSecurityException) {
            PageImage.Unreadable
        }
    }
}

/** On-device text recognition ([PageReader]), sideways and upside-down pages included. */
internal fun onDeviceText(reader: PageReader, dispatchers: AppDispatchers): suspend (ByteArray) -> PageText = { bytes ->
    val reading = withContext(dispatchers.default) { reader.read(bytes, detectTurn = true) }
    when {
        reading == null -> PageText.Undecodable
        !reading.recognized -> PageText.RecognizerUnavailable
        else -> PageText.Read(reading.text.trim())
    }
}
