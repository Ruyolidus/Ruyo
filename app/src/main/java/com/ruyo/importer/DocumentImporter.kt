package com.ruyo.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ruyo.data.LocalBookStore
import com.ruyo.data.StagedPage
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Bounded document expansion into the same staged pages used by image imports. Call on IO. */
class DocumentImporter(private val context: Context, private val store: LocalBookStore) {
    fun stage(uri: Uri, capacity: Int, byteBudget: Long, check: () -> Unit, progress: (Int, Int) -> Unit = { _, _ -> }): List<StagedPage> {
        val name = ImageImporter.name(context, uri)
        val type = context.contentResolver.getType(uri).orEmpty()
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension !in listOf("pdf", "cbz", "zip") && type !in listOf("application/pdf", "application/zip", "application/x-cbz", "application/vnd.comicbook+zip")) {
            require(capacity > 0) { "This chapter already has 200 pages." }
            return listOf(store.stage(uri, check))
        }
        val temp = File.createTempFile("document-", ".bin", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(32768); var bytes = 0L
                    while (true) {
                        check(); val count = input.read(buffer); if (count < 0) break
                        bytes += count; require(bytes <= MAX_DOCUMENT_BYTES) { "PDF/CBZ files must be no larger than 128 MB." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("This document could not be opened.")
            val signature = ByteArray(5).also { bytes -> temp.inputStream().use { it.read(bytes) } }
            return if (extension == "pdf" || type == "application/pdf" || signature.toString(Charsets.US_ASCII) == "%PDF-") {
                pdf(temp, name, capacity, byteBudget, check, progress)
            } else zip(temp, capacity, byteBudget, check, progress)
        } finally { temp.delete() }
    }

    internal fun zip(file: File, capacity: Int, byteBudget: Long, check: () -> Unit, progress: (Int, Int) -> Unit = { _, _ -> }): List<StagedPage> {
        val pages = mutableListOf<StagedPage>()
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().take(4097).toList()
                require(entries.size <= 4096) { "This archive contains too many entries." }
                require(entries.all { safeEntry(it.name) }) { "The archive contains an unsafe file path." }
                val images = entries.filter { !it.isDirectory && !it.name.startsWith("__MACOSX/") && it.name.substringAfterLast('.').lowercase(Locale.ROOT) in IMAGE_TYPES }
                    .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
                require(images.isNotEmpty()) { "This CBZ/ZIP contains no supported images." }
                require(images.size <= capacity) { "This document has ${images.size} pages; only $capacity fit in this chapter. Split the document first." }
                require(images.map { it.name }.distinct().size == images.size) { "This archive has duplicate image entries." }
                var expanded = 0L; var stored = 0L
                images.forEachIndexed { index, entry ->
                    check()
                    require(entry.size <= LocalBookStore.MAX_IMAGE_BYTES) { "An archive image exceeds 40 MB." }
                    val page = store.stageStream(entry.name, check) {
                        val source = zip.getInputStream(entry)
                        object : java.io.FilterInputStream(source) {
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                check(); val count = super.read(buffer, offset, length)
                                if (count > 0) { expanded += count; require(expanded <= MAX_EXPANDED_BYTES) { "The archive expands beyond 512 MB." } }
                                return count
                            }
                            override fun read(): Int { val single = ByteArray(1); return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255 }
                        }
                    }
                    pages += page; stored += size(page)
                    require(stored <= byteBudget) { "The prepared document exceeds this import's storage budget." }
                    progress(index + 1, images.size)
                }
            }
            return pages
        } catch (error: Exception) { store.discard(pages); throw error }
    }

    internal fun pdf(file: File, name: String, capacity: Int, byteBudget: Long, check: () -> Unit, progress: (Int, Int) -> Unit = { _, _ -> }): List<StagedPage> {
        val pages = mutableListOf<StagedPage>()
        try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(descriptor) } catch (error: Exception) { descriptor.close(); throw IllegalArgumentException("This PDF could not be opened. Use an unencrypted, readable PDF.", error) }
            renderer.use {
                require(it.pageCount in 1..capacity) { "This PDF has ${it.pageCount} pages; only $capacity fit in this chapter. Split the PDF first." }
                var stored = file.length()
                for (index in 0 until it.pageCount) {
                    check()
                    it.openPage(index).use { page ->
                        require(page.width > 0 && page.height > 0)
                        val scale = min(2.0, min(sqrt(ImageImporter.MAX_WORKING_PIXELS / (page.width.toDouble() * page.height)), ImageImporter.MAX_WORKING_DIMENSION.toDouble() / max(page.width, page.height)))
                        val bitmap = Bitmap.createBitmap(max(1, (page.width * scale).toInt()), max(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            check()
                            val staged = store.stageBitmap(name.substringBeforeLast('.') + " · page " + (index + 1), bitmap, reduced = scale < 2)
                            pages += staged; stored += size(staged)
                            require(stored <= byteBudget) { "The rendered PDF exceeds this import's storage budget." }
                        } finally { bitmap.recycle() }
                    }
                    progress(index + 1, it.pageCount)
                }
            }
            check()
            file.copyTo(File(pages.first().folder, "document.pdf")) // Preserve the source document once per import.
            return pages
        } catch (error: Exception) { store.discard(pages); throw error }
    }
    private fun size(page: StagedPage) = page.folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    companion object {
        const val MAX_DOCUMENT_BYTES = 128L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 512L * 1024 * 1024
        val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")
        fun safeEntry(name: String): Boolean = name.isNotBlank() && name.length <= 1024 && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') && name.split('/').none { it == ".." || it == "." } && name.none(Char::isISOControl)
    }
}
