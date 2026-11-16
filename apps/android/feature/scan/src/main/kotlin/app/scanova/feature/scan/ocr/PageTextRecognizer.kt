package app.scanova.feature.scan.ocr

import android.graphics.Bitmap
import app.scanova.core.ml.text.PageReader
import app.scanova.core.ml.text.PageReading
import javax.inject.Inject

/**
 * On-device text recognition for the scan flow and the page editor
 * (ML Kit, bundled Latin-script model, in `core:ml`). It runs entirely on the
 * phone: the image is not sent anywhere. Recognition is a convenience for
 * instant search and a title suggestion, so any failure simply yields no
 * text; the server's OCR is the authoritative one and also covers Urdu and
 * other scripts.
 */
class PageTextRecognizer @Inject constructor(private val reader: PageReader) {

    /** The text of [page] as it is (no orientation detection). */
    suspend fun recognize(page: Bitmap): String = reader.read(page, detectTurn = false).text

    /**
     * The text of [page] and the quarter turn that makes it upright
     * ([PageReading.turn]); the text is read the right way up.
     */
    suspend fun read(page: Bitmap, detectTurn: Boolean): PageReading = reader.read(page, detectTurn)
}
