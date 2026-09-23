package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BubbleRegion(
    val left: Int,
    val top: Int,
    val interior: PixelMask,
    val eraseMask: BooleanArray,
    val backgroundColor: Int,
    val backgroundSurface: BackgroundSurface? = null,
    val textColor: Int = Color.rgb(39, 42, 53),
    val inpaint: Boolean = false,
) {
    val width get() = interior.width
    val height get() = interior.height
    init { require(eraseMask.size == width * height) }
    fun contains(x: Int, y: Int) = interior[x - left, y - top]
    fun overlaps(other: BubbleRegion): Boolean {
        val x0 = maxOf(left, other.left); val x1 = minOf(left + width, other.left + other.width)
        val y0 = maxOf(top, other.top); val y1 = minOf(top + height, other.top + other.height)
        for (y in y0 until y1) for (x in x0 until x1) if (contains(x, y) && other.contains(x, y)) return true
        return false
    }
}

data class BubbleEdit(
    val id: String = UUID.randomUUID().toString(),
    val region: BubbleRegion,
    val japanese: String,
    val margin: Int,
    val fontScale: Float = 1f,
    val languageTag: String = "ja",
    val fontFamily: String = "sans-serif",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val sourceLetterHeight: Float? = null,
    val matchSourceSize: Boolean = false,
)

sealed interface SelectionResult {
    data class Selected(val region: BubbleRegion) : SelectionResult
    data class Rejected(val reason: String, val retryNearby: Boolean = false, val retryOutline: Boolean = false) : SelectionResult
}

/** A deliberately limited, user-seeded selector for enclosed, light, flat bubbles. */
object BubbleSelector {
    fun select(source: Bitmap, x: Int, y: Int): SelectionResult {
        val w = source.width
        val h = source.height
        if (x !in 0 until w || y !in 0 until h) return SelectionResult.Rejected("Tap inside the image.")
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var outlineAttempted = false
        fun pick(xx: Int, yy: Int): SelectionResult {
            val selected = selectAt(pixels, w, h, xx, yy)
            if (selected is SelectionResult.Rejected && selected.retryOutline && !outlineAttempted) {
                outlineAttempted = true
                val repaired = selectThroughSmallGap(pixels, w, h, xx, yy)
                if (repaired is SelectionResult.Selected && repaired.region.contains(x, y) && repaired.region.eraseMask.any { it }) return repaired
            }
            return selected
        }
        val direct = pick(x, y)
        if (direct is SelectionResult.Selected && (direct.region.eraseMask.any { it } || minOf(direct.region.width, direct.region.height) > 72)) return direct
        if (direct is SelectionResult.Rejected && isLight(pixels[y * w + x]) && !direct.retryNearby) return direct
        // A tap can land on dark ink, a pale antialiased edge, or a white counter
        // inside a letter. Try nearby background in all three cases, accepting
        // only an interior that contains the original tap. An empty direct
        // selection is retained if no enclosing lettering-bearing region exists.
        var attempts = 0
        for (radius in listOf(3, 7, 14, 24, 36)) for ((dx, dy) in listOf(
            0 to -radius, -radius to 0, radius to 0, 0 to radius,
            -radius to -radius, radius to -radius, -radius to radius, radius to radius,
        )) {
            val xx = x + dx; val yy = y + dy
            if (xx !in 0 until w || yy !in 0 until h || !isLight(pixels[yy * w + xx])) continue
            if (attempts++ >= 32) return direct
            val nearby = pick(xx, yy)
            if (nearby is SelectionResult.Selected && nearby.region.contains(x, y) && nearby.region.eraseMask.any { it }) return nearby
        }
        return direct
    }

    private fun isLight(color: Int) = Color.alpha(color) >= 250 && minOf(Color.red(color), Color.green(color), Color.blue(color)) >= 175

    private fun selectThroughSmallGap(pixels: IntArray, w: Int, h: Int, x: Int, y: Int): SelectionResult {
        val cw = minOf(w, 1024); val ch = minOf(h, 1024)
        val left = (x - cw / 2).coerceIn(0, w - cw); val top = (y - ch / 2).coerceIn(0, h - ch)
        val crop = IntArray(cw * ch) { pixels[(top + it / cw) * w + left + it % cw] }
        return when (val result = selectAt(crop, cw, ch, x - left, y - top, seal = 2)) {
            is SelectionResult.Selected -> SelectionResult.Selected(result.region.copy(left = result.region.left + left, top = result.region.top + top))
            is SelectionResult.Rejected -> result
        }
    }

    private fun selectAt(pixels: IntArray, w: Int, h: Int, x: Int, y: Int, seal: Int = 0): SelectionResult {
        fun reject(message: String) = SelectionResult.Rejected(message)
        val seed = pixels[y * w + x]
        if (!isLight(seed)) {
            return reject("No enclosed light bubble found. Tap the lettering to try text-area cleanup. Detailed artwork may need manual repair.")
        }
        // Erosion closes only very narrow background leaks through a broken outline.
        // The accepted interior stays inset; source border pixels are never repainted.
        val allowed = if (seal > 0) PixelMask(w, h, BooleanArray(pixels.size) { distance(pixels[it], seed) <= 18 && Color.alpha(pixels[it]) >= 250 }).inset(seal) else null
        if (allowed != null && !allowed[x, y]) return reject("Try an empty spot farther inside this bubble.")
        val cap = min(pixels.size, 900_000)
        val queue = IntArray(cap)
        val visited = BooleanArray(pixels.size)
        var head = 0
        var tail = 1
        queue[0] = y * w + x
        visited[queue[0]] = true
        var left = x; var right = x; var top = y; var bottom = y
        var edge = false
        fun add(index: Int): Boolean {
            if (visited[index] || distance(pixels[index], seed) > 18 || Color.alpha(pixels[index]) < 250 || allowed != null && !allowed[index % w, index / w]) return true
            if (tail == cap) return false
            visited[index] = true
            queue[tail++] = index
            return true
        }
        while (head < tail) {
            val i = queue[head++]
            val xx = i % w; val yy = i / w
            left = min(left, xx); right = max(right, xx); top = min(top, yy); bottom = max(bottom, yy)
            if (xx <= seal || yy <= seal || xx >= w - 1 - seal || yy >= h - 1 - seal) edge = true
            if ((xx > 0 && !add(i - 1)) || (xx < w - 1 && !add(i + 1)) ||
                (yy > 0 && !add(i - w)) || (yy < h - 1 && !add(i + w))) {
                return SelectionResult.Rejected("This area is too large. Choose a smaller, enclosed speech bubble.", retryOutline = seal == 0)
            }
        }
        if (edge) return SelectionResult.Rejected("The detected background connects to the page edge. Try an empty spot inside another part of the bubble; its outline may be open or clipped.", retryOutline = seal == 0)
        val cw = right - left + 1; val ch = bottom - top + 1
        if (cw < 16 || ch < 16 || tail < 80) return SelectionResult.Rejected("There is too little bubble background at this image resolution. Try an empty area beside the letters, or a higher-quality source image.", retryNearby = true)
        if (cw.toLong() * ch > 900_000) return SelectionResult.Rejected("This area is too large. Choose a smaller, enclosed speech bubble.", retryOutline = seal == 0)

        // The connected background excludes lettering. Fill enclosed holes to recover the interior.
        val background = BooleanArray(cw * ch) { visited[(top + it / cw) * w + left + it % cw] }
        val outside = BooleanArray(background.size)
        val holes = IntArray(background.size)
        var a = 0; var b = 0
        fun outsideAdd(i: Int) {
            if (!background[i] && !outside[i]) { outside[i] = true; holes[b++] = i }
        }
        for (xx in 0 until cw) { outsideAdd(xx); outsideAdd((ch - 1) * cw + xx) }
        for (yy in 0 until ch) { outsideAdd(yy * cw); outsideAdd(yy * cw + cw - 1) }
        while (a < b) {
            val i = holes[a++]; val xx = i % cw; val yy = i / cw
            if (xx > 0) outsideAdd(i - 1)
            if (xx < cw - 1) outsideAdd(i + 1)
            if (yy > 0) outsideAdd(i - cw)
            if (yy < ch - 1) outsideAdd(i + cw)
        }
        val shape = BooleanArray(background.size) { !outside[it] }
        val interior = PixelMask(cw, ch, shape)
        val protectedRegion = interior.inset(2)
        val rs = IntArray(256); val gs = IntArray(256); val bs = IntArray(256)
        for (i in 0 until tail) { val c = pixels[queue[i]]; rs[Color.red(c)]++; gs[Color.green(c)]++; bs[Color.blue(c)]++ }
        fun median(histogram: IntArray): Int { var count = 0; for (i in histogram.indices) { count += histogram[i]; if (count >= tail / 2) return i }; return 255 }
        val color = Color.rgb(median(rs), median(gs), median(bs))
        val uneven = (0 until tail).count { distance(pixels[queue[it]], color) > 12 }
        if (uneven > tail / 12) return reject("The bubble background is uneven. Textured and gradient bubbles are not supported yet.")
        val rawInk = BooleanArray(shape.size)
        var colored = 0
        for (i in shape.indices) {
            val c = pixels[(top + i / cw) * w + left + i % cw]
            if (protectedRegion[i % cw, i / cw] && distance(c, color) > 24) {
                rawInk[i] = true
                if (maxOf(Color.red(c), Color.green(c), Color.blue(c)) - minOf(Color.red(c), Color.green(c), Color.blue(c)) > 55) colored++
            }
        }
        val inkCount = rawInk.count { it }
        if (inkCount > shape.count { it } * 0.25 || colored > max(10, inkCount / 15)) {
            return reject("This selection may contain artwork. Choose a plain dialogue bubble.")
        }
        val ink = BooleanArray(shape.size)
        for (i in rawInk.indices) if (rawInk[i]) for (dy in -1..1) for (dx in -1..1) {
            val xx = i % cw + dx; val yy = i / cw + dy
            if (protectedRegion[xx, yy]) ink[yy * cw + xx] = true
        }
        return SelectionResult.Selected(BubbleRegion(left, top, interior, ink, color))
    }

    private fun distance(a: Int, b: Int) = maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
}

data class BubblePreview(val crop: Bitmap, val fit: FitResult.Accepted)

object BubbleEditRenderer {
    // Start at ordinary dialogue size (about 16 dp on a 393 dp-wide reader),
    // independent of the amount of empty space in a large speech bubble.
    fun preferredSize(pageWidth: Int, scale: Float = 1f): Float = (pageWidth / 24f).coerceIn(14f, 60f) * scale

    fun preferredSize(pageWidth: Int, edit: BubbleEdit): Float {
        val height = edit.sourceLetterHeight
        val font = LetteringFont.fromId(edit.fontFamily).typeface(edit.bold, edit.italic)
        return if (edit.matchSourceSize && height != null && height.isFinite() && height > 0f)
            SourceLettering.preferredSize(height, edit.languageTag, font) * edit.fontScale
        else preferredSize(pageWidth, edit.fontScale)
    }

    fun preview(source: Bitmap, edit: BubbleEdit): Result<BubblePreview> = runCatching {
        val region = edit.region
        require(!region.inpaint || region.eraseMask.any { it }) { "Brush over the original letters, or use Rebuild cleanup, before previewing this area." }
        require(region.left >= 0 && region.top >= 0 && region.left + region.width <= source.width && region.top + region.height <= source.height) { "The bubble is outside the image." }
        val safe = region.interior.inset(edit.margin.coerceAtLeast(2))
        require(edit.fontScale.isFinite() && edit.fontScale in 0.6f..1.6f) { "Choose a text size between 60% and 160%." }
        val preferred = preferredSize(source.width, edit)
        val minimum = min(preferred, 6f)
        val result = BubbleFitter().fit(edit.japanese, safe, preferred, minimum,
            textColor = region.textColor, languageTag = edit.languageTag, typeface = LetteringFont.fromId(edit.fontFamily).typeface(edit.bold, edit.italic))
        require(result is FitResult.Accepted) { (result as FitResult.Rejected).reason }
        val pixels = IntArray(region.width * region.height)
        source.getPixels(pixels, 0, region.width, region.left, region.top, region.width, region.height)
        if (region.inpaint) {
            val mask = BooleanArray(pixels.size) { region.eraseMask[it] && region.interior[it % region.width, it / region.width] }
            LocalInpainter.repair(pixels, region.width, region.height, mask).copyInto(pixels)
        } else for (i in pixels.indices) if (region.eraseMask[i] && region.interior[i % region.width, i / region.width]) pixels[i] =
            region.backgroundSurface?.colorAt(region.left + i % region.width, region.top + i / region.width) ?: region.backgroundColor
        val crop = Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888)
        crop.setPixels(pixels, 0, region.width, 0, 0, region.width, region.height)
        Canvas(crop).drawBitmap(result.ink, 0f, 0f, null)
        BubblePreview(crop, result)
    }

    fun composite(source: Bitmap, edits: List<BubbleEdit>): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        for (edit in edits) {
            val preview = preview(source, edit).getOrThrow()
            // Transparent outside the explicit repair/ink footprint, so overlapping
            // bounding rectangles cannot erase a previously composed bubble.
            val pixels = IntArray(edit.region.width * edit.region.height)
            val ink = IntArray(pixels.size)
            preview.crop.getPixels(pixels, 0, edit.region.width, 0, 0, edit.region.width, edit.region.height)
            preview.fit.ink.getPixels(ink, 0, edit.region.width, 0, 0, edit.region.width, edit.region.height)
            for (i in pixels.indices) if (!edit.region.eraseMask[i] && Color.alpha(ink[i]) == 0) pixels[i] = Color.TRANSPARENT
            preview.crop.setPixels(pixels, 0, edit.region.width, 0, 0, edit.region.width, edit.region.height)
            canvas.drawBitmap(preview.crop, edit.region.left.toFloat(), edit.region.top.toFloat(), null)
            preview.crop.recycle()
            preview.fit.ink.recycle()
        }
        return bitmap
    }
}
