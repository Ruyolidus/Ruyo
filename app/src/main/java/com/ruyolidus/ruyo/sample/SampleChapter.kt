package com.ruyolidus.ruyo.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.ruyolidus.ruyo.reader.BubbleFitter
import com.ruyolidus.ruyo.reader.FitResult
import com.ruyolidus.ruyo.reader.FlatBubbleRepair
import com.ruyolidus.ruyo.reader.PixelMask

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
        val replacement = fitter.fit(line.japanese, safe, preferredSize = 70f, minimumSize = 34f)
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun fill(color: Int) { paint.color = color; paint.style = Paint.Style.FILL }
        val sky = when (index) {
            0 -> Color.rgb(226, 235, 241)
            1 -> Color.rgb(239, 227, 211)
            else -> Color.rgb(209, 220, 227)
        }
        canvas.drawColor(sky)
        fill(Color.rgb(250, 226, 160))
        canvas.drawCircle(730f, 420f, 80f, paint)
        fill(Color.rgb(193, 208, 196))
        canvas.drawPath(Path().apply {
            moveTo(0f, 730f); lineTo(0f, 490f); quadTo(260f, 365f, 490f, 615f)
            quadTo(690f, 410f, 900f, 565f); lineTo(900f, 1000f); lineTo(0f, 1000f); close()
        }, paint)
        fill(Color.rgb(144, 174, 158))
        canvas.drawPath(Path().apply {
            moveTo(0f, 710f); quadTo(480f, 510f, 900f, 755f)
            lineTo(900f, 1000f); lineTo(0f, 1000f); close()
        }, paint)
        fill(Color.rgb(237, 224, 201))
        canvas.drawPath(Path().apply {
            moveTo(500f, 630f); lineTo(610f, 630f); lineTo(800f, 1000f); lineTo(225f, 1000f); close()
        }, paint)
        for (i in 0..4) {
            val x = 40f + i * 160f
            fill(Color.rgb(104, 132, 113))
            canvas.drawRoundRect(RectF(x, 680f, x + 10f, 860f), 5f, 5f, paint)
        }
        paint.color = Color.rgb(104, 132, 113)
        paint.strokeWidth = 7f
        canvas.drawLine(0f, 745f, 650f, 745f, paint)
        drawPerson(canvas, 340f, 617f, Color.rgb(104, 87, 153), index == 0)
        drawPerson(canvas, 588f, 650f, Color.rgb(61, 112, 104), false)
        fill(Color.rgb(82, 105, 86))
        for (i in 0..10) {
            val x = (i * 89 + 25).toFloat()
            val y = 940f + (i % 3) * 14f
            canvas.drawOval(RectF(x, y, x + 26f, y + 10f), paint)
        }
        if (index == 2) {
            fill(Color.rgb(182, 192, 208))
            canvas.drawOval(RectF(35f, 400f, 210f, 455f), paint)
            canvas.drawOval(RectF(135f, 378f, 330f, 450f), paint)
        }
    }

    private fun drawPerson(canvas: Canvas, x: Float, y: Float, shirt: Int, waving: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        paint.color = Color.rgb(65, 67, 80)
        paint.strokeWidth = 24f
        canvas.drawLine(x - 16f, y + 170f, x - 32f, y + 280f, paint)
        canvas.drawLine(x + 22f, y + 170f, x + 49f, y + 280f, paint)
        paint.color = shirt
        canvas.drawRoundRect(RectF(x - 57f, y + 45f, x + 62f, y + 190f), 38f, 38f, paint)
        paint.color = Color.rgb(226, 186, 152)
        canvas.drawCircle(x, y + 8f, 45f, paint)
        paint.color = Color.rgb(63, 61, 68)
        canvas.drawArc(RectF(x - 46f, y - 39f, x + 46f, y + 47f), 180f, 180f, true, paint)
        paint.strokeWidth = 7f
        canvas.drawLine(x - 13f, y + 13f, x - 13f, y + 18f, paint)
        canvas.drawLine(x + 15f, y + 13f, x + 15f, y + 18f, paint)
        paint.style = Paint.Style.STROKE
        canvas.drawArc(RectF(x - 10f, y + 20f, x + 13f, y + 34f), 0f, 180f, false, paint)
        paint.color = shirt
        paint.strokeWidth = 24f
        canvas.drawLine(x - 42f, y + 80f, x - 83f, if (waving) y - 12f else y + 155f, paint)
        canvas.drawLine(x + 44f, y + 78f, x + 91f, y + 158f, paint)
    }
}

