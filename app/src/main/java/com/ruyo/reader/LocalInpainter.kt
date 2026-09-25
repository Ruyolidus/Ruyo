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
                // Extrapolating gradients from already reconstructed pixels
                // amplifies tiny errors into dark streaks shaped like the old text.
                // A convex fill cannot invent colors outside its boundary samples.
                red += Color.red(c) * factor; green += Color.green(c) * factor; blue += Color.blue(c) * factor; weight += factor
            }
            require(weight > 0)
            output[i] = Color.rgb((red / weight).roundToInt(), (green / weight).roundToInt(), (blue / weight).roundToInt())
        }
        // Relax the propagation seams while holding every source pixel outside
        // the mask fixed. This is smooth local repair, not recovery of hidden art.
        repeat(24) {
            for (parity in 0..1) for (n in 0 until tail) {
                val i=queue[n];val x=i%width;val y=i/width
                if ((x+y)%2!=parity) continue
                var r=0;var g=0;var b=0;var total=0
                fun add(j:Int) { val c=output[j];r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c);total++ }
                if(x>0) add(i-1);if(x+1<width) add(i+1);if(y>0) add(i-width);if(y+1<height) add(i+width)
                if(total>0) output[i]=Color.rgb((r+total/2)/total,(g+total/2)/total,(b+total/2)/total)
            }
        }
        return output
    }
}
