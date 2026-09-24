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
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float = 1f, val angle: Float = 0f) {
    val x get() = (left + right) / 2
    val y get() = (top + bottom) / 2
}
fun interface OcrService {
    suspend fun recognize(source: Bitmap, region: BubbleRegion, script: OcrScript): String
    suspend fun lines(source: Bitmap, script: OcrScript): List<OcrLine> =
        throw IllegalStateException("Page recognition is not available with this recognizer.")
}

/** Own the input copy until ML Kit's native task finishes, including after cancellation. */
class BubbleOcr(context: Context? = null, private val onDetected: ((List<Rect>) -> Unit)? = null) : OcrService, AutoCloseable {
    private val app = context?.applicationContext
    private val detectorState = lazy { app?.let(::LearnedTextDetector) }
    private val gate = Mutex()
    @Volatile private var released = false
    private var client: com.google.mlkit.vision.text.TextRecognizer? = null
    private var clientScript: OcrScript? = null
    private fun closeClient() { client?.close(); client = null; clientScript = null }
    override fun close() {
        released = true
        if (gate.tryLock()) try { closeClient() } finally { gate.unlock() }
        if (detectorState.isInitialized()) detectorState.value?.close()
    }
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
                            (r.right + tile.left).coerceAtMost(source.width), (r.bottom + tile.top).coerceAtMost(source.height), line.confidence, slope(line))
                    } }
                }
                OcrTiles.merge(found, result)
                require(found.size <= 300) { "This image has too many text lines. Tap individual text areas to edit them." }
            }
            currentCoroutineContext().ensureActive()
            val model = detectorState.value
            val detected = if (model == null) emptyList() else {
                val tileImage = Bitmap.createBitmap(source, tile.left, tile.top, tile.width(), tile.height())
                try { model.detect(tileImage).map { Rect(it).apply { offset(tile.left, tile.top) } } }
                finally { if (tileImage !== source) tileImage.recycle() }
            }
            onDetected?.invoke(detected)
            // A detector miss must not prevent a weak primary OCR line from receiving a crop retry.
            val weak = found.filter { tile.contains(it.x,it.y) && (it.confidence < .65f ||
                (it.confidence < .88f && OcrTiles.lightLettering(source,it))) }.map { line ->
                val pad = ((line.bottom-line.top)/2).coerceIn(10,36)
                Rect((line.left-pad).coerceAtLeast(0),(line.top-pad).coerceAtLeast(0),
                    (line.right+pad).coerceAtMost(source.width),(line.bottom+pad).coerceAtMost(source.height))
            }
            for (box in (weak+detected).distinct()) {
                currentCoroutineContext().ensureActive()
                val hint = found.filter { box.contains(it.x,it.y) }.minByOrNull { kotlin.math.abs(it.y-box.centerY()) }
                val light = hint?.let { OcrTiles.lightLettering(source,it) } == true
                fun alreadyRead() = found.any { line -> line.confidence >= (if (light) .88f else .68f) && box.contains(line.x,line.y) &&
                    kotlin.math.abs(line.y-box.centerY()) <= box.height()*.20f &&
                    line.right-line.left >= box.width()*.60f && line.bottom-line.top >= box.height()*.25f }
                if (alreadyRead()) continue
                val angle = hint?.angle ?: 0f
                for (mode in if (light) listOf(2,0,1) else listOf(1,0,2)) {
                    OcrTiles.merge(found,recognizeBox(source,box,script,false,mode,angle))
                    if (alreadyRead()) break
                }
                require(found.size <= 300) { "This image has too many separate text lines." }
            }
        }
        return found.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
    }

    private fun slope(line: Text.Line): Float {
        val points = line.cornerPoints ?: return 0f
        if (points.size < 2) return 0f
        return Math.toDegrees(kotlin.math.atan2((points[1].y - points[0].y).toDouble(), (points[1].x - points[0].x).toDouble())).toFloat().coerceIn(-25f, 25f)
    }

    private suspend fun recognizeBox(source: Bitmap, rect: Rect, script: OcrScript, contrast: Boolean, isolate: Int = 0, angle: Float = 0f): List<OcrLine> {
        val scale = (192f / rect.height()).coerceIn(1.5f, 3f)
        val w = (rect.width() * scale).toInt(); val h = (rect.height() * scale).toInt()
        val radians = Math.toRadians(angle.toDouble())
        val bw = (w * kotlin.math.abs(kotlin.math.cos(radians)) + h * kotlin.math.abs(kotlin.math.sin(radians))).toInt() + 34
        val bh = (h * kotlin.math.abs(kotlin.math.cos(radians)) + w * kotlin.math.abs(kotlin.math.sin(radians))).toInt() + 34
        val mapping = android.graphics.Matrix().apply {
            setRectToRect(android.graphics.RectF(rect), android.graphics.RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), android.graphics.Matrix.ScaleToFit.FILL)
            postRotate(-angle); postTranslate(bw / 2f, bh / 2f)
        }
        val inverse = android.graphics.Matrix(); check(mapping.invert(inverse))
        return process(script, {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.WHITE)
                // Transform source pixels before adding a white margin. Inverting bright
                // lettering after padding creates a black frame that confuses recognition.
                val crop = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                try {
                    android.graphics.Canvas(crop).drawBitmap(source, rect, Rect(0, 0, crop.width, crop.height), null)
                    if (contrast) OcrTiles.contrast(crop)
                    if (isolate != 0) OcrTiles.isolate(crop, isolate == 2)
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.translate(bw / 2f, bh / 2f); canvas.rotate(-angle)
                    canvas.drawBitmap(crop, null, android.graphics.RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                } finally { crop.recycle() }
            }
        }) { result -> result.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { bounds ->
            val original = android.graphics.RectF(bounds); inverse.mapRect(original)
            val left = original.left.toInt().coerceIn(0, source.width); val top = original.top.toInt().coerceIn(0, source.height)
            val right = original.right.toInt().coerceIn(0, source.width); val bottom = original.bottom.toInt().coerceIn(0, source.height)
            if (line.text.isBlank() || right <= left || bottom <= top) null else OcrLine(line.text.trim(), left, top, right, bottom, line.confidence, slope(line) + angle)
        } } }
    }

    private suspend fun <T> process(script: OcrScript, input: () -> Bitmap, result: (Text) -> T): T {
        gate.lock()
        val bitmap: Bitmap
        val recognizer: com.google.mlkit.vision.text.TextRecognizer
        try {
            check(!released) { "Text recognition has been closed." }
            bitmap = input()
            try {
                if (clientScript != script) closeClient()
                recognizer = client ?: when (script) {
                    OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                    OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                    OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                    OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                }.also { client = it; clientScript = script }
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
                    } finally { if (released) closeClient(); bitmap.recycle(); gate.unlock() }
                }
            } catch (_: Exception) {
                closeClient(); bitmap.recycle(); gate.unlock()
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
    /** Bright glyphs on a darker panel benefit from polarity correction even when
     * the recognizer gives a plausible-looking, but misspelled, first reading. */
    fun lightLettering(source: Bitmap, line: OcrLine): Boolean {
        val width=line.right-line.left;val height=line.bottom-line.top
        if(width<=0||height<=0) return false
        val pixels=IntArray(width*height)
        source.getPixels(pixels,0,width,line.left,line.top,width,height)
        var dark=0;var bright=0
        for(c in pixels) {
            val value=minOf(Color.red(c),Color.green(c),Color.blue(c))
            if(value<180) dark++
            if(value>200) bright++
        }
        return dark>pixels.size/2 && bright>pixels.size/40
    }
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
                    (line.confidence > other.confidence + .02f && line.text.length >= other.text.length * .75f)) existing[duplicate] = line.copy(
                        left = minOf(line.left, other.left), top = minOf(line.top, other.top),
                        right = maxOf(line.right, other.right), bottom = maxOf(line.bottom, other.bottom))
            }
        }
    }
    /** Isolate neutral dark cores or bright lettering from colored comic artwork for OCR only. */
    fun isolate(bitmap: Bitmap, light: Boolean) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val c = pixels[i]; val lo = minOf(Color.red(c), Color.green(c), Color.blue(c)); val hi = maxOf(Color.red(c), Color.green(c), Color.blue(c))
            val foreground = if (light) lo > 190 else hi < 115 && hi - lo < 55
            pixels[i] = if (foreground) Color.BLACK else Color.WHITE
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
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
