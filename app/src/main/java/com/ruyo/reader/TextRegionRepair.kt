package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Color
import com.ruyo.ai.OcrLine
import kotlin.math.abs
import kotlin.math.roundToInt

/** A small, persisted bilinear surface. Coordinates stay on the source page when areas split. */
data class BackgroundSurface(val left: Int, val top: Int, val width: Int, val height: Int, val corners: List<Int>) {
    init { require(left >= 0 && top >= 0 && width in 2..16_384 && height in 2..16_384 && corners.size == 4) }
    fun colorAt(x: Int, y: Int): Int {
        val xx = ((x - left).toDouble() / (width - 1)).coerceIn(0.0, 1.0)
        val yy = ((y - top).toDouble() / (height - 1)).coerceIn(0.0, 1.0)
        fun channel(get: (Int) -> Int): Int = ((get(corners[0]) * (1 - xx) + get(corners[1]) * xx) * (1 - yy) +
            (get(corners[2]) * (1 - xx) + get(corners[3]) * xx) * yy).roundToInt().coerceIn(0, 255)
        return Color.rgb(channel(Color::red), channel(Color::green), channel(Color::blue))
    }
}

/** OCR supplies text bounds, never permission to paint a rectangle over the image.
 * A flat/linear background must agree around AND between the glyphs. Complex art is rejected.
 */
object TextRegionRepair {
    fun groups(lines: List<OcrLine>): List<List<OcrLine>> {
        val remaining = lines.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left }).toMutableList()
        val output = mutableListOf<List<OcrLine>>()
        while (remaining.isNotEmpty()) {
            val group = mutableListOf(remaining.removeAt(0))
            while (group.size < 8) {
                val last = group.last(); val height = last.bottom - last.top
                val next = remaining.firstOrNull { line ->
                    val overlap = minOf(line.right, last.right) - maxOf(line.left, last.left)
                    val otherHeight = line.bottom - line.top
                    line.top >= last.bottom - height / 5 && line.top - last.bottom <= height * .7 &&
                        otherHeight in (height / 2)..(height * 2) && overlap >= minOf(line.right - line.left, last.right - last.left) * .5
                } ?: break
                group += next; remaining.remove(next)
            }
            output += group
        }
        return output
    }

    fun select(source: Bitmap, lines: List<OcrLine>, allLines: List<OcrLine> = lines): BubbleRegion? {
        if (lines.isEmpty() || lines.size > 8 || lines.any { it.left < 0 || it.top < 0 || it.right > source.width || it.bottom > source.height || it.right <= it.left || it.bottom <= it.top }) return null
        val letterHeight = lines.map { it.bottom - it.top }.sorted()[lines.size / 2]
        val halo = (letterHeight / 6).coerceIn(2, 10)
        val padding = halo + (letterHeight / 3).coerceIn(5, 16)
        val left = lines.minOf { it.left } - padding; val top = lines.minOf { it.top } - padding
        val right = lines.maxOf { it.right } + padding; val bottom = lines.maxOf { it.bottom } + padding
        if (left < 0 || top < 0 || right > source.width || bottom > source.height) return null
        val w = right - left; val h = bottom - top
        if (w < 16 || h < 16 || w.toLong() * h > 900_000) return null
        // Nearby lettering that OCR kept separate cannot silently become part of this repair.
        if (allLines.any { it !in lines && it.left < right && it.right > left && it.top < bottom && it.bottom > top }) return null
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, left, top, w, h)
        fun inText(x: Int, y: Int) = lines.any { x in (it.left - halo) until (it.right + halo) && y in (it.top - halo) until (it.bottom + halo) }
        // Evenly spaced samples keep this independent of page height and device resolution.
        val step = maxOf(1, maxOf(w, h) / 100)
        val samples = mutableListOf<Sample>()
        for (y in 0 until h step step) for (x in 0 until w step step) {
            if (!inText(left + x, top + y)) samples += Sample(x.toDouble() / (w - 1), y.toDouble() / (h - 1), pixels[y * w + x])
        }
        if (samples.size < 24 || samples.any { Color.alpha(it.color) < 250 }) return null
        var active: List<Sample> = samples
        var plane = fit(active) ?: return null
        repeat(3) {
            active = samples.filter { distance(it.color, plane.color(it.x, it.y)) <= 10 }
            if (active.size < samples.size * .97) return null
            plane = fit(active) ?: return null
        }
        // Strong color ramps/texture cannot be reconstructed as a gently shaded panel.
        val corners = listOf(plane.color(0.0, 0.0), plane.color(1.0, 0.0), plane.color(0.0, 1.0), plane.color(1.0, 1.0))
        if (corners.any { a -> corners.any { b -> distance(a, b) > 90 } }) return null
        val surface = BackgroundSurface(left, top, w, h, corners)
        val raw = BooleanArray(pixels.size)
        var outside = 0; var clear = 0; var lighter = 0; var darker = 0
        for (i in pixels.indices) {
            val x = left + i % w; val y = top + i / w
            val bg = surface.colorAt(x, y); val delta = distance(pixels[i], bg)
            if (Color.alpha(pixels[i]) < 250) return null
            if (inText(x, y)) {
                raw[i] = delta > 12
                if (delta <= 8) clear++
                if (delta > 45) { if (luminance(pixels[i]) > luminance(bg)) lighter++ else darker++ }
            } else if (delta > 12) outside++
        }
        val count = raw.count { it }
        val textArea = pixels.indices.count { inText(left + it % w, top + it / w) }
        if (outside > maxOf(4, (pixels.size - textArea) / 100) || count < 8 || count > textArea * .48 || clear < textArea * .45) return null
        // Ink must break into letter-sized components; a drawing crossing the OCR box is unsafe.
        val seen = BooleanArray(raw.size); val queue = IntArray(raw.size)
        for (start in raw.indices) if (raw[start] && !seen[start]) {
            var head = 0; var tail = 1; queue[0] = start; seen[start] = true
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            while (head < tail) {
                val i = queue[head++]; val x = i % w; val y = i / w
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h) {
                        val next = yy * w + xx
                        if (raw[next] && !seen[next]) { seen[next] = true; queue[tail++] = next }
                    }
                }
            }
            if (maxY - minY > letterHeight * 1.5 || maxX - minX > maxOf(letterHeight * 5.0, w * .85)) return null
        }
        val ink = BooleanArray(raw.size)
        for (i in raw.indices) if (raw[i]) for (dy in -2..2) for (dx in -2..2) {
            val x = i % w + dx; val y = i / w + dy
            if (x in 2 until w - 2 && y in 2 until h - 2) ink[y * w + x] = true
        }
        return BubbleRegion(left, top, PixelMask(w, h, BooleanArray(w * h) { true }), ink,
            surface.colorAt(left + w / 2, top + h / 2), surface, if (lighter > darker) Color.WHITE else Color.BLACK)
    }

    private data class Sample(val x: Double, val y: Double, val color: Int)
    private data class Plane(val channels: List<DoubleArray>) {
        fun color(x: Double, y: Double): Int {
            val values = channels.map { (it[0] + it[1] * x + it[2] * y).roundToInt().coerceIn(0, 255) }
            return Color.rgb(values[0], values[1], values[2])
        }
    }
    private fun fit(samples: List<Sample>): Plane? {
        val matrix = Array(3) { DoubleArray(6) }
        for (sample in samples) {
            val v = doubleArrayOf(1.0, sample.x, sample.y)
            val colors = intArrayOf(Color.red(sample.color), Color.green(sample.color), Color.blue(sample.color))
            for (row in 0..2) {
                for (col in 0..2) matrix[row][col] += v[row] * v[col]
                for (col in 0..2) matrix[row][col + 3] += v[row] * colors[col]
            }
        }
        for (col in 0..2) {
            val pivot = (col..2).maxBy { abs(matrix[it][col]) }
            val swap = matrix[col]; matrix[col] = matrix[pivot]; matrix[pivot] = swap
            val factor = matrix[col][col]; if (abs(factor) < .000001) return null
            for (j in col..5) matrix[col][j] /= factor
            for (row in 0..2) if (row != col) {
                val multiple = matrix[row][col]
                for (j in col..5) matrix[row][j] -= multiple * matrix[col][j]
            }
        }
        return Plane((0..2).map { channel -> DoubleArray(3) { matrix[it][channel + 3] } })
    }
    private fun luminance(c: Int) = Color.red(c) * .299 + Color.green(c) * .587 + Color.blue(c) * .114
    private fun distance(a: Int, b: Int) = maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
}
