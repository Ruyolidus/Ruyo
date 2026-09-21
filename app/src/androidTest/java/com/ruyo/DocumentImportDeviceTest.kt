package com.ruyo

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ruyo.data.LocalBookStore
import com.ruyo.importer.DocumentImporter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentImportDeviceTest {
    @Test fun pdfPagesRenderInOrderAtBoundedResolutionAndKeepTheSource() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val file = File(root, "chapter.pdf")
            PdfDocument().use { pdf ->
                for ((index, color) in listOf(Color.RED, Color.BLUE).withIndex()) {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawRect(20f, 20f, 100f, 100f, Paint().apply { this.color = color })
                    pdf.finishPage(page)
                }
                file.outputStream().use(pdf::writeTo)
            }
            val store = LocalBookStore(context, File(root, "library"), File(root, "stage"))
            val pages = DocumentImporter(context, store).pdf(file, "chapter.pdf", 200, 512L * 1024 * 1024, {})
            assertEquals(2, pages.size)
            assertEquals(600, pages.first().page.width); assertEquals(800, pages.first().page.height)
            assertArrayEquals(file.readBytes(), File(pages.first().folder, "document.pdf").readBytes())
            val book = store.commitChapter("PDF", pages)
            val first = store.openPage(book, book.pages[0]); val second = store.openPage(book, book.pages[1])
            assertEquals(Color.RED, first.original.getPixel(80, 80)); assertEquals(Color.BLUE, second.original.getPixel(80, 80))
            assertEquals(Color.WHITE, first.original.getPixel(599, 799))
            assertTrue(runCatching { DocumentImporter(context, store).pdf(file, "chapter.pdf", 1, Long.MAX_VALUE, {}) }.isFailure)
            assertTrue(File(root, "stage").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
