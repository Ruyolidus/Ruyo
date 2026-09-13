package com.ruyolidus.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Color

data class RepairedBubble(val bitmap: Bitmap, val changedMask: BooleanArray)

/** The sample supplies known glyph masks and flat background colors. No OCR is implied. */
object FlatBubbleRepair {
    fun repair(source: Bitmap, sourceInk: Bitmap, interior: PixelMask, backgroundColor: Int): RepairedBubble {
        val width = source.width
        val height = source.height
        require(sourceInk.width == width && sourceInk.height == height)
        require(interior.width == width && interior.height == height)
        val original = IntArray(width * height)
        val lettering = IntArray(original.size)
        source.getPixels(original, 0, width, 0, 0, width, height)
        sourceInk.getPixels(lettering, 0, width, 0, 0, width, height)
        val mask = BooleanArray(original.size)
        for (y in 0 until height) for (x in 0 until width) {
            if (Color.alpha(lettering[y * width + x]) == 0) continue
            for (dy in -2..2) for (dx in -2..2) {
                val xx = x + dx
                val yy = y + dy
                if (interior[xx, yy]) mask[yy * width + xx] = true
            }
        }
        val repaired = original.copyOf()
        for (i in repaired.indices) if (mask[i]) repaired[i] = backgroundColor
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(repaired, 0, width, 0, 0, width, height)
        return RepairedBubble(output, mask)
    }
}
