package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Point
import com.ruyo.ai.OcrLine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DetectedBubble(val region: BubbleRegion, val source: String)
data class DetectedGroup(val region: BubbleRegion, val centers: List<Point>, val bubbles: List<DetectedBubble>)

/** OCR locates dialogue; the same conservative selector owns cleanup and fitting. */
object AutoBubbleDetector {
    suspend fun detect(source: Bitmap, lines: List<OcrLine>, savedCenters: (BubbleRegion) -> List<Point>? = { null }): List<DetectedGroup> {
        require(lines.size <= 300) { "Too many text lines in one image." }
        val parents = mutableListOf<BubbleRegion>()
        val valid = lines.filter { it.text.isNotBlank() && it.left >= 0 && it.top >= 0 && it.right <= source.width && it.bottom <= source.height && it.right > it.left && it.bottom > it.top }
        for (line in valid) {
            currentCoroutineContext().ensureActive()
            if (parents.any { it.contains(line.x, line.y) }) continue
            if (parents.size == 40) break
            val selected = BubbleSelector.select(source, line.x, line.y) as? SelectionResult.Selected ?: continue
            if (parents.none { it.overlaps(selected.region) }) parents += selected.region
        }
        return parents.map { parent ->
            currentCoroutineContext().ensureActive()
            val centers = savedCenters(parent) ?: BubbleAreas.suggest(parent)
            val parts = BubbleAreas.split(parent, centers)
            val bubbles = parts.mapNotNull { region ->
                val assigned = valid.filter { region.contains(it.x, it.y) }.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
                // Never erase a line that was divided between two parts or outside the cleanup area.
                val whole = assigned.all { line ->
                    region.contains(line.left, line.y) && region.contains(line.right - 1, line.y) &&
                        region.contains(line.x, line.top) && region.contains(line.x, line.bottom - 1)
                }
                val text = assigned.joinToString("\n") { it.text }
                val ink = region.eraseMask.count { it }
                var uncovered = 0
                for (i in region.eraseMask.indices) if (region.eraseMask[i]) {
                    val x = region.left + i % region.width; val y = region.top + i / region.width
                    if (assigned.none { line ->
                        val halo = maxOf(3, (line.bottom - line.top) / 5)
                        x in (line.left - halo)..(line.right + halo) && y in (line.top - halo)..(line.bottom + halo)
                    }) uncovered++
                }
                // Missing OCR lines must not be erased along with recognized neighbors.
                val covered = uncovered <= maxOf(6, ink / 50)
                if (!covered || !whole || text.isBlank() || text.length > 2000 || region.eraseMask.none { it }) null else DetectedBubble(region, text)
            }
            DetectedGroup(parent, centers, bubbles)
        }
    }
}
