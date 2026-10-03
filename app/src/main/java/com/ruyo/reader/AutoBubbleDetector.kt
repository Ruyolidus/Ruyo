package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.ruyo.ai.OcrLine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DetectedBubble(val region: BubbleRegion, val source: String, val needsReview: Boolean = false)
data class DetectedGroup(val region: BubbleRegion, val centers: List<Point>, val bubbles: List<DetectedBubble>)
data class TextArea(val lines: List<OcrLine>, val bounds: Rect, val source: String, val region: BubbleRegion?, val needsReview: Boolean)

/** Text is the inventory. A bubble outline can improve layout, but cannot remove text from that inventory. */
object AutoBubbleDetector {
    suspend fun analyze(source: Bitmap, lines: List<OcrLine>, allLines: List<OcrLine> = lines, occupied: List<BubbleRegion> = emptyList(),
        savedCenters: (BubbleRegion) -> List<Point>? = { null }): List<TextArea> {
        require(lines.size <= 300) { "Too many text lines in one image." }
        val valid = lines.filter { it.text.isNotBlank() && it.left >= 0 && it.top >= 0 && it.right <= source.width && it.bottom <= source.height && it.right > it.left && it.bottom > it.top }
        val accepted = occupied.toMutableList()
        return TextRegionRepair.groups(valid).map { block ->
            currentCoroutineContext().ensureActive()
            val bounds = Rect(block.minOf { it.left }, block.minOf { it.top }, block.maxOf { it.right }, block.maxOf { it.bottom })
            val smooth = TextRegionRepair.select(source, block, allLines)
            var repair = smooth ?: TextRegionRepair.selectArtwork(source, block, allLines)
            // Only a successful lettering mask proceeds to optional outline/layout detection.
            if (repair != null && smooth != null) repair = roomForText(source, repair, block, allLines, savedCenters)
            repair = repair?.let { separateLayout(it, accepted) }
            repair?.let { accepted += it }
            TextArea(block, bounds, block.joinToString("\n") { it.text }, repair, smooth == null)
        }
    }

    /** Empty padding can meet another area's padding without making either text
     * disappear. Remove that shared layout space; never clip an erasure stroke. */
    private fun separateLayout(region: BubbleRegion, occupied: List<BubbleRegion>): BubbleRegion? {
        val neighbors = occupied.filter { it.overlaps(region) }
        if (neighbors.isEmpty()) return region
        val shape = BooleanArray(region.width*region.height) { i -> region.interior[i%region.width,i/region.width] }
        for (i in shape.indices) if (shape[i] && neighbors.any { it.contains(region.left+i%region.width,region.top+i/region.width) }) {
            if (region.eraseMask[i]) return null
            shape[i] = false
        }
        return region.copy(interior = PixelMask(region.width,region.height,shape))
    }

    suspend fun detect(source: Bitmap, lines: List<OcrLine>, savedCenters: (BubbleRegion) -> List<Point>? = { null }): List<DetectedGroup> =
        analyze(source, lines, savedCenters = savedCenters).mapNotNull { area -> area.region?.let { region ->
            DetectedGroup(region, listOf(Point(region.width / 2, region.height / 2)), listOf(DetectedBubble(region, area.source, area.needsReview)))
        } }

    private fun roomForText(source: Bitmap, repair: BubbleRegion, block: List<OcrLine>, allLines: List<OcrLine>, savedCenters: (BubbleRegion) -> List<Point>?): BubbleRegion {
        val letterHeight = block.maxOf { it.bottom - it.top }
        val pad = (letterHeight * 4).coerceIn(48, 240)
        val left = (repair.left - pad).coerceAtLeast(0); val top = (repair.top - pad).coerceAtLeast(0)
        val right = (repair.left + repair.width + pad).coerceAtMost(source.width)
        val bottom = (repair.top + repair.height + pad).coerceAtMost(source.height)
        if ((right - left).toLong() * (bottom - top) > 650_000) return repair
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        val selected = try { BubbleSelector.select(crop, block.first().x - left, block.first().y - top) as? SelectionResult.Selected }
        finally { if (crop !== source) crop.recycle() }
        val local = selected?.region ?: return repair
        val parent = local.copy(left = local.left + left, top = local.top + top)
        val centers = savedCenters(parent) ?: BubbleAreas.suggest(parent)
        val part = runCatching { BubbleAreas.split(parent, centers) }.getOrNull()?.firstOrNull { region -> block.all { region.contains(it.x, it.y) } } ?: return repair
        if (allLines.any { it !in block && part.contains(it.x, it.y) }) return repair
        val ink = BooleanArray(part.width * part.height)
        for (i in repair.eraseMask.indices) if (repair.eraseMask[i]) {
            val x = repair.left + i % repair.width; val y = repair.top + i / repair.width
            if (!part.contains(x, y)) return repair
            ink[(y - part.top) * part.width + x - part.left] = true
        }
        // Unknown source lettering must not become extra layout room for this translation.
        val uncovered = part.eraseMask.indices.count { i -> part.eraseMask[i] && !ink[i] }
        if (uncovered > maxOf(6, part.eraseMask.count { it } / 50)) return repair
        return part.copy(eraseMask = ink, backgroundColor = repair.backgroundColor, backgroundSurface = repair.backgroundSurface,
            textColor = repair.textColor, inpaint = repair.inpaint)
    }
}
