package com.ruyo.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.Executor

enum class OcrScript(val label: String) {
    LATIN("Latin (English, French…)"), JAPANESE("Japanese"), CHINESE("Chinese"), KOREAN("Korean"), DEVANAGARI("Devanagari (Hindi…)")
}
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float = 1f) {
    val x get() = (left + right) / 2
    val y get() = (top + bottom) / 2
}
fun interface OcrService {
    suspend fun recognize(source: Bitmap, region: BubbleRegion, script: OcrScript): String
    suspend fun lines(source: Bitmap, script: OcrScript): List<OcrLine> =
        throw IllegalStateException("Page recognition is not available with this recognizer.")
}

/** Own the input copy until ML Kit's native task finishes, including after cancellation. */
class BubbleOcr(context: Context? = null) : OcrService {
    private val app = context?.applicationContext
    private val detector by lazy { app?.let(::LearnedTextDetector) }
    private val gate = Mutex()
    override suspend fun recognize(source: Bitmap, region: BubbleRegion, script: OcrScript): String {
        val original = process(script, { crop(source, region) }) { it.text.trim() }
        if (original.isNotEmpty()) return original
        currentCoroutineContext().ensureActive()
        return process(script, { crop(source, region).also(OcrTiles::contrast) }) {
            it.text.trim().also { text -> require(text.isNotEmpty()) { "No readable text found. Check the source script, or type the original text below." } }
        }
    }

    override suspend fun lines(source: Bitmap, script: OcrScript): List<OcrLine> {
        val found = mutableListOf<OcrLine>()
        for (tile in OcrTiles.regions(source.width, source.height)) {
            for (contrast in listOf(false, true)) {
                currentCoroutineContext().ensureActive()
                val result = process(script, {
                    val bitmap = Bitmap.createBitmap(tile.width(), tile.height(), Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(bitmap).drawBitmap(source, tile, Rect(0, 0, tile.width(), tile.height()), null)
                    if (contrast) OcrTiles.contrast(bitmap)
                    bitmap
                }) { text ->
                    text.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { r ->
                        if (line.text.isBlank() || r.width() <= 0 || r.height() <= 0) null
                        else OcrLine(line.text.trim(), (r.left + tile.left).coerceAtLeast(0), (r.top + tile.top).coerceAtLeast(0),
                            (r.right + tile.left).coerceAtMost(source.width), (r.bottom + tile.top).coerceAtMost(source.height), line.confidence)
                    } }
                }
                OcrTiles.merge(found, result)
                require(found.size <= 300) { "This image has too many text lines. Tap individual text areas to edit them." }
            }
            val model = detector
            if (model != null) {
                currentCoroutineContext().ensureActive()
                val tileImage = Bitmap.createBitmap(source, tile.left, tile.top, tile.width(), tile.height())
                val boxes = try { model.detect(tileImage) } finally { if (tileImage !== source) tileImage.recycle() }
                for (local in boxes) {
                    currentCoroutineContext().ensureActive()
                    val box = Rect(local).apply { offset(tile.left, tile.top) }
                    val alreadyRead = found.any { line -> line.confidence >= .94f && box.contains(line.x, line.y) &&
                        line.right - line.left >= box.width() * .60f && line.bottom - line.top >= box.height() * .35f }
                    if (alreadyRead) continue
                    for (contrast in listOf(false, true)) OcrTiles.merge(found, recognizeBox(source, box, script, contrast))
                    require(found.size <= 300) { "This image has too many separate text lines." }
                }
            }
        }
        return found.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
    }

    private suspend fun recognizeBox(source: Bitmap, rect: Rect, script: OcrScript, contrast: Boolean): List<OcrLine> {
        val scale = (96f / rect.height()).coerceIn(1f, 3f)
        val w = (rect.width() * scale).toInt(); val h = (rect.height() * scale).toInt()
        return process(script, {
            Bitmap.createBitmap(w + 32, h + 32, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.WHITE)
                android.graphics.Canvas(bitmap).drawBitmap(source, rect, Rect(16, 16, w + 16, h + 16), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                if (contrast) OcrTiles.contrast(bitmap)
            }
        }) { result -> result.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { bounds ->
            val left = (rect.left + (bounds.left - 16) * rect.width().toFloat() / w).toInt().coerceIn(0, source.width)
            val top = (rect.top + (bounds.top - 16) * rect.height().toFloat() / h).toInt().coerceIn(0, source.height)
            val right = (rect.left + (bounds.right - 16) * rect.width().toFloat() / w).toInt().coerceIn(0, source.width)
            val bottom = (rect.top + (bounds.bottom - 16) * rect.height().toFloat() / h).toInt().coerceIn(0, source.height)
            if (line.text.isBlank() || right <= left || bottom <= top) null else OcrLine(line.text.trim(), left, top, right, bottom, line.confidence)
        } } }
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

/** Preserve working-image resolution through overlapping OCR tiles. No image leaves the device. */
internal object OcrTiles {
    fun regions(width: Int, height: Int): List<Rect> {
        fun starts(length: Int, limit: Int): List<Int> {
            if (length <= limit) return listOf(0)
            val result = mutableListOf(0)
            while (result.last() + limit < length) result += minOf(result.last() + limit - 192, length - limit)
            return result
        }
        return starts(height, 1536).flatMap { y -> starts(width, 1280).map { x -> Rect(x, y, minOf(width, x + 1280), minOf(height, y + 1536)) } }
    }
    fun merge(existing: MutableList<OcrLine>, added: List<OcrLine>) {
        for (line in added) {
            if (line.right <= line.left || line.bottom <= line.top) continue
            val duplicate = existing.indexOfFirst { other ->
                val width = (minOf(line.right, other.right) - maxOf(line.left, other.left)).coerceAtLeast(0)
                val height = (minOf(line.bottom, other.bottom) - maxOf(line.top, other.top)).coerceAtLeast(0)
                val smaller = minOf((line.right - line.left) * (line.bottom - line.top), (other.right - other.left) * (other.bottom - other.top))
                width * height > smaller * .60
            }
            if (duplicate < 0) existing += line
            else {
                val other = existing[duplicate]
                // A full line from the overlap can replace a fragment at a tile boundary.
                if ((line.text.contains(other.text, ignoreCase = true) && line.text.length > other.text.length) ||
                    (line.confidence > other.confidence + .02f && line.text.length >= other.text.length * .75f)) existing[duplicate] = line
            }
        }
    }
    fun contrast(bitmap: Bitmap) {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h); bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val stride = w + 1; val sum = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                val c = pixels[y * w + x]; val gray = (Color.red(c) * 77 + Color.green(c) * 150 + Color.blue(c) * 29) shr 8
                pixels[y * w + x] = gray; row += gray; sum[(y + 1) * stride + x + 1] = sum[y * stride + x + 1] + row
            }
        }
        for (y in 0 until h) for (x in 0 until w) {
            val l = maxOf(0, x - 20); val r = minOf(w, x + 21); val t = maxOf(0, y - 20); val b = minOf(h, y + 21)
            val mean = (sum[b * stride + r] - sum[t * stride + r] - sum[b * stride + l] + sum[t * stride + l]) / ((r - l) * (b - t))
            pixels[y * w + x] = if (pixels[y * w + x] < mean - 12) Color.BLACK else Color.WHITE
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
