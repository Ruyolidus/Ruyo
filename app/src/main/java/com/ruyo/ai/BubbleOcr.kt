package com.ruyo.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
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
fun interface OcrService { suspend fun recognize(source: Bitmap, region: BubbleRegion, script: OcrScript): String }

class BubbleOcr : OcrService {
    private val gate = Mutex()
    override suspend fun recognize(source: Bitmap, region: BubbleRegion, script: OcrScript): String {
        gate.lock()
        // Retain the bitmap and gate until the native task finishes, even after cancellation.
        val bitmap: Bitmap
        val recognizer: com.google.mlkit.vision.text.TextRecognizer
        try {
            bitmap = crop(source, region)
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
                        if (continuation.isActive) {
                            val text = if (task.isSuccessful) task.result.text.trim() else ""
                            continuation.resumeWith(if (text.isNotEmpty()) Result.success(text) else Result.failure(IllegalStateException("No readable text found. Check the source script, or type the original text below.")))
                        }
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
