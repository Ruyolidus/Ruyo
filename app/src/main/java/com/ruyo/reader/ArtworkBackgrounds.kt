package com.ruyo.reader

import android.graphics.Bitmap
import java.lang.ref.WeakReference
import java.security.MessageDigest

/** Small process cache shared by editor previews and page composition. Never owns source bitmaps. */
internal object ArtworkBackgrounds {
    private data class Entry(val source: WeakReference<Bitmap>, val generation: Int, val key: String, val pixels: IntArray)
    private val entries = ArrayDeque<Entry>()
    private const val MAX_PIXELS = 2_000_000

    fun clean(source: Bitmap, region: BubbleRegion, otherRegions: List<BubbleRegion>): IntArray {
        val padding = (maxOf(region.width, region.height) / 3).coerceIn(24, 128)
        val left = (region.left - padding).coerceAtLeast(0)
        val top = (region.top - padding).coerceAtLeast(0)
        val right = (region.left + region.width + padding).coerceAtMost(source.width)
        val bottom = (region.top + region.height + padding).coerceAtMost(source.height)
        val w = right - left; val h = bottom - top
        require(w.toLong() * h <= MAX_PIXELS) { "Choose a smaller lettering area for artwork repair." }
        val mask = BooleanArray(w * h)
        val excluded = BooleanArray(w * h)
        for (y in 0 until region.height) for (x in 0 until region.width) {
            if (region.eraseMask[y * region.width + x] && region.interior[x, y])
                mask[(region.top - top + y) * w + region.left - left + x] = true
        }
        for (other in otherRegions) {
            if (other === region) continue
            for (y in maxOf(top, other.top) until minOf(bottom, other.top + other.height))
                for (x in maxOf(left, other.left) until minOf(right, other.left + other.width))
                    if (other.eraseMask[(y - other.top) * other.width + x - other.left]) excluded[(y - top) * w + x - left] = true
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$left,$top,$w,$h,${region.left},${region.top},${region.width},${region.height}".toByteArray())
        digest.update(ByteArray(mask.size) { (if (mask[it]) 1 else if (excluded[it]) 2 else 0).toByte() })
        val key = digest.digest().joinToString("") { "%02x".format(it) }
        synchronized(entries) {
            entries.removeAll { it.source.get() == null }
            entries.firstOrNull { it.source.get() === source && it.generation == source.generationId && it.key == key }?.let {
                entries.remove(it); entries.addLast(it); return it.pixels.copyOf()
            }
        }
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, left, top, w, h)
        val cleaned = LocalInpainter.repair(pixels, w, h, mask, excluded)
        val result = IntArray(region.width * region.height) {
            cleaned[(region.top - top + it / region.width) * w + region.left - left + it % region.width]
        }
        synchronized(entries) {
            while (entries.isNotEmpty() && entries.sumOf { it.pixels.size } + result.size > MAX_PIXELS) entries.removeFirst()
            entries.addLast(Entry(WeakReference(source), source.generationId, key, result.copyOf()))
        }
        return result
    }
}
