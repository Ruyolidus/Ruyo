package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import com.ruyo.data.LocalBookStore
import com.ruyo.sample.SampleChapter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BubbleEditorTest {
    @Before fun clearFiles() { File(RuntimeEnvironment.getApplication().filesDir, "books").deleteRecursively() }

    @Test fun narrowOutlineGapsDoNotTurnInteriorTapsIntoPageEdgeSelections() {
        val source = android.graphics.Bitmap.createBitmap(240, 240, android.graphics.Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.WHITE)
        val canvas = android.graphics.Canvas(source)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f }
        canvas.drawCircle(120f, 120f, 90f, paint)
        paint.style = android.graphics.Paint.Style.FILL; paint.color = android.graphics.Color.WHITE
        canvas.drawRect(119f, 25f, 121f, 36f, paint)
        paint.color = android.graphics.Color.BLACK
        canvas.drawRect(100f, 110f, 140f, 118f, paint)
        val before = source.copy(source.config!!, false)
        val selected = BubbleSelector.select(source, 120, 150)
        assertTrue(selected is SelectionResult.Selected)
        val region = (selected as SelectionResult.Selected).region
        assertTrue(region.contains(120, 150)); assertFalse(region.contains(10, 10))
        assertTrue(region.width < 180 && region.height < 180)
        assertTrue(BubbleSelector.select(source, 10, 10) is SelectionResult.Rejected)
        assertTrue(source.sameAs(before)); source.recycle(); before.recycle()
    }

    @Test fun tapsOnPaleLetterEdgesAndWhiteCountersFindTheEnclosingBubble() {
        val source = android.graphics.Bitmap.createBitmap(240, 240, android.graphics.Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.DKGRAY)
        val canvas = android.graphics.Canvas(source)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
        canvas.drawCircle(120f, 120f, 100f, paint)
        paint.color = android.graphics.Color.BLACK
        canvas.drawCircle(120f, 120f, 21f, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(120f, 120f, 11f, paint)
        // A separate pale island imitates an antialiased stroke edge.
        paint.color = android.graphics.Color.rgb(195, 195, 195)
        canvas.drawRect(155f, 119f, 158f, 122f, paint)
        for ((x, y) in listOf(120 to 120, 156 to 120, 140 to 120)) {
            val selected = BubbleSelector.select(source, x, y)
            assertTrue(selected is SelectionResult.Selected)
            val region = (selected as SelectionResult.Selected).region
            assertTrue(region.width > 180 && region.height > 180)
            assertTrue(region.contains(x, y))
        }
        source.recycle()
    }

    @Test fun selectsEnclosedBubbleAndPreservesUnmaskedArtwork() {
        val page = SampleChapter.build().first()
        val selection = BubbleSelector.select(page.original, 450, 105)
        assertTrue(selection.toString(), selection is SelectionResult.Selected)
        val region = (selection as SelectionResult.Selected).region
        val edit = BubbleEdit(region = region, japanese = "待って！", margin = 15)
        val preview = BubbleEditRenderer.preview(page.original, edit).getOrThrow()
        assertEquals(0, region.interior.inset(15).outsideInkCount(preview.fit.ink))
        val composite = BubbleEditRenderer.composite(page.original, listOf(edit))
        var changed = 0
        for (y in 0 until composite.height) for (x in 0 until composite.width) {
            if (page.original.getPixel(x, y) != composite.getPixel(x, y)) {
                assertTrue("Artwork changed outside the bubble at $x,$y", region.contains(x, y))
                changed++
            }
        }
        assertTrue(changed > 100)
        val output = File(System.getProperty("ruyo.previewDir"), "selected-bubble-japanese.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { assertTrue(composite.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun rejectsAnUnenclosedPageBackground() {
        val plain = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        assertTrue(BubbleSelector.select(plain, 200, 200) is SelectionResult.Rejected)
    }

    @Test fun rejectsDarkLetteringAsTheBackgroundSeed() {
        val plain = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        assertTrue(BubbleSelector.select(plain, 200, 200) is SelectionResult.Rejected)
    }

    @Test fun smallBubbleAcceptsALetterTapWithoutSelectingItsNeighbour() {
        val source = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        Canvas(source).apply {
            paint.color = Color.BLACK; drawOval(30f, 30f, 62f, 58f, paint)
            paint.color = Color.WHITE; drawOval(32f, 32f, 60f, 56f, paint)
            paint.color = Color.BLACK; drawRect(44f, 40f, 48f, 47f, paint)
        }
        val selected = BubbleSelector.select(source, 46, 43)
        assertTrue(selected.toString(), selected is SelectionResult.Selected)
        val region = (selected as SelectionResult.Selected).region
        assertTrue(region.width < 40 && region.height < 32)
        val preview = BubbleEditRenderer.preview(source, BubbleEdit(region = region, japanese = "はい", margin = 2)).getOrThrow()
        assertEquals(0, region.interior.inset(2).outsideInkCount(preview.fit.ink))
        assertTrue("A nearby bubble must not capture a tap outside its interior", BubbleSelector.select(source, 64, 44) is SelectionResult.Rejected)
    }

    @Test fun anEmptyColoredPanelDoesNotBecomeAnEnclosedBubble() {
        val blue = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(60, 190, 240)) }
        val rejected = BubbleSelector.select(blue, 60, 50)
        assertTrue(rejected is SelectionResult.Rejected)
        assertTrue((rejected as SelectionResult.Rejected).reason.contains("text-area cleanup"))
    }

    @Test fun importAndEditsSurviveReopeningAndCanBeUndone() {
        val store = LocalBookStore(RuntimeEnvironment.getApplication())
        val source = SampleChapter.build().first().original
        val book = store.addBitmap("My chapter", source)
        val region = (BubbleSelector.select(source, 450, 105) as SelectionResult.Selected).region
        val edit = BubbleEdit(region = region, japanese = "待って！", margin = 15, fontScale = 0.85f)
        store.saveEdit(book.id, source, edit)
        val reopened = LocalBookStore(RuntimeEnvironment.getApplication()).open(book)
        assertEquals("待って！", reopened.edits.single().japanese)
        assertEquals(0.85f, reopened.edits.single().fontScale, 0.001f)
        assertTrue(source.sameAs(reopened.original))
        assertFalse(reopened.original.sameAs(reopened.displayed))
        assertTrue(region.eraseMask.contentEquals(reopened.edits.single().region.eraseMask))
        store.removeEdit(book.id, edit.id)
        val restored = store.open(book)
        assertTrue(restored.edits.isEmpty())
        assertTrue(source.sameAs(restored.displayed))
    }

    @Test fun denseJapaneseShrinksBelowTheOldFloorWithoutOverflowOrTextLoss() {
        val source = SampleChapter.build().first().original
        val region = (BubbleSelector.select(source, 450, 105) as SelectionResult.Selected).region
        val text = "明日は早く起きて、一緒に駅まで歩こう。".repeat(8)
        val edit = BubbleEdit(region = region, japanese = text, margin = 24)
        val preview = BubbleEditRenderer.preview(source, edit).getOrThrow()
        assertTrue("Dense dialogue must shrink below the former page-width/32 floor", preview.fit.fontSize < source.width / 32f)
        assertEquals(text, preview.fit.lines.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(0, region.interior.inset(edit.margin).outsideInkCount(preview.fit.ink))
        val output = File(System.getProperty("ruyo.previewDir"), "dense-japanese-auto-fit.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { preview.crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shortTextKeepsANormalSizeInsteadOfGrowingToFillTheBubble() {
        val source = SampleChapter.build().first().original
        val region = (BubbleSelector.select(source, 450, 105) as SelectionResult.Selected).region
        val normal = BubbleEditRenderer.preview(source, BubbleEdit(region = region, japanese = "はい。", margin = 15)).getOrThrow()
        val smaller = BubbleEditRenderer.preview(source, BubbleEdit(region = region, japanese = "はい。", margin = 15, fontScale = 0.8f)).getOrThrow()
        assertEquals(BubbleEditRenderer.preferredSize(source.width), normal.fit.fontSize, 0.01f)
        assertTrue(smaller.fit.fontSize < normal.fit.fontSize)
    }

    @Test fun invalidReplacementNeverOverwritesSavedEdits() {
        val store = LocalBookStore(RuntimeEnvironment.getApplication())
        val source = SampleChapter.build().first().original
        val book = store.addBitmap("My chapter", source)
        val region = (BubbleSelector.select(source, 450, 105) as SelectionResult.Selected).region
        val valid = BubbleEdit(region = region, japanese = "待って！", margin = 15)
        store.saveEdit(book.id, source, valid)
        assertTrue(runCatching { store.saveEdit(book.id, source, valid.copy(japanese = "W".repeat(512))) }.isFailure)
        assertEquals("待って！", store.open(book).edits.single().japanese)
    }
}
