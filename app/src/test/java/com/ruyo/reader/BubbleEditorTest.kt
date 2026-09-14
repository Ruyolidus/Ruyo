package com.ruyo.reader

import android.graphics.Bitmap
import android.graphics.Color
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

    @Test fun importAndEditsSurviveReopeningAndCanBeUndone() {
        val store = LocalBookStore(RuntimeEnvironment.getApplication())
        val source = SampleChapter.build().first().original
        val book = store.addBitmap("My chapter", source)
        val region = (BubbleSelector.select(source, 450, 105) as SelectionResult.Selected).region
        val edit = BubbleEdit(region = region, japanese = "待って！", margin = 15)
        store.saveEdit(book.id, source, edit)
        val reopened = LocalBookStore(RuntimeEnvironment.getApplication()).open(book)
        assertEquals("待って！", reopened.edits.single().japanese)
        assertTrue(source.sameAs(reopened.original))
        assertFalse(reopened.original.sameAs(reopened.displayed))
        assertTrue(region.eraseMask.contentEquals(reopened.edits.single().region.eraseMask))
        store.removeEdit(book.id, edit.id)
        val restored = store.open(book)
        assertTrue(restored.edits.isEmpty())
        assertTrue(source.sameAs(restored.displayed))
    }

    @Test fun invalidReplacementNeverOverwritesSavedEdits() {
        val store = LocalBookStore(RuntimeEnvironment.getApplication())
        val source = SampleChapter.build().first().original
        val book = store.addBitmap("My chapter", source)
        val region = (BubbleSelector.select(source, 450, 105) as SelectionResult.Selected).region
        val valid = BubbleEdit(region = region, japanese = "待って！", margin = 15)
        store.saveEdit(book.id, source, valid)
        assertTrue(runCatching { store.saveEdit(book.id, source, valid.copy(japanese = "あ".repeat(512))) }.isFailure)
        assertEquals("待って！", store.open(book).edits.single().japanese)
    }
}
