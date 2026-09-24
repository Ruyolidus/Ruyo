package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Color
import com.ruyo.ai.OcrLine
import kotlin.math.abs
import kotlin.math.roundToInt

/** A persisted bilinear or row-sampled surface. Coordinates stay on the page when areas split. */
data class BackgroundSurface(val left: Int, val top: Int, val width: Int, val height: Int, val corners: List<Int>, val rows: List<Int> = emptyList(),
    val coefficients: List<Double> = emptyList()) {
    init {
        require(left >= 0 && top >= 0 && width in 2..16_384 && height in 2..16_384 && corners.size == 4)
        require(rows.isEmpty() || rows.size == height * 2)
        require(coefficients.isEmpty() || (coefficients.size == 18 && coefficients.all { it.isFinite() } && rows.isEmpty()))
    }
    fun colorAt(x: Int, y: Int): Int {
        val xx = ((x - left).toDouble() / (width - 1)).coerceIn(0.0, 1.0)
        val yy = ((y - top).toDouble() / (height - 1)).coerceIn(0.0, 1.0)
        if (coefficients.isNotEmpty()) {
            fun channel(offset: Int) = (coefficients[offset]+coefficients[offset+1]*xx+coefficients[offset+2]*yy+
                coefficients[offset+3]*xx*xx+coefficients[offset+4]*xx*yy+coefficients[offset+5]*yy*yy).roundToInt().coerceIn(0,255)
            return Color.rgb(channel(0),channel(6),channel(12))
        }
        if (rows.isNotEmpty()) {
            val row = (y - top).coerceIn(0, height - 1) * 2
            fun channel(get: (Int) -> Int) = (get(rows[row]) * (1 - xx) + get(rows[row + 1]) * xx).roundToInt().coerceIn(0, 255)
            return Color.rgb(channel(Color::red), channel(Color::green), channel(Color::blue))
        }
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
                    line.top >= last.top + height * .30 && line.top - last.bottom <= height * .55 &&
                        otherHeight in (height / 2)..(height * 2) && overlap >= minOf(line.right - line.left, last.right - last.left) * .5
                } ?: break
                group += next; remaining.remove(next)
            }
            output += group
        }
        return output
    }

    fun select(source: Bitmap, lines: List<OcrLine>, allLines: List<OcrLine> = lines): BubbleRegion? =
        selectUniform(source, lines, allLines) ?: selectFlat(source, lines, allLines) ?: selectShaded(source, lines, allLines) ?: selectCurved(source,lines,allLines)

    /** Whole foreground components on a stable background; outlines entering the crop stay protected. */
    private fun selectUniform(source: Bitmap, lines: List<OcrLine>, allLines: List<OcrLine>): BubbleRegion? {
        if (lines.isEmpty() || lines.size > 8) return null
        val size = lines.map { it.bottom - it.top }.sorted()[lines.size / 2]
        if (size < 8) return null
        val halo = (size / 2).coerceIn(5, 40)
        val pad = halo + 8
        val left = (lines.minOf { it.left } - pad).coerceAtLeast(0)
        val top = (lines.minOf { it.top } - pad).coerceAtLeast(0)
        val right = (lines.maxOf { it.right } + pad).coerceAtMost(source.width)
        val bottom = (lines.maxOf { it.bottom } + pad).coerceAtMost(source.height)
        val w = right - left; val h = bottom - top
        if (w < 16 || h < 16 || w.toLong() * h > 900_000) return null
        fun inText(x: Int, y: Int, extra: Int = 0) = lines.any { x in it.left - extra until it.right + extra && y in it.top - extra until it.bottom + extra }
        val pixels = IntArray(w * h); source.getPixels(pixels, 0, w, left, top, w, h)
        val samples = pixels.indices.filter { !inText(left + it % w, top + it / w, halo / 2) }.map { pixels[it] }
        if (samples.size < 24) return null
        fun median(channel: (Int) -> Int) = samples.map(channel).sorted()[samples.size / 2]
        val background = Color.rgb(median(Color::red), median(Color::green), median(Color::blue))
        if (samples.count { distance(it, background) <= 10 } < samples.size * .70) return null
        val bins = IntArray(512)
        fun bin(c: Int) = Color.red(c) / 32 * 64 + Color.green(c) / 32 * 8 + Color.blue(c) / 32
        for (i in pixels.indices) if (inText(left + i % w, top + i / w) && distance(pixels[i], background) > 45) bins[bin(pixels[i])]++
        val dominant = bins.indices.maxBy { bins[it] }
        if (bins[dominant] < 8) return null
        val target = Color.rgb(dominant / 64 * 32 + 16, dominant / 8 % 8 * 32 + 16, dominant % 8 * 32 + 16)
        val foreground = BooleanArray(pixels.size) { distance(pixels[it], background) > 14 }
        val protected = BooleanArray(pixels.size); val queue = IntArray(pixels.size)
        val boundary = pixels.indices.filter { foreground[it] && (it % w == 0 || it % w == w - 1 || it / w == 0 || it / w == h - 1) }
        val borderBins = IntArray(512); boundary.forEach { borderBins[bin(pixels[it])]++ }
        val borderBin = borderBins.indices.maxBy { borderBins[it] }
        val borderColor = Color.rgb(borderBin / 64 * 32 + 16, borderBin / 8 % 8 * 32 + 16, borderBin % 8 * 32 + 16)
        fun borderPixel(i: Int): Boolean {
            if (!foreground[i]) return false
            val dr=Color.red(borderColor)-Color.red(background);val dg=Color.green(borderColor)-Color.green(background);val db=Color.blue(borderColor)-Color.blue(background)
            val denominator=dr*dr+dg*dg+db*db
            if (denominator == 0) return false
            val c=pixels[i]
            val alpha=((Color.red(c)-Color.red(background))*dr+(Color.green(c)-Color.green(background))*dg+(Color.blue(c)-Color.blue(background))*db).toDouble()/denominator
            val mixed=Color.rgb((Color.red(background)+dr*alpha.coerceIn(0.0,1.0)).roundToInt(),
                (Color.green(background)+dg*alpha.coerceIn(0.0,1.0)).roundToInt(),(Color.blue(background)+db*alpha.coerceIn(0.0,1.0)).roundToInt())
            return distance(c,mixed)<=14
        }
        var head = 0; var tail = 0
        for (i in boundary) if (borderPixel(i)) { protected[i] = true; queue[tail++] = i }
        fun adjacent(i: Int, visit: (Int) -> Unit) {
            val x = i % w; val y = i / w
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) visit(yy * w + xx)
            }
        }
        while (head < tail) adjacent(queue[head++]) { j -> if (borderPixel(j) && !protected[j]) { protected[j] = true; queue[tail++] = j } }
        val textPixels = pixels.indices.count { inText(left + it % w, top + it / w) }
        val entering = pixels.indices.count { protected[it] && inText(left + it % w, top + it / w) }
        val crosses = ((0 until w).any { protected[it] } && (0 until w).any { protected[(h-1)*w+it] }) ||
            ((0 until h).any { protected[it*w] } && (0 until h).any { protected[it*w+w-1] })
        if (entering > maxOf(8,textPixels/25) || (crosses && entering>3)) return null
        // A skewed OCR box can contain an unrelated black balloon curve beside
        // colored letters. Reject connections to the lettering's actual color,
        // not every protected pixel inside its rectangular OCR bounds.
        fun sourceColor(c: Int) = distance(c, target) <= 40 &&
            abs((Color.red(c)-Color.green(c))-(Color.red(target)-Color.green(target))) < 12 &&
            abs((Color.green(c)-Color.blue(c))-(Color.green(target)-Color.blue(target))) < 12
        val central = pixels.indices.count { i -> protected[i] && sourceColor(pixels[i]) && lines.any {
            left+i%w > it.left+(it.right-it.left)*.15 && left+i%w < it.right-(it.right-it.left)*.15 &&
                top+i/w > it.top+(it.bottom-it.top)*.15 && top+i/w < it.bottom-(it.bottom-it.top)*.15
        } }
        if (central > 4) return null
        val seen = BooleanArray(pixels.size); val raw = BooleanArray(pixels.size)
        for (start in pixels.indices) if (foreground[start] && !protected[start] && !seen[start]) {
            head = 0; tail = 1; queue[0] = start; seen[start] = true
            var hits = 0; var x0 = w; var x1 = 0; var y0 = h; var y1 = 0
            while (head < tail) {
                val i = queue[head++]; val x = i % w; val y = i / w
                if (inText(left + x, top + y, halo / 2)) hits++
                x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
                adjacent(i) { j -> if (foreground[j] && !protected[j] && !seen[j]) { seen[j] = true; queue[tail++] = j } }
            }
            if (hits < 2 || tail < 3 || y1 - y0 > size * 1.8 || x1 - x0 > size * 5) continue
            for (n in 0 until tail) raw[queue[n]] = true
        }
        val ink = BooleanArray(pixels.size); val grow = (size / 10).coerceIn(2, 5)
        for (i in raw.indices) if (raw[i]) for (dy in -grow..grow) for (dx in -grow..grow) {
            val x = i % w + dx; val y = i / w + dy
            if (x in 1 until w - 1 && y in 1 until h - 1 && !protected[y * w + x]) ink[y * w + x] = true
        }
        if (ink.count { it } < 8 || ink.count { it } > pixels.size * .65) return null
        val marked = ink.indices.filter { ink[it] }
        val edge = maxOf(3, size / 5)
        val x0 = (marked.minOf { it % w } - edge).coerceAtLeast(0); val y0 = (marked.minOf { it / w } - edge).coerceAtLeast(0)
        val x1 = (marked.maxOf { it % w } + edge + 1).coerceAtMost(w); val y1 = (marked.maxOf { it / w } + edge + 1).coerceAtMost(h)
        val cw = x1 - x0; val ch = y1 - y0
        if (allLines.any { it !in lines && it.left < left + x1 && it.right > left + x0 && it.top < top + y1 && it.bottom > top + y0 }) return null
        val mask = BooleanArray(cw * ch) { ink[(it / cw + y0) * w + it % cw + x0] }
        val backgroundSamples = pixels.indices.filter { !inText(left+it%w,top+it/w,halo/2) && distance(pixels[it],background)<=10 }
            .map { Sample((it%w).toDouble()/(w-1),(it/w).toDouble()/(h-1),pixels[it]) }
        val plane = fit(backgroundSamples)
        val corners = plane?.let { listOf(it.color(0.0,0.0),it.color(1.0,0.0),it.color(0.0,1.0),it.color(1.0,1.0)) }
            ?: List(4) { background }
        return BubbleRegion(left + x0, top + y0, PixelMask(cw, ch, BooleanArray(cw * ch) { true }), mask, background,
            BackgroundSurface(left, top, w, h, corners),
            textColor = if (luminance(target) > luminance(background)) Color.WHITE else Color.BLACK)
    }

    /** Uniform, high-contrast lettering over artwork. Ambiguous components need a manual mask. */
    fun selectArtwork(source: Bitmap, lines: List<OcrLine>, allLines: List<OcrLine> = lines): BubbleRegion? {
        if (lines.isEmpty() || lines.size > 8) return null
        val size = lines.map { it.bottom - it.top }.sorted()[lines.size / 2]
        if (size < 12) return null
        val pad = (size / 2).coerceIn(8, 28)
        val left = lines.minOf { it.left } - pad; val top = lines.minOf { it.top } - pad
        val right = lines.maxOf { it.right } + pad; val bottom = lines.maxOf { it.bottom } + pad
        val w = right - left; val h = bottom - top
        if (left < 0 || top < 0 || right > source.width || bottom > source.height || w.toLong() * h > 400_000 || w < 16 || h < 16) return null
        if (allLines.any { it !in lines && it.left < right && it.right > left && it.top < bottom && it.bottom > top }) return null
        val pixels = IntArray(w * h); source.getPixels(pixels, 0, w, left, top, w, h)
        val gray = IntArray(pixels.size) { luminance(pixels[it]).roundToInt() }
        val radius = (size / 3).coerceIn(4, 16)
        val sum = IntArray((w + 1) * (h + 1)); val stride = w + 1
        for (y in 0 until h) { var row = 0; for (x in 0 until w) { row += gray[y * w + x]; sum[(y + 1) * stride + x + 1] = sum[y * stride + x + 1] + row } }
        val contrast = IntArray(pixels.size); val bins = IntArray(512)
        fun bin(c: Int) = (Color.red(c) / 32) * 64 + (Color.green(c) / 32) * 8 + Color.blue(c) / 32
        val outline = (size / 6).coerceIn(2, 8)
        fun inText(x: Int, y: Int) = lines.any { x in it.left - outline until it.right + outline && y in it.top - outline until it.bottom + outline }
        val surrounding = pixels.indices.filter { !inText(left+it%w,top+it/w) }.map { pixels[it] }
        if (surrounding.size < 12) return null
        fun edgeMedian(channel: (Int)->Int) = surrounding.map(channel).sorted()[surrounding.size/2]
        val surroundingColor = Color.rgb(edgeMedian(Color::red),edgeMedian(Color::green),edgeMedian(Color::blue))
        var textArea = 0
        for (y in 0 until h) for (x in 0 until w) if (inText(left + x, top + y)) {
            textArea++
            val l = maxOf(0, x - radius); val r = minOf(w, x + radius + 1); val t = maxOf(0, y - radius); val b = minOf(h, y + radius + 1)
            val mean = (sum[b * stride + r] - sum[t * stride + r] - sum[b * stride + l] + sum[t * stride + l]) / ((r - l) * (b - t))
            val i = y * w + x; contrast[i] = gray[i] - mean
            // Dense black type makes the pale paper around it locally high-contrast
            // too. That paper must never win the vote for the lettering color.
            if (abs(contrast[i]) > 40 && distance(pixels[i],surroundingColor)>45) bins[bin(pixels[i])]++
        }
        val dominant = bins.indices.maxBy { bins[it] }
        if (bins[dominant] < maxOf(12, textArea / 45)) return null
        val target = Color.rgb(dominant / 64 * 32 + 16, dominant / 8 % 8 * 32 + 16, dominant % 8 * 32 + 16)
        val raw = BooleanArray(pixels.size) { inText(left + it % w, top + it / w) && distance(pixels[it], target) <= 40 }
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
            if (y1 - y0 > size * 1.3 || x1 - x0 > maxOf(size * 5, lines.maxOf { it.right - it.left } + outline * 2)) return null
            components++; kept += b
            val grow = (size / 8).coerceIn(2, 5)
            for (n in 0 until b) for (dy in -grow..grow) for (dx in -grow..grow) {
                val x = queue[n] % w + dx; val y = queue[n] / w + dy
                if (x in 2 until w - 2 && y in 2 until h - 2) mask[y * w + x] = true
            }
        }
        // An outlined glyph includes its enclosed dark center. Leaving the center out
        // feeds the old letter back into the inpainter and produces letter-shaped smears.
        val outside = BooleanArray(mask.size); var head = 0; var tail = 0
        for (i in mask.indices) if (!mask[i] && (i % w == 0 || i % w == w - 1 || i / w == 0 || i / w == h - 1)) { outside[i] = true; queue[tail++] = i }
        while (head < tail) {
            val i = queue[head++]; val x = i % w; val y = i / w
            for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x + 1 < w) i + 1 else -1, if (y > 0) i - w else -1, if (y + 1 < h) i + w else -1))
                if (j >= 0 && !mask[j] && !outside[j]) { outside[j] = true; queue[tail++] = j }
        }
        for (i in mask.indices) if (!outside[i]) mask[i] = true
        if (components < 1 || kept < textArea / 80 || kept > textArea * .60 || mask.count { it } > pixels.size * .70) return null
        return BubbleRegion(left, top, PixelMask(w, h, BooleanArray(w * h) { true }), mask, pixels.first(),
            textColor = if (luminance(target) > 160) Color.WHITE else Color.BLACK, inpaint = true)
    }

    /** Keep the bubble's layout shape, but isolate cleanup from OCR lettering bounds. */
    fun refine(source: Bitmap, parent: BubbleRegion, lines: List<OcrLine>, allLines: List<OcrLine> = lines,
        allowExpansion: Boolean = false): BubbleRegion {
        val text = select(source, lines, allLines) ?: selectArtwork(source, lines, allLines) ?: return parent
        val ink = BooleanArray(parent.width * parent.height)
        for (i in text.eraseMask.indices) if (text.eraseMask[i]) {
            val x = text.left + i % text.width; val y = text.top + i / text.width
            if (!parent.contains(x, y)) return if (allowExpansion) text else parent
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
        fun inText(x: Int, y: Int) = lines.any { x in (it.left - maxOf(halo, letterHeight / 2)) until (it.right + maxOf(halo, letterHeight / 2)) && y in (it.top - halo) until (it.bottom + halo) }
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
        fun inText(x: Int, y: Int) = lines.any { x in (it.left - maxOf(halo, letterHeight / 2)) until (it.right + maxOf(halo, letterHeight / 2)) && y in (it.top - halo) until (it.bottom + halo) }
        val raw = BooleanArray(w * h); var outside = 0; var backgroundCount = 0; var textCount = 0
        var darker = 0L; var lighter = 0L
        for (i in pixels.indices) {
            if (Color.alpha(pixels[i]) < 250) return null
            val x = i % w; val y = i / w; val bg = background(x, y); val delta = distance(pixels[i], bg)
            // A strong stroke entering from outside the padded text area belongs to artwork or a border.
            if (delta > 32 && (x < 2 || y < 2 || x >= w - 2 || y >= h - 2)) return null
            if (inText(left + x, top + y)) {
                textCount++; raw[i] = delta > 8
                if (delta > 45) { if (luminance(pixels[i]) > luminance(bg)) lighter += delta * delta else darker += delta * delta }
            } else { backgroundCount++; if (delta > 16) outside++ }
        }
        val count = raw.count { it }
        if (outside > maxOf(6, backgroundCount / 30) || count < 8 || count > textCount * .95 || lighter + darker < 4000) return null
        val ink = BooleanArray(raw.size); val grow = (letterHeight / 7).coerceIn(3, 7)
        for (i in raw.indices) if (raw[i]) for (dy in -grow..grow) for (dx in -grow..grow) {
            val x = i % w + dx; val y = i / w + dy
            if (x in 2 until w - 2 && y in 2 until h - 2) ink[y * w + x] = true
        }
        if (ink.count { it } > pixels.size * .80) return null
        val surface = BackgroundSurface(left, top, w, h,
            listOf(rows.first().first, rows.first().second, rows.last().first, rows.last().second), rows.flatMap { listOf(it.first, it.second) })
        return BubbleRegion(left, top, PixelMask(w, h, BooleanArray(w * h) { true }), ink, background(w / 2, h / 2), surface,
            textColor = if (lighter > darker) Color.WHITE else Color.BLACK)
    }

    /** Smooth two-dimensional shading needs more than a flat fill or row interpolation.
     * Fit only exposed background, then remove both bright type and its darker shadow. */
    private fun selectCurved(source: Bitmap, lines: List<OcrLine>, allLines: List<OcrLine>): BubbleRegion? {
        if(lines.isEmpty() || lines.size>8) return null
        val size=lines.map { it.bottom-it.top }.sorted()[lines.size/2]
        val halo=(size/4).coerceIn(4,16);val pad=halo+(size/3).coerceIn(6,20)
        val left=lines.minOf { it.left }-pad;val top=lines.minOf { it.top }-pad
        val right=lines.maxOf { it.right }+pad;val bottom=lines.maxOf { it.bottom }+pad
        val w=right-left;val h=bottom-top
        if(left<0||top<0||right>source.width||bottom>source.height||w<16||h<16||w.toLong()*h>900_000) return null
        if(allLines.any { it !in lines && it.left<right && it.right>left && it.top<bottom && it.bottom>top }) return null
        fun inText(x:Int,y:Int)=lines.any { x in it.left-maxOf(halo,size/2) until it.right+maxOf(halo,size/2) && y in it.top-halo until it.bottom+halo }
        val pixels=IntArray(w*h);source.getPixels(pixels,0,w,left,top,w,h)
        if(pixels.any { Color.alpha(it)<250 }) return null
        val samples=mutableListOf<Sample>();val step=maxOf(1,maxOf(w,h)/120)
        for(y in 0 until h step step) for(x in 0 until w step step) if(!inText(left+x,top+y)) samples+=Sample(x.toDouble()/(w-1),y.toDouble()/(h-1),pixels[y*w+x])
        if(samples.size<48) return null
        var active:List<Sample> = samples
        var surface:BackgroundSurface?=null
        repeat(3) {
            val matrix=Array(6) { DoubleArray(9) }
            for(s in active) {
                val v=doubleArrayOf(1.0,s.x,s.y,s.x*s.x,s.x*s.y,s.y*s.y)
                val channels=intArrayOf(Color.red(s.color),Color.green(s.color),Color.blue(s.color))
                for(row in 0..5) {
                    for(col in 0..5) matrix[row][col]+=v[row]*v[col]
                    for(channel in 0..2) matrix[row][6+channel]+=v[row]*channels[channel]
                }
            }
            for(col in 0..5) {
                val pivot=(col..5).maxBy { abs(matrix[it][col]) };val swap=matrix[col];matrix[col]=matrix[pivot];matrix[pivot]=swap
                val factor=matrix[col][col];if(abs(factor)<.00000001) return null
                for(j in col..8) matrix[col][j]/=factor
                for(row in 0..5) if(row!=col) { val multiple=matrix[row][col];for(j in col..8) matrix[row][j]-=multiple*matrix[col][j] }
            }
            val coefficients=(0..2).flatMap { channel -> (0..5).map { matrix[it][channel+6] } }
            val current=BackgroundSurface(left,top,w,h,List(4) { pixels.first() },coefficients=coefficients)
            active=samples.filter { distance(it.color,current.colorAt(left+(it.x*(w-1)).roundToInt(),top+(it.y*(h-1)).roundToInt()))<=10 }
            if(active.size<samples.size*.97) return null
            surface=current
        }
        val fitted=requireNotNull(surface)
        val grid=(0..4).flatMap { y -> (0..4).map { x -> fitted.colorAt(left+x*(w-1)/4,top+y*(h-1)/4) } }
        if(grid.any { a -> grid.any { b -> distance(a,b)>110 } }) return null
        val raw=BooleanArray(pixels.size);var outside=0;var bgCount=0;var textCount=0;var lighter=0L;var darker=0L
        for(i in pixels.indices) {
            val x=i%w;val y=i/w;val bg=fitted.colorAt(left+x,top+y);val delta=distance(pixels[i],bg)
            if(delta>32 && (x<2||y<2||x>=w-2||y>=h-2)) return null
            if(inText(left+x,top+y)) {
                textCount++;raw[i]=delta>8
                if(delta>45) { if(luminance(pixels[i])>luminance(bg)) lighter+=delta*delta else darker+=delta*delta }
            } else { bgCount++;if(delta>16) outside++ }
        }
        val count=raw.count { it }
        if(outside>maxOf(6,bgCount/30)||count<8||count>textCount*.96||lighter+darker<4000) return null
        val mask=BooleanArray(raw.size);val grow=(size/7).coerceIn(3,7)
        for(i in raw.indices) if(raw[i]) for(dy in -grow..grow) for(dx in -grow..grow) {
            val x=i%w+dx;val y=i/w+dy
            if(x in 2 until w-2 && y in 2 until h-2) mask[y*w+x]=true
        }
        if(mask.count { it }>pixels.size*.90) return null
        val corners=listOf(fitted.colorAt(left,top),fitted.colorAt(right-1,top),fitted.colorAt(left,bottom-1),fitted.colorAt(right-1,bottom-1))
        return BubbleRegion(left,top,PixelMask(w,h,BooleanArray(w*h) { true }),mask,fitted.colorAt(left+w/2,top+h/2),fitted.copy(corners=corners),
            textColor=if(lighter>darker) Color.WHITE else Color.BLACK)
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
