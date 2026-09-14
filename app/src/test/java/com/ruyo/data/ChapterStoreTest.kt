package com.ruyo.data

import android.graphics.Bitmap
import android.graphics.Color
import com.ruyo.importer.NaturalOrder
import com.ruyo.reader.BubbleEdit
import com.ruyo.reader.BubbleSelector
import com.ruyo.reader.SelectionResult
import com.ruyo.sample.SampleChapter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChapterStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { File(context.filesDir, "books").deleteRecursively(); File(context.cacheDir, "chapter-imports").deleteRecursively() }
    private fun bitmap(color: Int = Color.WHITE) = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun numericFilenamesUseReadingOrderIncludingLargeNumbersAndLeadingZeros() {
        val names = listOf("chapter10.png", "chapter0002.png", "chapter1.png", "chapter999999999999999999999999999.png")
        assertEquals(listOf("chapter1.png", "chapter0002.png", "chapter10.png", "chapter999999999999999999999999999.png"), names.sortedWith(NaturalOrder))
    }

    @Test fun multiPageChapterReordersAppendsAndKeepsPageEditsAndProgress() {
        val store = LocalBookStore(context)
        val source = SampleChapter.build().first().original
        val first = store.stageBitmap("01.png", source)
        val second = store.stageBitmap("02.png", bitmap(Color.BLUE))
        val book = store.commitChapter("Test chapter", listOf(first, second))
        val region = (BubbleSelector.select(source, 450, 105) as SelectionResult.Selected).region
        val edit = BubbleEdit(region = region, japanese = "待って！", margin = 15)
        store.saveEdit(book.id, source, edit, first.page.id)
        store.saveProgress(book.id, ReadingPosition(first.page.id, 150))
        val reordered = store.updateChapter(book.id, "Renamed", listOf(second.page.id, first.page.id))
        assertEquals(listOf(second.page.id, first.page.id), reordered.pages.map { it.id })
        assertEquals(ReadingPosition(first.page.id, 150), LocalBookStore(context).progress(reordered))
        assertTrue(store.openPage(reordered, reordered.pages.first()).edits.isEmpty())
        assertEquals(edit.id, store.openPage(reordered, reordered.pages.last()).edits.single().id)
        val third = store.stageBitmap("03.png", bitmap(Color.GREEN))
        val appended = store.commitChapter(reordered.title, listOf(third), book.id)
        assertEquals(listOf(second.page.id, first.page.id, third.page.id), appended.pages.map { it.id })
        assertEquals("Renamed", appended.title)
        val removed = store.updateChapter(book.id, appended.title, listOf(second.page.id, third.page.id))
        assertEquals(ReadingPosition(second.page.id, 0), store.progress(removed))
        assertEquals(2, LocalBookStore(context).list().single().pages.size)
    }

    @Test fun oldSingleImageFormatRetainsItsSourceAndEditsAfterAppend() {
        val store = LocalBookStore(context)
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "books/$id").apply { mkdirs() }
        val source = SampleChapter.build().first().original
        File(dir, "source.png").outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "cover.png").writeBytes(File(dir, "source.png").readBytes())
        File(dir, "edits.json").writeText("[]")
        File(dir, "book.json").writeText(JSONObject().put("id", id).put("title", "Legacy").put("width", source.width).put("height", source.height).put("reduced", false).put("added", 1).toString())
        val old = store.list().single()
        val region = (BubbleSelector.select(source, 450, 105) as SelectionResult.Selected).region
        store.saveEdit(id, source, BubbleEdit(region = region, japanese = "待って！", margin = 15))
        val appended = store.commitChapter("Legacy", listOf(store.stageBitmap("New page", bitmap())), id)
        assertTrue(appended.pages.first().legacy)
        assertEquals(old.id, appended.pages.first().id)
        val reopened = LocalBookStore(context).open(appended)
        assertEquals("待って！", reopened.edits.single().japanese)
        assertTrue(source.sameAs(reopened.original))
    }

    @Test fun failedCommitRollsBackMovedPagesAndNeverPartiallyAppends() {
        val store = LocalBookStore(context)
        val book = store.addBitmap("Existing", bitmap())
        val good = store.stageBitmap("First", bitmap())
        val lost = store.stageBitmap("Second", bitmap())
        lost.folder.deleteRecursively()
        assertTrue(runCatching { store.commitChapter("Updated", listOf(good, lost), book.id) }.isFailure)
        assertEquals(1, store.readBook(book.id).pages.size)
        assertEquals("Existing", store.readBook(book.id).title)
        assertTrue(good.folder.isDirectory)
        assertEquals(2, store.commitChapter("Updated", listOf(good), book.id).pages.size)
    }

    @Test fun realPngBytesCanBeStagedAndOriginalBytesArePreserved() {
        val store = LocalBookStore(context)
        val source = bitmap(Color.GREEN)
        val bytes = java.io.ByteArrayOutputStream().apply { source.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        val stage = store.stageStream("Green.png") { ByteArrayInputStream(bytes) }
        assertArrayEquals(bytes, File(stage.folder, "original.bin").readBytes())
        val book = store.commitChapter("Green", listOf(stage))
        assertTrue(source.sameAs(store.open(book).original))
    }

    @Test fun invalidOrCancelledImageNeverLeavesAnImportFolder() {
        val store = LocalBookStore(context)
        assertTrue(runCatching { store.stageStream("bad.png") { ByteArrayInputStream("not an image".toByteArray()) } }.isFailure)
        assertTrue(runCatching { store.stageStream("cancelled.png", { throw CancellationException() }) { ByteArrayInputStream(byteArrayOf(1)) } }.isFailure)
        assertTrue(File(context.cacheDir, "chapter-imports").listFiles().orEmpty().isEmpty())
        assertTrue(store.list().isEmpty())
    }
}
