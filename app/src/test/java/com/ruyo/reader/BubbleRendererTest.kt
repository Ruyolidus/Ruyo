package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import com.ruyo.sample.SampleChapter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleRendererTest {
    @Test
    fun completeJapaneseFitsWithoutClipping() {
        for (page in pages) {
            val fit = page.replacement
            assertEquals(page.line.japanese, fit.lines.joinToString("") { fit.text.substring(it.start, it.end) })
            assertEquals(0, page.safeRegion.outsideInkCount(fit.ink))
            assertTrue(fit.fontSize in 24f..40f)
        }
    }

    @Test
    fun shrinksTheWholeSentenceToFitANarrowBubble() {
        val mask = oval(360, 170).inset(12)
        val result = BubbleFitter().fit("一緒に行こうか？", mask, preferredSize = 90f, minimumSize = 24f)
        assertTrue(result is FitResult.Accepted)
        result as FitResult.Accepted
        assertTrue(result.fontSize < 90f)
        assertEquals(0, mask.outsideInkCount(result.ink))
        assertEquals(result.text, result.lines.joinToString("") { result.text.substring(it.start, it.end) })
    }

    @Test
    fun rejectsTextThatCannotFitAtTheMinimumSize() {
        val result = BubbleFitter().fit("一緒に行こう。".repeat(50), oval(150, 110).inset(12), 48f, 40f)
        assertTrue(result is FitResult.Rejected)
    }

    @Test
    fun rejectsEmptyOrUnboundedInput() {
        val fitter = BubbleFitter()
        val mask = oval(300, 200)
        assertTrue(fitter.fit("   ", mask) is FitResult.Rejected)
        assertTrue(fitter.fit("あ".repeat(513), mask) is FitResult.Rejected)
    }

    @Test
    fun avoidsHolesInsideAConcaveSafeRegion() {
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRoundRect(RectF(0f, 0f, 320f, 230f), 35f, 35f, Path.Direction.CW)
            addRect(135f, 55f, 185f, 175f, Path.Direction.CW)
        }
        val mask = PixelMask.fromPath(320, 230, path).inset(8)
        val result = BubbleFitter().fit("待って！", mask, 42f, 24f)
        assertTrue(result is FitResult.Accepted)
        assertEquals(0, mask.outsideInkCount((result as FitResult.Accepted).ink))
        assertFalse(mask[160, 100])
    }

    @Test
    fun repairPreservesEveryPixelOutsideItsLetteringMask() {
        var changed = 0
        for (page in pages) {
            val original = pixels(page.sourceCrop)
            val repaired = pixels(page.repairedCrop)
            for (i in original.indices) {
                if (!page.repairMask[i]) assertEquals("Unmasked pixel changed at $i", original[i], repaired[i])
                if (original[i] != repaired[i]) changed++
            }
        }
        assertTrue("The source lettering must actually have been removed", changed > 100)
    }

    @Test
    fun finalCompositeOnlyChangesRepairAndReplacementPixels() {
        for (page in pages) {
            val original = pixels(page.original)
            val translated = pixels(page.translated)
            val ink = pixels(page.replacement.ink)
            val cropWidth = page.safeRegion.width
            val cropHeight = page.safeRegion.height
            val left = page.bubbleBounds.left.toInt()
            val top = page.bubbleBounds.top.toInt()
            for (y in 0 until page.original.height) for (x in 0 until page.original.width) {
                val cx = x - left
                val cy = y - top
                val mayChange = if (cx in 0 until cropWidth && cy in 0 until cropHeight) {
                    val i = cy * cropWidth + cx
                    page.repairMask[i] || Color.alpha(ink[i]) != 0
                } else false
                val index = y * page.original.width + x
                if (!mayChange) assertEquals("Artwork changed at $x,$y", original[index], translated[index])
            }
        }
    }

    @Test
    fun writesRealRendererPreviewsForVisualReview() {
        val output = File(System.getProperty("ruyo.previewDir", "build/test-previews")).apply { mkdirs() }
        for ((index, page) in pages.withIndex()) {
            for ((label, bitmap) in listOf("original" to page.original, "japanese" to page.translated)) {
                File(output, "panel-${index + 1}-$label.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        }
    }

    private fun oval(width: Int, height: Int): PixelMask = PixelMask.fromPath(width, height, Path().apply {
        addOval(RectF(0f, 0f, width.toFloat(), height.toFloat()), Path.Direction.CW)
    })

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    companion object {
        private val pages by lazy { SampleChapter.build() }
    }
}

