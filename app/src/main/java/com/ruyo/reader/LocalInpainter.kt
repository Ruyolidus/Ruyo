package com.ruyo.reader

import android.graphics.Color
import kotlin.math.roundToInt

/** Fill a lettering mask from its boundary inward. Never writes an unmasked pixel.
 * This local reconstruction is useful for narrow strokes over shading/texture;
 * large missing objects still need an artist, not an invented flat background.
 */
object LocalInpainter {
    fun repair(source: IntArray, width: Int, height: Int, mask: BooleanArray): IntArray {
        require(source.size == width * height && mask.size == source.size)
        val count = mask.count { it }
        require(count <= 400_000 && count < source.size * .75) { "The cleanup mask is too large. Brush only over the lettering." }
        val output = source.copyOf()
        if (count == 0) return output
        val distance = IntArray(source.size) { if (mask[it]) Int.MAX_VALUE else 0 }
        val queue = IntArray(count); var head = 0; var tail = 0
        fun neighbors(i: Int): IntArray {
            val x = i % width; val y = i / width
            return intArrayOf(if (x > 0) i - 1 else -1, if (x + 1 < width) i + 1 else -1,
                if (y > 0) i - width else -1, if (y + 1 < height) i + width else -1)
        }
        for (i in mask.indices) if (mask[i] && neighbors(i).any { it >= 0 && !mask[it] }) {
            distance[i] = 1; queue[tail++] = i
        }
        while (head < tail) {
            val i = queue[head++]
            for (j in neighbors(i)) if (j >= 0 && distance[j] == Int.MAX_VALUE) { distance[j] = distance[i] + 1; queue[tail++] = j }
        }
        require(tail == count) { "Leave some original pixels around the cleanup mask." }
        for (n in 0 until tail) {
            val i = queue[n]; val x = i % width; val y = i / width
            var red = 0.0; var green = 0.0; var blue = 0.0; var weight = 0.0
            fun valid(xx: Int, yy: Int) = xx in 0 until width && yy in 0 until height && distance[yy * width + xx] < distance[i]
            for (dy in -3..3) for (dx in -3..3) {
                val xx = x + dx; val yy = y + dy
                if (dx * dx + dy * dy !in 1..9 || !valid(xx, yy)) continue
                val c = output[yy * width + xx]
                val factor = 1.0 / (dx * dx + dy * dy)
                fun estimate(channel: (Int) -> Int): Double {
                    val gx = if (valid(xx - 1, yy) && valid(xx + 1, yy)) (channel(output[yy * width + xx + 1]) - channel(output[yy * width + xx - 1])) / 2.0 else 0.0
                    val gy = if (valid(xx, yy - 1) && valid(xx, yy + 1)) (channel(output[(yy + 1) * width + xx]) - channel(output[(yy - 1) * width + xx])) / 2.0 else 0.0
                    val correction = (-dx * gx - dy * gy).coerceIn(-24.0, 24.0)
                    return (channel(c) + correction).coerceIn(0.0, 255.0)
                }
                red += estimate(Color::red) * factor; green += estimate(Color::green) * factor; blue += estimate(Color::blue) * factor; weight += factor
            }
            require(weight > 0)
            output[i] = Color.rgb((red / weight).roundToInt(), (green / weight).roundToInt(), (blue / weight).roundToInt())
        }
        return output
    }
}
