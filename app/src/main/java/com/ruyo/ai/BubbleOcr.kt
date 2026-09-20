package com.ruyo.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.ruyo.reader.BubbleRegion
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.Executor

enum class OcrScript(val label: String) {
    LATIN("Latin (English, French…)"), JAPANESE("Japanese"), CHINESE("Chinese"), KOREAN("Korean"), DEVANAGARI("Devanagari (Hindi…)")
}
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val x get() = (left + right) / 2
    val y get() = (top + bottom) / 2
}
fun interface OcrService {
    suspend fun recognize(source: Bitmap, region: BubbleRegion, script: OcrScript): String
    suspend fun lines(source: Bitmap, script: OcrScript): List<OcrLine> =
        throw IllegalStateException("Page recognition is not available with this recognizer.")
}

/** Own the input copy until ML Kit's native task finishes, including after cancellation. */
class BubbleOcr : OcrService {
    private val gate = Mutex()
    override suspend fun recognize(source: Bitmap, region: BubbleRegion, script: OcrScript): String =
        process(script, { crop(source, region) }) {
            it.text.trim().also { text -> require(text.isNotEmpty()) { "No readable text found. Check the source script, or type the original text below." } }
        }

    override suspend fun lines(source: Bitmap, script: OcrScript): List<OcrLine> =
        process(script, { source.copy(Bitmap.Config.ARGB_8888, false) }) { text ->
            val lines = text.textBlocks.flatMap { it.lines }
            require(lines.size <= 300) { "This image has too many text lines. Tap individual bubbles to edit them." }
            lines.mapNotNull { line ->
                line.boundingBox?.let { r ->
                    if (line.text.isBlank() || r.width() <= 0 || r.height() <= 0) null
                    else OcrLine(line.text.trim(), r.left, r.top, r.right, r.bottom)
                }
            }
        }

    private suspend fun <T> process(script: OcrScript, input: () -> Bitmap, result: (Text) -> T): T {
        gate.lock()
        val bitmap: Bitmap
        val recognizer: com.google.mlkit.vision.text.TextRecognizer
        try {
            bitmap = input()
            try {
                recognizer = when (script) {
                    OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                    OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                    OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                    OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                }
            } catch (error: Exception) { bitmap.recycle(); throw error }
        } catch (error: Exception) { gate.unlock(); throw error }
        return suspendCancellableCoroutine { continuation ->
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(Executor { it.run() }) { task ->
                    try {
                        if (continuation.isActive) continuation.resumeWith(runCatching {
                            check(task.isSuccessful) { "Text recognition failed. Check the source script or enter the text manually." }
                            result(task.result)
                        })
                    } finally { recognizer.close(); bitmap.recycle(); gate.unlock() }
                }
            } catch (_: Exception) {
                recognizer.close(); bitmap.recycle(); gate.unlock()
                if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("Text recognition could not start. Try again or enter the text manually.")))
            }
        }
    }
    companion object {
        fun crop(source: Bitmap, region: BubbleRegion): Bitmap {
            val padding = 16; val width = region.width + padding * 2; val height = region.height + padding * 2
            val pixels = IntArray(region.width * region.height)
            source.getPixels(pixels, 0, region.width, region.left, region.top, region.width, region.height)
            val output = IntArray(width * height) { Color.WHITE }
            for (y in 0 until region.height) for (x in 0 until region.width) if (region.interior[x, y]) output[(y + padding) * width + x + padding] = pixels[y * region.width + x]
            return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
