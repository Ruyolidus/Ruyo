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
 * Smooth-background repair is validated separately from the reviewable artwork fallback.
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

    fun select(source: Bitmap, lines: List<OcrLine>, allLines: List<OcrLine> = lines): BubbleRegion? =
        selectFlat(source, lines, allLines) ?: selectShaded(source, lines, allLines)

    /** Uniform, high-contrast lettering over artwork. Ambiguous components need a manual mask. */
    fun selectArtwork(source: Bitmap, lines: List<OcrLine>): BubbleRegion? {
        if (lines.isEmpty() || lines.size > 8) return null
        val size = lines.map { it.bottom - it.top }.sorted()[lines.size / 2]
        if (size < 12) return null
        val pad = (size / 2).coerceIn(8, 28)
        val left = lines.minOf { it.left } - pad; val top = lines.minOf { it.top } - pad
        val right = lines.maxOf { it.right } + pad; val bottom = lines.maxOf { it.bottom } + pad
        val w = right - left; val h = bottom - top
        if (left < 0 || top < 0 || right > source.width || bottom > source.height || w.toLong() * h > 400_000 || w < 16 || h < 16) return null
        val pixels = IntArray(w * h); source.getPixels(pixels, 0, w, left, top, w, h)
        val gray = IntArray(pixels.size) { luminance(pixels[it]).roundToInt() }
        val radius = (size / 3).coerceIn(4, 16)
        val sum = IntArray((w + 1) * (h + 1)); val stride = w + 1
        for (y in 0 until h) { var row = 0; for (x in 0 until w) { row += gray[y * w + x]; sum[(y + 1) * stride + x + 1] = sum[y * stride + x + 1] + row } }
        val contrast = IntArray(pixels.size); val bins = IntArray(512)
        fun bin(c: Int) = (Color.red(c) / 32) * 64 + (Color.green(c) / 32) * 8 + Color.blue(c) / 32
        fun inText(x: Int, y: Int) = lines.any { x in it.left until it.right && y in it.top until it.bottom }
        var textArea = 0
        for (y in 0 until h) for (x in 0 until w) if (inText(left + x, top + y)) {
            textArea++
            val l = maxOf(0, x - radius); val r = minOf(w, x + radius + 1); val t = maxOf(0, y - radius); val b = minOf(h, y + radius + 1)
            val mean = (sum[b * stride + r] - sum[t * stride + r] - sum[b * stride + l] + sum[t * stride + l]) / ((r - l) * (b - t))
            val i = y * w + x; contrast[i] = gray[i] - mean
            if (abs(contrast[i]) > 40) bins[bin(pixels[i])]++
        }
        val dominant = bins.indices.maxBy { bins[it] }
        if (bins[dominant] < maxOf(12, textArea / 45)) return null
        val target = Color.rgb(dominant / 64 * 32 + 16, dominant / 8 % 8 * 32 + 16, dominant % 8 * 32 + 16)
        val raw = BooleanArray(pixels.size) { abs(contrast[it]) > 30 && distance(pixels[it], target) <= 30 }
        val visited = BooleanArray(raw.size); val queue = IntArray(raw.size)
        val mask = BooleanArray(raw.size); var components = 0; var kept = 0
        for (start in raw.indices) if (raw[start] && !visited[start]) {
            var a = 0; var b = 1; queue[0] = start; visited[start] = true
            var x0 = w; var x1 = 0; var y0 = h; var y1 = 0
            while (a < b) {
                val i = queue[a++]; val x = i % w; val y = i / w
                x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h) {
                        val j = yy * w + xx
                        if (raw[j] && !visited[j]) { visited[j] = true; queue[b++] = j }
                    }
                }
            }
            if (b < 3) continue
            if (y1 - y0 > size * 1.3 || x1 - x0 > size * 2.5) return null
            components++; kept += b
            val grow = (size / 8).coerceIn(2, 5)
            for (n in 0 until b) for (dy in -grow..grow) for (dx in -grow..grow) {
                val x = queue[n] % w + dx; val y = queue[n] / w + dy
                if (x in 2 until w - 2 && y in 2 until h - 2) mask[y * w + x] = true
            }
        }
        if (components < 2 || kept < textArea / 80 || mask.count { it } > textArea * .65) return null
        return BubbleRegion(left, top, PixelMask(w, h, BooleanArray(w * h) { true }), mask, pixels.first(),
            textColor = if (luminance(target) > 160) Color.WHITE else Color.BLACK, inpaint = true)
    }

    /** Keep the bubble's layout shape, but isolate cleanup from OCR lettering bounds. */
    fun refine(source: Bitmap, parent: BubbleRegion, lines: List<OcrLine>): BubbleRegion {
        val text = select(source, lines) ?: selectArtwork(source, lines) ?: return parent
        val ink = BooleanArray(parent.width * parent.height)
        for (i in text.eraseMask.indices) if (text.eraseMask[i]) {
            val x = text.left + i % text.width; val y = text.top + i / text.width
            if (!parent.contains(x, y)) return parent
            ink[(y - parent.top) * parent.width + x - parent.left] = true
        }
        return parent.copy(eraseMask = ink, backgroundSurface = text.backgroundSurface, backgroundColor = text.backgroundColor,
            textColor = text.textColor, inpaint = text.inpaint)
    }

    private fun selectFlat(source: Bitmap, lines: List<OcrLine>, allLines: List<OcrLine>): BubbleRegion? {
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

    /** Follow horizontal background transitions instead of forcing one plane across a panel. */
    private fun selectShaded(source: Bitmap, lines: List<OcrLine>, allLines: List<OcrLine>): BubbleRegion? {
        if (lines.isEmpty() || lines.size > 8 || lines.any { it.left < 0 || it.top < 0 || it.right > source.width || it.bottom > source.height || it.right <= it.left || it.bottom <= it.top }) return null
        val letterHeight = lines.map { it.bottom - it.top }.sorted()[lines.size / 2]
        val halo = (letterHeight / 6).coerceIn(3, 12)
        val padding = halo + (letterHeight / 3).coerceIn(6, 20)
        val left = lines.minOf { it.left } - padding; val top = lines.minOf { it.top } - padding
        val right = lines.maxOf { it.right } + padding; val bottom = lines.maxOf { it.bottom } + padding
        if (left < 0 || top < 0 || right > source.width || bottom > source.height) return null
        val w = right - left; val h = bottom - top
        if (w.toLong() * h > 900_000 || w < 16 || h < 16) return null
        if (allLines.any { it !in lines && it.left < right && it.right > left && it.top < bottom && it.bottom > top }) return null
        val pixels = IntArray(w * h); source.getPixels(pixels, 0, w, left, top, w, h)
        val strip = maxOf(3, padding / 3)
        fun median(values: List<Int>, channel: (Int) -> Int) = values.map(channel).sorted()[values.size / 2]
        fun color(values: List<Int>) = Color.rgb(median(values, Color::red), median(values, Color::green), median(values, Color::blue))
        val rows = (0 until h).map { y ->
            color((0 until strip).map { pixels[y * w + it] }) to color((w - strip until w).map { pixels[y * w + it] })
        }
        fun background(x: Int, y: Int): Int {
            val (a, b) = rows[y]; val t = x.toDouble() / (w - 1)
            fun c(channel: (Int) -> Int) = (channel(a) * (1 - t) + channel(b) * t).roundToInt()
            return Color.rgb(c(Color::red), c(Color::green), c(Color::blue))
        }
        fun inText(x: Int, y: Int) = lines.any { x in (it.left - halo) until (it.right + halo) && y in (it.top - halo) until (it.bottom + halo) }
        val raw = BooleanArray(w * h); var outside = 0; var backgroundCount = 0; var textCount = 0
        var darker = 0L; var lighter = 0L
        for (i in pixels.indices) {
            if (Color.alpha(pixels[i]) < 250) return null
            val x = i % w; val y = i / w; val bg = background(x, y); val delta = distance(pixels[i], bg)
            if (inText(left + x, top + y)) {
                textCount++; raw[i] = delta > 14
                if (delta > 45) { if (luminance(pixels[i]) > luminance(bg)) lighter += delta * delta else darker += delta * delta }
            } else { backgroundCount++; if (delta > 16) outside++ }
        }
        val count = raw.count { it }
        if (outside > maxOf(6, backgroundCount / 30) || count < 8 || count > textCount * .65 || lighter + darker < 4000) return null
        val ink = BooleanArray(raw.size); val grow = (letterHeight / 9).coerceIn(2, 6)
        for (i in raw.indices) if (raw[i]) for (dy in -grow..grow) for (dx in -grow..grow) {
            val x = i % w + dx; val y = i / w + dy
            if (x in 2 until w - 2 && y in 2 until h - 2) ink[y * w + x] = true
        }
        if (ink.count { it } > pixels.size * .60) return null
        return BubbleRegion(left, top, PixelMask(w, h, BooleanArray(w * h) { true }), ink, background(w / 2, h / 2),
            textColor = if (lighter > darker) Color.WHITE else Color.BLACK, inpaint = true)
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
