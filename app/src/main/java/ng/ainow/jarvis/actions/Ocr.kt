package ng.ainow.jarvis.actions

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class OcrLine(val text: String, val rect: Rect)

/**
 * On-device OCR (Google ML Kit, runs offline). Finds buttons that have text drawn on them
 * but no accessibility label, or that the screen reader skips.
 */
class Ocr {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Returns every line and every word found, each with its position on the screen. */
    fun read(bmp: Bitmap, cb: (List<OcrLine>) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { vt ->
                val out = ArrayList<OcrLine>()
                for (b in vt.textBlocks) {
                    for (l in b.lines) {
                        val r = l.boundingBox ?: continue
                        out.add(OcrLine(l.text, r))
                        for (e in l.elements) {
                            val er = e.boundingBox ?: continue
                            out.add(OcrLine(e.text, er))
                        }
                    }
                }
                cb(out)
            }
            .addOnFailureListener { cb(emptyList()) }
    }
}
