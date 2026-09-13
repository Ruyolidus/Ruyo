package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/** An image-space mask. A pixel is permitted only when its entire cell is inside. */
class PixelMask(val width: Int, val height: Int, private val pixels: BooleanArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height)
    }

    operator fun get(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height && pixels[y * width + x]

    fun copyPixels(): BooleanArray = pixels.copyOf()

    fun centerY(): Float {
        var sum = 0L
        var count = 0
        for (i in pixels.indices) if (pixels[i]) { sum += i / width; count++ }
        return if (count == 0) height / 2f else sum.toFloat() / count + 0.5f
    }

    /** Intersection of every row in a line band, then its longest contiguous span. */
    fun span(top: Int, bottomExclusive: Int): IntRange? {
        if (top < 0 || bottomExclusive > height || top >= bottomExclusive) return null
        var runStart = 0
        var bestStart = 0
        var bestLength = 0
        for (x in 0..width) {
            val allowed = x < width && (top until bottomExclusive).all { y -> this[x, y] }
            if (!allowed) {
                if (x - runStart > bestLength) {
                    bestStart = runStart
                    bestLength = x - runStart
                }
                runStart = x + 1
            }
        }
        return if (bestLength > 0) bestStart until (bestStart + bestLength) else null
    }

    /** Conservative square erosion using a two-pass Chebyshev distance transform. */
    fun inset(padding: Int): PixelMask {
        require(padding >= 0)
        val infinity = width + height + 1
        val distance = IntArray(pixels.size) { if (pixels[it]) infinity else 0 }
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            var d = distance[i]
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) d = minOf(d, 1)
            if (x > 0) d = minOf(d, distance[i - 1] + 1)
            if (y > 0) {
                d = minOf(d, distance[i - width] + 1)
                if (x > 0) d = minOf(d, distance[i - width - 1] + 1)
                if (x < width - 1) d = minOf(d, distance[i - width + 1] + 1)
            }
            distance[i] = d
        }
        for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) {
            val i = y * width + x
            var d = distance[i]
            if (x < width - 1) d = minOf(d, distance[i + 1] + 1)
            if (y < height - 1) {
                d = minOf(d, distance[i + width] + 1)
                if (x > 0) d = minOf(d, distance[i + width - 1] + 1)
                if (x < width - 1) d = minOf(d, distance[i + width + 1] + 1)
            }
            distance[i] = d
        }
        return PixelMask(width, height, BooleanArray(pixels.size) { pixels[it] && distance[it] > padding })
    }

    fun outsideInkCount(bitmap: Bitmap): Int {
        require(bitmap.width == width && bitmap.height == height)
        val colors = IntArray(width * height)
        bitmap.getPixels(colors, 0, width, 0, 0, width, height)
        return colors.indices.count { Color.alpha(colors[it]) != 0 && !pixels[it] }
    }

    companion object {
        fun fromPath(width: Int, height: Int, path: Path): PixelMask {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            val colors = IntArray(width * height)
            bitmap.getPixels(colors, 0, width, 0, 0, width, height)
            bitmap.recycle()
            return PixelMask(width, height, BooleanArray(colors.size) { Color.alpha(colors[it]) == 255 })
        }
    }
}
