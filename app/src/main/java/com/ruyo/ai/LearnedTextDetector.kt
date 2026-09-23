package com.ruyo.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/** Text-line detection independent of speech-bubble outlines. The pinned model is bundled offline. */
internal class LearnedTextDetector(context: Context) {
    private val app = context.applicationContext
    private val environment = OrtEnvironment.getEnvironment()
    private val session by lazy {
        val bytes = app.assets.open("ocr/ppocr-det.onnx").use { it.readBytes() }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            environment.createSession(bytes, options)
        }
    }
    @Suppress("UNCHECKED_CAST")
    suspend fun detect(source: Bitmap): List<Rect> {
        currentCoroutineContext().ensureActive()
        val ratio = minOf(1f, 1024f / maxOf(source.width, source.height))
        val w = maxOf(32, (source.width * ratio / 32).roundToInt() * 32)
        val h = maxOf(32, (source.height * ratio / 32).roundToInt() * 32)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        try { scaled.getPixels(pixels, 0, w, 0, 0, w, h) }
        finally { if (scaled !== source) scaled.recycle() }
        // Paddle's detector takes normalized BGR, NCHW; resize coordinates map back independently.
        val tensor = FloatArray(w * h * 3)
        val mean = floatArrayOf(.485f, .456f, .406f); val std = floatArrayOf(.229f, .224f, .225f)
        for (i in pixels.indices) {
            tensor[i] = (Color.blue(pixels[i]) / 255f - mean[0]) / std[0]
            tensor[w * h + i] = (Color.green(pixels[i]) / 255f - mean[1]) / std[1]
            tensor[w * h * 2 + i] = (Color.red(pixels[i]) / 255f - mean[2]) / std[2]
        }
        val map = OnnxTensor.createTensor(environment, FloatBuffer.wrap(tensor), longArrayOf(1, 3, h.toLong(), w.toLong())).use { input ->
            session.run(mapOf(session.inputNames.single() to input)).use { result ->
                (result[0].value as Array<Array<Array<FloatArray>>>)[0][0]
            }
        }
        currentCoroutineContext().ensureActive()
        val mh = map.size; val mw = map.first().size
        val seen = BooleanArray(mw * mh); val queue = IntArray(mw * mh); val output = mutableListOf<Rect>()
        for (y in 0 until mh) for (x in 0 until mw) {
            val start = y * mw + x
            if (seen[start] || map[y][x] < .3f) continue
            var head = 0; var tail = 1; queue[0] = start; seen[start] = true
            var l = x; var r = x; var t = y; var b = y; var confidence = 0.0
            while (head < tail) {
                val i = queue[head++]; val xx = i % mw; val yy = i / mw
                l = minOf(l, xx); r = maxOf(r, xx); t = minOf(t, yy); b = maxOf(b, yy); confidence += map[yy][xx]
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = xx + dx; val ny = yy + dy
                    if (nx in 0 until mw && ny in 0 until mh) {
                        val n = ny * mw + nx
                        if (!seen[n] && map[ny][nx] >= .3f) { seen[n] = true; queue[tail++] = n }
                    }
                }
            }
            if (tail < 8 || b - t < 2 || confidence / tail < .55 || tail > mw * mh * .5) continue
            val pad = maxOf(3f, (b - t + 1) * .65f)
            val box = Rect(((l - pad) * source.width / mw).toInt().coerceAtLeast(0), ((t - pad) * source.height / mh).toInt().coerceAtLeast(0),
                ((r + 1 + pad) * source.width / mw).toInt().coerceAtMost(source.width), ((b + 1 + pad) * source.height / mh).toInt().coerceAtMost(source.height))
            if (box.width() >= 8 && box.height() >= 8) output += box
            require(output.size <= 300) { "This image contains too many separate text areas." }
        }
        return output.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
    }
}
