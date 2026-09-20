package com.ruyo.reader

import android.graphics.Point
import kotlin.math.roundToInt

/** Connected speech balloons contain independent layout and repair regions. */
object BubbleAreas {
    const val MAX_AREAS = 12

    fun suggest(region: BubbleRegion): List<Point> {
        val w = region.width; val h = region.height
        for (fraction in listOf(.12f, .18f, .24f)) {
            val mask = region.interior.inset(maxOf(3, (minOf(w, h) * fraction).roundToInt()))
            val seen = BooleanArray(w * h)
            val queue = IntArray(w * h)
            val centers = mutableListOf<Point>()
            for (start in seen.indices) if (!seen[start] && mask[start % w, start / w]) {
                var head = 0; var tail = 1; queue[0] = start; seen[start] = true
                var sx = 0L; var sy = 0L
                while (head < tail) {
                    val i = queue[head++]; val x = i % w; val y = i / w
                    sx += x; sy += y
                    fun add(xx: Int, yy: Int) {
                        if (mask[xx, yy] && !seen[yy * w + xx]) { seen[yy * w + xx] = true; queue[tail++] = yy * w + xx }
                    }
                    add(x - 1, y); add(x + 1, y); add(x, y - 1); add(x, y + 1)
                }
                if (tail >= maxOf(40, w * h / 150)) {
                    val cx = sx.toFloat() / tail; val cy = sy.toFloat() / tail
                    var nearest = queue[0]; var best = Float.MAX_VALUE
                    for (n in 0 until tail) {
                        val i = queue[n]; val dx = i % w - cx; val dy = i / w - cy
                        if (dx * dx + dy * dy < best) { best = dx * dx + dy * dy; nearest = i }
                    }
                    centers += Point(nearest % w, nearest / w)
                }
            }
            if (centers.size in 2..MAX_AREAS) {
                val ordered = centers.sortedWith(compareBy<Point> { it.y }.thenBy { it.x })
                val parts = runCatching { split(region, ordered) }.getOrNull()
                if (parts != null && parts.all { it.eraseMask.count { bit -> bit } >= 12 }) return ordered
            }
        }
        var center = -1; var best = Long.MAX_VALUE
        for (y in 0 until h) for (x in 0 until w) if (region.interior[x, y]) {
            val dx = (x - w / 2).toLong(); val dy = (y - h / 2).toLong()
            if (dx * dx + dy * dy < best) { best = dx * dx + dy * dy; center = y * w + x }
        }
        require(center >= 0) { "This bubble has no usable interior." }
        return listOf(Point(center % w, center / w))
    }

    fun split(region: BubbleRegion, centers: List<Point>): List<BubbleRegion> {
        require(centers.size in 1..MAX_AREAS) { "Mark between 1 and 12 text areas." }
        val w = region.width; val h = region.height
        require(centers.distinctBy { it.x to it.y }.size == centers.size) { "Place centers farther apart." }
        require(centers.all { region.interior[it.x, it.y] }) { "Place each center inside the bubble." }
        if (centers.size == 1) return listOf(region)
        val owners = IntArray(w * h) { -1 }
        val queue = IntArray(w * h)
        var head = 0; var tail = 0
        centers.forEachIndexed { index, point -> val i = point.y * w + point.x; owners[i] = index; queue[tail++] = i }
        while (head < tail) {
            val i = queue[head++]; val x = i % w; val y = i / w
            fun add(xx: Int, yy: Int) {
                if (region.interior[xx, yy] && owners[yy * w + xx] == -1) {
                    owners[yy * w + xx] = owners[i]; queue[tail++] = yy * w + xx
                }
            }
            add(x - 1, y); add(x + 1, y); add(x, y - 1); add(x, y + 1)
        }
        for (i in owners.indices) if (region.interior[i % w, i / w]) {
            require(owners[i] >= 0) { "An area is disconnected. Add a center inside it." }
            if (region.eraseMask[i]) {
                val x = i % w; val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (region.interior[xx, yy]) require(owners[yy * w + xx] == owners[i]) {
                        "A dividing line touches lettering. Move the centers farther into their text blocks."
                    }
                }
            }
        }
        return centers.indices.map { owner ->
            var left = w; var top = h; var right = -1; var bottom = -1
            for (i in owners.indices) if (owners[i] == owner) {
                left = minOf(left, i % w); right = maxOf(right, i % w)
                top = minOf(top, i / w); bottom = maxOf(bottom, i / w)
            }
            val cw = right - left + 1; val ch = bottom - top + 1
            require(cw >= 16 && ch >= 16) { "One area is too small. Move or remove its center." }
            val shape = BooleanArray(cw * ch) { owners[(top + it / cw) * w + left + it % cw] == owner }
            val erase = BooleanArray(shape.size) { shape[it] && region.eraseMask[(top + it / cw) * w + left + it % cw] }
            BubbleRegion(region.left + left, region.top + top, PixelMask(cw, ch, shape), erase, region.backgroundColor)
        }
    }
}
