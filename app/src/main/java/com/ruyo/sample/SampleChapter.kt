package com.ruyo.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.ruyo.reader.BubbleFitter
import com.ruyo.reader.FitResult
import com.ruyo.reader.FlatBubbleRepair
import com.ruyo.reader.PixelMask

data class SampleLine(
    val id: String,
    val original: String,
    val japanese: String,
    val reading: String,
    val meaning: String,
    val grammar: String,
    val vocabulary: List<Pair<String, String>>,
    val example: String,
)

data class SamplePage(
    val line: SampleLine,
    val original: Bitmap,
    val translated: Bitmap,
    val bubbleBounds: RectF,
    val interior: PixelMask,
    val safeRegion: PixelMask,
    val replacement: FitResult.Accepted,
    val sourceCrop: Bitmap,
    val repairedCrop: Bitmap,
    val repairMask: BooleanArray,
)

/** Original programmatic artwork and prewritten dialogue, created specifically for Ruyo. */
object SampleChapter {
    val lines = listOf(
        SampleLine(
            "wait", "Wait for me!", "待って！", "まって！", "Wait for me!",
            "待って is the て-form of 待つ (to wait). On its own, it can be a casual request. “For me” comes from the situation; it is not stated explicitly in this Japanese line.",
            listOf("待つ・まつ" to "to wait", "待って・まって" to "wait; the て-form of 待つ"),
            "ちょっと待って。\nWait a moment.",
        ),
        SampleLine(
            "together", "Shall we go together?", "一緒に行こうか？", "いっしょに いこうか？", "Shall we go together?",
            "一緒に means “together.” 行こう is the volitional form of 行く (to go). Adding か makes the invitation a question. This is casual speech between people who know each other.",
            listOf("一緒に・いっしょに" to "together", "行く・いく" to "to go", "行こう・いこう" to "let’s go; volitional form"),
            "一緒に勉強しようか？\nShall we study together?",
        ),
        SampleLine(
            "rain", "If we leave now, we can arrive before the rain.", "今出発すれば、雨が降る前に着けるよ。", "いま しゅっぱつすれば、あめが ふる まえに つけるよ。", "If we leave now, we can arrive before it rains.",
            "出発すれば expresses the condition “if we leave.” 雨が降る前に means “before it rains.” 着ける is the potential form of 着く, expressing that we can arrive. よ adds a casual, informative tone.",
            listOf("今・いま" to "now", "出発する・しゅっぱつする" to "to depart", "雨・あめ" to "rain", "着く・つく" to "to arrive"),
            "暗くなる前に帰ろう。\nLet’s go home before it gets dark.",
        ),
    )

    fun build(): List<SamplePage> = lines.mapIndexed { index, line -> createPage(index, line) }

    private fun createPage(index: Int, line: SampleLine): SamplePage {
        val width = 900
        val height = 1000
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        drawScene(canvas, index)

        val bounds = when (index) {
            0 -> RectF(140f, 65f, 760f, 325f)
            1 -> RectF(110f, 62f, 790f, 345f)
            else -> RectF(70f, 48f, 830f, 360f)
        }
        val w = bounds.width().toInt()
        val h = bounds.height().toInt()
        val background = if (index == 1) Color.rgb(240, 248, 229) else Color.rgb(255, 254, 249)
        val localPath = Path().apply {
            if (index == 2) addRoundRect(RectF(4f, 4f, w - 4f, h - 4f), 80f, 80f, Path.Direction.CW)
            else addOval(RectF(4f, 4f, w - 4f, h - 4f), Path.Direction.CW)
        }
        val tail = Path().apply {
            moveTo(bounds.centerX() - 80f, bounds.bottom - 28f)
            lineTo(bounds.centerX() - 115f, bounds.bottom + 95f)
            lineTo(bounds.centerX() + 18f, bounds.bottom - 30f)
            close()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(53, 59, 63)
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(tail, fill)
        canvas.drawPath(tail, outline)
        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.drawPath(localPath, fill)
        canvas.drawPath(localPath, outline)
        canvas.restore()
        // Cover only the base of the tail, inside the bubble, for a continuous balloon.
        canvas.drawLine(bounds.centerX() - 72f, bounds.bottom - 15f, bounds.centerX() + 12f, bounds.bottom - 15f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background; strokeWidth = 17f })

        val interior = PixelMask.fromPath(w, h, localPath)
        val safe = interior.inset(22)
        val fitter = BubbleFitter()
        val originalInk = fitter.fit(line.original, safe, preferredSize = 64f, minimumSize = 32f)
        check(originalInk is FitResult.Accepted) { "Source sample did not fit: $originalInk" }
        canvas.drawBitmap(originalInk.ink, bounds.left, bounds.top, null)

        val sourceCrop = Bitmap.createBitmap(page, bounds.left.toInt(), bounds.top.toInt(), w, h)
        val repaired = FlatBubbleRepair.repair(sourceCrop, originalInk.ink, interior, background)
        originalInk.ink.recycle()
        val replacement = fitter.fit(line.japanese, safe, preferredSize = 40f, minimumSize = 24f)
        check(replacement is FitResult.Accepted) { "Japanese sample did not fit: $replacement" }
        val translated = page.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(translated).apply {
            drawBitmap(repaired.bitmap, bounds.left, bounds.top, null)
            save()
            translate(bounds.left, bounds.top)
            clipPath(localPath)
            drawBitmap(replacement.ink, 0f, 0f, null)
            restore()
        }
        return SamplePage(line, page, translated, bounds, interior, safe, replacement,
            sourceCrop, repaired.bitmap, repaired.changedMask)
    }

    private fun drawScene(canvas: Canvas, index: Int) {
        // Original ink-style streetscape: no external illustrations or image assets.
        val ink = Color.rgb(40, 44, 49)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun fill(color: Int) { paint.color = color; paint.style = Paint.Style.FILL }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 2f) {
            paint.color = ink; paint.style = Paint.Style.STROKE; paint.strokeWidth = width
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        canvas.drawColor(Color.rgb(230 - index * 5, 233 - index * 5, 233 - index * 5))
        fill(Color.rgb(210, 213, 212))
        canvas.drawRect(0f, 330f, 250f, 1000f, paint)
        canvas.drawRect(670f, 230f, 900f, 1000f, paint)
        fill(Color.rgb(246, 245, 239))
        canvas.drawRect(12f, 370f, 200f, 980f, paint)
        canvas.drawRect(705f, 270f, 890f, 970f, paint)
        for (y in 410..820 step 94) for (x in listOf(35, 122, 730, 820)) {
            fill(Color.rgb(167, 176, 177))
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 49f, y + 63f, paint)
            line(x.toFloat(), y.toFloat(), x + 49f, y.toFloat(), 3f)
            line(x + 24f, y.toFloat(), x + 24f, y + 63f)
        }
        line(205f, 330f, 205f, 1000f, 5f)
        line(695f, 230f, 695f, 1000f, 5f)
        for (y in 360..950 step 18) line(220f, y.toFloat(), 245f, y + 10f, 1f)
        for (y in 340..920 step 34) line(710f, y.toFloat(), 898f, y.toFloat(), 1f)
        fill(Color.rgb(191, 199, 198))
        canvas.drawPath(Path().apply { moveTo(330f, 540f); lineTo(560f, 540f); lineTo(930f, 1000f); lineTo(-30f, 1000f); close() }, paint)
        fill(Color.rgb(235, 235, 229))
        canvas.drawPath(Path().apply { moveTo(425f, 560f); lineTo(470f, 560f); lineTo(640f, 1000f); lineTo(238f, 1000f); close() }, paint)
        line(425f, 560f, 238f, 1000f)
        line(470f, 560f, 640f, 1000f)
        for (y in listOf(650f, 735f, 850f, 965f)) line(262f, y, 645f, y, 1f)
        line(255f, 410f, 668f, 430f, 2f)
        line(254f, 423f, 666f, 438f, 1f)
        line(284f, 510f, 284f, 835f, 5f)
        fill(ink); canvas.drawRect(270f, 506f, 302f, 552f, paint)
        fill(Color.rgb(240, 213, 126)); canvas.drawRect(276f, 511f, 296f, 545f, paint)
        // A striped shop awning and quiet foreground details.
        fill(Color.rgb(229, 225, 213)); canvas.drawRect(0f, 650f, 205f, 700f, paint)
        for (x in 0..190 step 32) { fill(Color.rgb(108, 122, 119)); canvas.drawRect(x.toFloat(), 650f, x + 16f, 700f, paint) }
        line(0f, 700f, 205f, 700f, 4f)
        fill(Color.rgb(103, 116, 114)); canvas.drawRect(25f, 812f, 112f, 935f, paint)
        fill(Color.rgb(230, 230, 219)); canvas.drawRect(36f, 827f, 102f, 879f, paint)
        for (x in 46..92 step 15) line(x.toFloat(), 888f, x.toFloat(), 908f, 4f)
        drawWalker(canvas, 385f, 724f, Color.rgb(66, 77, 80), index == 0)
        drawWalker(canvas, 536f, 746f, Color.rgb(113, 118, 115), false)
        if (index == 2) {
            paint.color = Color.rgb(146, 157, 162); paint.strokeWidth = 1.5f
            for (i in 0..36) { val x = (i * 79 % 900).toFloat(); val y = 400f + (i * 67 % 530); canvas.drawLine(x, y, x - 16f, y + 44f, paint) }
        }
    }

    private fun drawWalker(canvas: Canvas, x: Float, y: Float, coat: Int, waving: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = coat }
        canvas.drawPath(Path().apply { moveTo(x - 21f, y + 30f); lineTo(x + 20f, y + 30f); lineTo(x + 36f, y + 137f); lineTo(x - 30f, y + 137f); close() }, p)
        p.color = Color.rgb(56, 59, 63); p.strokeWidth = 15f; p.strokeCap = Paint.Cap.SQUARE
        canvas.drawLine(x - 10f, y + 134f, x - 17f, y + 207f, p)
        canvas.drawLine(x + 15f, y + 134f, x + 28f, y + 205f, p)
        p.color = Color.rgb(211, 196, 177); canvas.drawOval(RectF(x - 16f, y - 13f, x + 17f, y + 30f), p)
        p.color = Color.rgb(50, 53, 57); canvas.drawArc(RectF(x - 18f, y - 18f, x + 19f, y + 25f), 170f, 210f, true, p)
        p.color = coat; p.strokeWidth = 13f; p.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x - 17f, y + 43f, x - 39f, if (waving) y - 2f else y + 105f, p)
        canvas.drawLine(x + 18f, y + 43f, x + 47f, y + 98f, p)
        p.color = Color.rgb(188, 156, 97); p.strokeWidth = 4f
        canvas.drawLine(x + 43f, y + 98f, x + 58f, y + 184f, p)
    }
}
