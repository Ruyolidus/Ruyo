package com.ruyo.importer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.ruyo.data.LocalBookStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentImporterTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }
    private fun archive(root: File, items: List<Pair<String, ByteArray>>): File = File(root, "fixture.cbz").also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> items.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }
    @Test fun cbzUsesNaturalPageOrderAndPreservesImageBytes() {
        val root = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val store = LocalBookStore(context, File(root, "store"), File(root, "stage")); val importer = DocumentImporter(context, store)
            val red = png(Color.RED); val blue = png(Color.BLUE)
            val file = archive(root, listOf("10.png" to blue, "2.png" to red, "ComicInfo.xml" to "metadata".toByteArray()))
            val pages = importer.zip(file, 200, 512L * 1024 * 1024, {})
            assertEquals(listOf("2.png", "10.png"), pages.map { it.page.name })
            assertArrayEquals(red, File(pages.first().folder, "original.bin").readBytes())
            val book = store.commitChapter("Archive", pages)
            assertEquals(2, store.readBook(book.id).pages.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun unsafeOversizedAndCancelledArchivesDoNotLeavePartialPages() {
        val root = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val stage = File(root, "stage"); val store = LocalBookStore(context, File(root, "store"), stage); val importer = DocumentImporter(context, store)
            val bytes = png(Color.WHITE)
            for (name in listOf("../escape.png", "/escape.png", "C:/escape.png", "a/../../escape.png")) {
                assertTrue(runCatching { importer.zip(archive(root, listOf(name to bytes)), 200, Long.MAX_VALUE, {}) }.isFailure)
            }
            val valid = archive(root, listOf("1.png" to bytes, "2.png" to bytes))
            assertTrue(runCatching { importer.zip(valid, 1, Long.MAX_VALUE, {}) }.isFailure)
            assertTrue(runCatching { importer.zip(valid, 200, 1, {}) }.isFailure)
            assertTrue(stage.listFiles().orEmpty().isEmpty())
            assertTrue(runCatching { importer.zip(valid, 200, Long.MAX_VALUE, {}, { _, _ -> throw kotlinx.coroutines.CancellationException() }) }.isFailure)
            assertTrue(stage.listFiles().orEmpty().isEmpty())
            val corrupt = archive(root, listOf("1.png" to bytes, "2.png" to byteArrayOf(1, 2, 3)))
            assertTrue(runCatching { importer.zip(corrupt, 200, Long.MAX_VALUE, {}) }.isFailure)
            assertTrue(stage.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
