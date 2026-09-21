package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.ruyo.ai.OcrLine
import com.ruyo.data.LocalBookStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextRegionRepairTest {
    private fun fixture(top: Int, bottom: Int, ink: Int): Pair<Bitmap, OcrLine> {
        val source = Bitmap.createBitmap(360, 260, Bitmap.Config.ARGB_8888)
        val surface = BackgroundSurface(0, 0, 360, 260, listOf(top, top, bottom, bottom))
        for (y in 0 until 260) for (x in 0 until 360) source.setPixel(x, y, surface.colorAt(x, y))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 28f }
        val bounds = Rect(); paint.getTextBounds("Ready now", 0, 9, bounds)
        Canvas(source).drawText("Ready now", 70f, 130f, paint)
        return source to OcrLine("Ready now", 70 + bounds.left, 130 + bounds.top, 70 + bounds.right, 130 + bounds.bottom)
    }

    @Test fun coloredBarsAndGentleGradientsRepairOnlyInkAndPersistExactly() {
        val cases = listOf(
            Triple(Color.WHITE, Color.rgb(237, 242, 248), Color.BLACK),
            Triple(Color.rgb(40, 165, 215), Color.rgb(70, 195, 245), Color.WHITE),
            Triple(Color.rgb(28, 35, 58), Color.rgb(28, 35, 58), Color.WHITE),
            Triple(Color.rgb(255, 235, 172), Color.rgb(255, 235, 172), Color.BLACK))
        for ((index, colors) in cases.withIndex()) {
            val (source, line) = fixture(colors.first, colors.second, colors.third)
            val original = source.copy(Bitmap.Config.ARGB_8888, false)
            val region = requireNotNull(TextRegionRepair.select(source, listOf(line))) { "Case $index was rejected" }
            assertEquals(colors.third, region.textColor)
            val edit = BubbleEdit(region = region, japanese = "準備できた", margin = 3, matchSourceSize = true, sourceLetterHeight = (line.bottom - line.top).toFloat())
            val preview = BubbleEditRenderer.preview(source, edit).getOrThrow()
            assertEquals(0, region.interior.inset(3).outsideInkCount(preview.fit.ink))
            var erased = 0
            for (y in 0 until region.height) for (x in 0 until region.width) {
                val i = y * region.width + x
                if (Color.alpha(preview.fit.ink.getPixel(x, y)) == 0) {
                    if (region.eraseMask[i]) {
                        assertEquals(region.backgroundSurface!!.colorAt(region.left + x, region.top + y), preview.crop.getPixel(x, y)); erased++
                    } else assertEquals(source.getPixel(region.left + x, region.top + y), preview.crop.getPixel(x, y))
                }
            }
            assertTrue(erased > 30); assertTrue(source.sameAs(original))
            val context = RuntimeEnvironment.getApplication()
            val folder = File(context.cacheDir, UUID.randomUUID().toString())
            try {
                val store = LocalBookStore(context, folder, File(folder, "stage")); val book = store.addBitmap("Color fixture", source)
                store.saveEdit(book.id, source, edit)
                val opened = store.open(book)
                assertEquals(region.backgroundSurface, opened.edits.single().region.backgroundSurface)
                assertEquals(region.textColor, opened.edits.single().region.textColor)
                assertTrue(BubbleEditRenderer.composite(source, listOf(edit)).sameAs(opened.displayed))
                if (index == 1) {
                    val output = File(System.getProperty("ruyo.previewDir"), "gradient-caption-swap.png")
                    output.parentFile!!.mkdirs(); output.outputStream().use { opened.displayed.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally { folder.deleteRecursively() }
            preview.crop.recycle(); preview.fit.ink.recycle(); source.recycle(); original.recycle()
        }
    }

    @Test fun artworkAndOtherTextAreNotSilentlyErased() {
        val (source, line) = fixture(Color.rgb(60, 185, 230), Color.rgb(60, 185, 230), Color.WHITE)
        val neighbor = line.copy(text = "Separate", top = line.bottom + 2, bottom = line.bottom + 20)
        assertNull(TextRegionRepair.select(source, listOf(line), listOf(line, neighbor)))
        val paint = Paint().apply { color = Color.RED; strokeWidth = 5f }
        Canvas(source).drawLine(130f, 80f, 150f, 160f, paint)
        assertNull(TextRegionRepair.select(source, listOf(line)))
        source.recycle()
        val plain = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertNull(TextRegionRepair.select(plain, listOf(OcrLine("False OCR", 50, 70, 150, 90))))
        plain.recycle()
    }

    @Test fun automaticDetectionUsesFallbackForAnOpenLightCaption() = runBlocking {
        val (source, line) = fixture(Color.WHITE, Color.rgb(239, 242, 245), Color.BLACK)
        val groups = AutoBubbleDetector.detect(source, listOf(line))
        val bubble = groups.flatMap { it.bubbles }.single()
        assertEquals("Ready now", bubble.source)
        assertNotNull(bubble.region.backgroundSurface)
        assertTrue(bubble.region.contains(line.x, line.y))
        source.recycle()
    }
}
