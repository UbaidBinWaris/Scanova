package app.scanova.feature.scan.imaging

import android.content.Context
import org.opencv.core.MatOfByte
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net

/**
 * Reads an ONNX model from the app's assets into OpenCV's dnn module. The bytes
 * go straight into one native buffer (no second Java copy, which matters for a
 * 16 MB model), and that buffer is released however parsing ends.
 */
internal fun readOnnxAsset(context: Context, asset: String): Net {
    val bytes = context.assets.open(asset).use { it.readBytes() }
    val buffer = MatOfByte()
    try {
        buffer.alloc(bytes.size)
        buffer.put(0, 0, bytes)
        return Dnn.readNetFromONNX(buffer)
    } finally {
        buffer.release()
    }
}
