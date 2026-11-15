package app.scanova.feature.scan.ocr

import android.graphics.Bitmap
import app.scanova.core.common.log.ScanovaLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device text recognition (ML Kit, bundled Latin-script model). It runs
 * entirely on the phone: the image is not sent anywhere. Recognition is a
 * convenience for instant search and a title suggestion, so any failure simply
 * yields no text; the server's OCR is the authoritative one and also covers
 * Urdu and other scripts.
 */
class PageTextRecognizer @Inject constructor() {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(page: Bitmap): String = suspendCancellableCoroutine { continuation ->
        try {
            recognizer.process(InputImage.fromBitmap(page, 0))
                .addOnSuccessListener { text ->
                    if (continuation.isActive) continuation.resume(text.text.take(MAX_CHARACTERS))
                }
                .addOnFailureListener { error ->
                    ScanovaLog.warn(TAG, "text_recognition_failed", error)
                    if (continuation.isActive) continuation.resume("")
                }
                .addOnCanceledListener {
                    if (continuation.isActive) continuation.resume("")
                }
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "text_recognition_unavailable", e)
            if (continuation.isActive) continuation.resume("")
        }
    }

    private companion object {
        const val TAG = "Ocr"

        /** A page of dense text is a few thousand characters; this only guards against pathological output. */
        const val MAX_CHARACTERS = 50_000
    }
}
