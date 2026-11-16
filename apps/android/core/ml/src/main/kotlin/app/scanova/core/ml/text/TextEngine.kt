package app.scanova.core.ml.text

import android.graphics.Bitmap
import app.scanova.core.common.log.ScanovaLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Reads text on an image. One implementation: ML Kit's bundled Latin model ([MlKitTextEngine]). */
interface TextEngine {
    /**
     * Recognises the text on [image] as if it were turned [turn] degrees
     * clockwise (0, 90, 180 or 270) first. Returns null when recognition
     * failed or is unavailable; an image without text gives [RecognizedText.EMPTY].
     */
    suspend fun recognize(image: Bitmap, turn: Int = 0): RecognizedText?
}

/**
 * ML Kit text recognition v2 with the bundled Latin-script model: on the
 * phone, offline, nothing leaves the device (its data-transport backend is
 * removed from the app's manifest). One client for the whole app; ML Kit
 * queues concurrent requests itself.
 */
@Singleton
class MlKitTextEngine @Inject constructor() : TextEngine {
    private val recognizer: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(image: Bitmap, turn: Int): RecognizedText? = suspendCancellableCoroutine { continuation ->
        try {
            // ML Kit takes the clockwise rotation that makes the image upright, like the page recipe.
            recognizer.process(InputImage.fromBitmap(image, PageOrientation.normalizeTurn(turn)))
                .addOnSuccessListener { text ->
                    val lines = text.textBlocks.flatMap { block ->
                        block.lines.map { line -> RecognizedLine(line.text, line.angle, line.confidence) }
                    }
                    if (continuation.isActive) continuation.resume(RecognizedText(lines))
                }
                .addOnFailureListener { error ->
                    ScanovaLog.warn(TAG, "text_recognition_failed", error)
                    if (continuation.isActive) continuation.resume(null)
                }
                .addOnCanceledListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        } catch (e: Exception) {
            ScanovaLog.warn(TAG, "text_recognition_unavailable", e)
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private companion object {
        const val TAG = "Ocr"
    }
}
