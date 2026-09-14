package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.text.TextPaint
import java.util.Locale
import kotlin.math.ceil

data class TextLine(val start: Int, val end: Int, val x: Float, val baseline: Float)

sealed interface FitResult {
    data class Accepted(
        val text: String,
        val fontSize: Float,
        val lines: List<TextLine>,
        val ink: Bitmap,
    ) : FitResult

    data class Rejected(val reason: String) : FitResult
}

/** Horizontal-only typesetting. A result cannot be accepted by clipping away overflow. */
class BubbleFitter {
    fun fit(
        input: String,
        safeRegion: PixelMask,
        preferredSize: Float = 64f,
        minimumSize: Float = 34f,
        textColor: Int = Color.rgb(39, 42, 53),
    ): FitResult {
        require(preferredSize.isFinite() && minimumSize.isFinite())
        require(minimumSize > 0f && preferredSize >= minimumSize)
        val text = input.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        if (text.isBlank() || text.length > 512) return FitResult.Rejected("Text is empty or exceeds this preview's layout limit.")

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = textColor
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textLocale = Locale.JAPAN
        }
        val graphemes = boundaries(text, BreakIterator.getCharacterInstance(Locale.JAPANESE))
        for (i in 0 until graphemes.lastIndex) {
            val glyph = text.substring(graphemes[i], graphemes[i + 1])
            if (!glyph.isBlank() && !paint.hasGlyph(glyph)) return FitResult.Rejected("A character is unavailable in the current font.")
        }
        val breaks = boundaries(text, BreakIterator.getLineInstance(Locale.JAPANESE))
            .filter { it in graphemes && legalBreak(text, it) }
        val centerY = safeRegion.centerY()
        val steps = ceil((preferredSize - minimumSize) / 2f).toInt().coerceAtMost(64)
        // Reserve shaping work for EVERY size, including the minimum. A difficult
        // large-font layout must not exhaust the search before shrink-to-fit runs.
        for (step in 0..steps) {
            val budget = LayoutBudget(8_000 / (steps + 1))
            val size = if (steps == 0) preferredSize else maxOf(minimumSize, preferredSize - (preferredSize - minimumSize) * step / steps)
            paint.textSize = size
            val metrics = paint.fontMetrics
            val lineHeight = ceil(metrics.bottom - metrics.top + size * 0.08f + 4f).toInt()
            val maxLines = minOf(32, safeRegion.height / lineHeight, graphemes.lastIndex)
            val firstCount = maxOf(1, (paint.measureText(text) / safeRegion.width).toInt()).coerceAtMost(maxOf(1, maxLines))
            for (count in firstCount..maxLines) {
                val centeredTop = (centerY - count * lineHeight / 2f).toInt()
                for (shift in intArrayOf(0, lineHeight / 5, -lineHeight / 5)) {
                    val top = centeredTop + shift
                    val spans = (0 until count).map { line ->
                        safeRegion.span(top + line * lineHeight, top + (line + 1) * lineHeight)
                    }
                    if (spans.any { it == null || it.count() < size }) continue
                    val validSpans = spans.filterNotNull()
                    val cuts = wrap(text, breaks, validSpans, paint, budget)
                    if (budget.exhausted) break
                    if (cuts == null) continue
                    val lines = cuts.mapIndexed { i, (start, end) ->
                        val span = validSpans[i]
                        TextLine(
                            start, end,
                            span.first + (span.count() - paint.measureText(text, start, end)) / 2f,
                            top + i * lineHeight - metrics.top + 2f,
                        )
                    }
                    if (lines.joinToString("") { text.substring(it.start, it.end) } != text) continue
                    val ink = Bitmap.createBitmap(safeRegion.width, safeRegion.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(ink)
                    lines.forEach { canvas.drawText(text, it.start, it.end, it.x, it.baseline, paint) }
                    // No clip has been used: this inspects actual antialiased glyph pixels.
                    if (safeRegion.outsideInkCount(ink) == 0 && hasInk(ink)) {
                        return FitResult.Accepted(text, size, lines, ink)
                    }
                    ink.recycle()
                }
                if (budget.exhausted) break
            }
        }
        return FitResult.Rejected("The whole text still does not fit after shrinking. Reduce the padding or shorten the text.")
    }

    private fun wrap(
        text: String,
        breaks: List<Int>,
        spans: List<IntRange>,
        paint: TextPaint,
        budget: LayoutBudget,
    ): List<Pair<Int, Int>>? {
        val failed = mutableSetOf<Pair<Int, Int>>()
        fun visit(line: Int, start: Int): List<Pair<Int, Int>>? {
            if (line == spans.size) return if (start == text.length) emptyList() else null
            if (start == text.length || (line to start) in failed) return null
            val remaining = spans.size - line - 1
            val fittingEnd = budget.fittingEnd(paint, text, start, spans[line].count() - 4f)
            for (end in breaks.asReversed()) {
                if (budget.exhausted) return null
                if (end <= start || end > fittingEnd || (remaining > 0 && end == text.length)) continue
                if (remaining == 0 && end != text.length) continue
                val tail = visit(line + 1, end)
                if (tail != null) return listOf(start to end) + tail
            }
            failed += line to start
            return null
        }
        return visit(0, 0)
    }

    /** Bound native shaping calls even for adversarially long or awkward text. */
    private class LayoutBudget(private var remaining: Int) {
        private val fitted = mutableMapOf<Pair<Int, Float>, Int>()
        var exhausted = false
            private set
        fun fittingEnd(paint: TextPaint, text: String, start: Int, width: Float): Int {
            val key = start to width
            fitted[key]?.let { return it }
            if (remaining-- <= 0) { exhausted = true; return start }
            val end = start + paint.breakText(text, start, text.length, true, width, null)
            fitted[key] = end
            return end
        }
    }

    private fun boundaries(text: String, iterator: BreakIterator): List<Int> {
        iterator.setText(text)
        val values = mutableListOf<Int>()
        var index = iterator.first()
        while (index != BreakIterator.DONE) {
            values += index
            index = iterator.next()
        }
        return values
    }

    private fun legalBreak(text: String, index: Int): Boolean {
        if (index == 0 || index == text.length) return true
        return text[index - 1] !in "「『（【〈《〔［｛" &&
            text[index] !in "、。，．！？：；）」』】〉》〕］｝ぁぃぅぇぉゃゅょっァィゥェォャュョッー々"
    }

    private fun hasInk(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { Color.alpha(it) != 0 }) return true
        }
        return false
    }
}
