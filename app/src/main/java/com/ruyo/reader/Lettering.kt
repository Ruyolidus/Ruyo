package com.ruyo.reader

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.util.Locale

enum class LetteringFont(val family: String, val label: String) {
    SANS("sans-serif", "Sans serif"),
    SERIF("serif", "Serif"),
    CONDENSED("sans-serif-condensed", "Condensed"),
    MONO("monospace", "Monospace");

    fun typeface(bold: Boolean, italic: Boolean): Typeface = Typeface.create(family,
        (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0))

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.family == id } ?: SANS
    }
}

object TextLanguages {
    val common = listOf("ja", "en", "fr", "es", "pt", "de", "ko", "zh-Hans", "zh-Hant", "ar", "hi", "bn", "ru", "th", "vi", "tr", "sw")
    fun normalize(value: String): String {
        require(value.length in 2..64) { "Enter a language code, such as ja, fr, ar, or zh-Hant." }
        val locale = Locale.Builder().setLanguageTag(value.trim()).build()
        require(locale.language.isNotBlank() && locale.language != "und") { "Choose a specific language." }
        return locale.toLanguageTag()
    }
    fun locale(tag: String): Locale = runCatching { Locale.forLanguageTag(normalize(tag)) }.getOrDefault(Locale.JAPANESE)
    fun label(tag: String): String = locale(tag).getDisplayName(Locale.ENGLISH)
}

/** Estimate visible letter height, not the identity of an unknown source font. */
object SourceLettering {
    fun estimateHeight(region: BubbleRegion): Float? {
        val ink = region.eraseMask
        val visited = BooleanArray(ink.size)
        val queue = IntArray(ink.size)
        val heights = mutableListOf<Int>()
        for (seed in ink.indices) {
            if (!ink[seed] || visited[seed]) continue
            var head = 0; var tail = 1
            queue[0] = seed; visited[seed] = true
            var top = seed / region.width; var bottom = top
            var left = seed % region.width; var right = left
            while (head < tail) {
                val index = queue[head++]
                val x = index % region.width; val y = index / region.width
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx !in 0 until region.width || yy !in 0 until region.height) continue
                    val next = yy * region.width + xx
                    if (ink[next] && !visited[next]) { visited[next] = true; queue[tail++] = next }
                }
            }
            // Exclude the cleanup mask's one-pixel fringe and tiny punctuation.
            val height = bottom - top - 1
            if (tail >= 8 && right - left >= 2 && height >= 4 && height <= region.height * 0.85f) heights += height
        }
        if (heights.isEmpty()) return null
        heights.sort()
        val typical = heights[(heights.lastIndex * 0.7f).toInt()]
        val similar = heights.filter { it in (typical * 0.7f).toInt()..(typical * 1.3f).toInt() }
        if (similar.size * 2 < heights.size) return null
        return similar.sorted()[similar.size / 2].toFloat()
    }

    fun preferredSize(height: Float, language: String, font: Typeface): Float {
        val sample = when (TextLanguages.locale(language).language) {
            "ja", "zh" -> "漢"; "ko" -> "한"; "ar", "fa", "ur" -> "أم"
            "hi", "mr", "ne" -> "आ"; "bn" -> "ম"; "th" -> "ก"; else -> "Hg"
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = 64f }
        val bounds = Rect()
        paint.getTextBounds(sample, 0, sample.length, bounds)
        return (height * 64f / bounds.height().coerceAtLeast(1)).coerceIn(6f, 256f)
    }
}
