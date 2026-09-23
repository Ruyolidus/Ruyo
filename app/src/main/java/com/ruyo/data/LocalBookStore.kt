package com.ruyo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import com.ruyo.importer.ImageImporter
import com.ruyo.reader.BubbleEdit
import com.ruyo.reader.BubbleAreas
import com.ruyo.reader.BubbleEditRenderer
import com.ruyo.reader.BubbleRegion
import com.ruyo.reader.PixelMask
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

data class LocalPage(val id: String, val name: String, val width: Int, val height: Int, val reduced: Boolean, val legacy: Boolean = false)
data class LocalBook(
    val id: String, val title: String, val width: Int, val height: Int, val reduced: Boolean, val added: Long,
    val pages: List<LocalPage> = listOf(LocalPage(id, title, width, height, reduced, legacy = true)),
    val sourceUrl: String? = null,
)
data class OpenBook(val book: LocalBook, val original: Bitmap, val displayed: Bitmap, val edits: List<BubbleEdit>, val page: LocalPage = book.pages.first())
data class StagedPage(val page: LocalPage, val folder: File)
data class PagePreparation(val message: String, val needsReview: Boolean)
data class ReadingPosition(val pageId: String, val offset: Int)
data class SavedLine(val id: String, val japanese: String, val source: String, val sampleId: String? = null, val languageTag: String = "ja")

/** Disk operations belong on IO. book.json is the commit point for a chapter import. */
class LocalBookStore(context: Context, storageDirectory: File = context.filesDir, stagingDirectory: File = File(context.cacheDir, "chapter-imports")) {
    private val root = File(storageDirectory, "books").apply { mkdirs() }
    private val staging = stagingDirectory.apply { mkdirs() }
    private val savedFile = File(storageDirectory, "saved-lines.json")
    private val appContext = context.applicationContext

    @Synchronized fun list(): List<LocalBook> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
        runCatching { readBook(folder.name) }.getOrNull()
    }.sortedByDescending { it.added }

    @Synchronized fun readBook(id: String): LocalBook = decodeBook(JSONObject(read(File(folder(id), "book.json"))))

    fun stage(uri: Uri, checkCancelled: () -> Unit = {}): StagedPage = stageStream(ImageImporter.name(appContext, uri), checkCancelled) {
        requireNotNull(appContext.contentResolver.openInputStream(uri)) { "This image could not be opened." }
    }

    fun stageStream(name: String, checkCancelled: () -> Unit = {}, stream: () -> InputStream): StagedPage {
        val id = UUID.randomUUID().toString()
        val dir = File(staging, id).apply { mkdirs() }
        try {
            stream().use { input -> File(dir, "original.bin").outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_IMAGE_BYTES) { "This image exceeds the 40 MB import limit." }
                    output.write(buffer, 0, count)
                }
            } }
            checkCancelled()
            val image = ImageImporter.load(File(dir, "original.bin"), name)
            return try {
                checkCancelled()
                preparePage(id, name, image.bitmap, image.reducedForPreview, dir)
            } finally { image.bitmap.recycle() }
        } catch (error: Exception) { dir.deleteRecursively(); throw error }
    }

    fun stageBitmap(title: String, bitmap: Bitmap, reduced: Boolean = false): StagedPage {
        val id = UUID.randomUUID().toString()
        val dir = File(staging, id).apply { mkdirs() }
        return try { preparePage(id, title, bitmap, reduced, dir) }
        catch (error: Exception) { dir.deleteRecursively(); throw error }
    }

    private fun preparePage(id: String, name: String, bitmap: Bitmap, reduced: Boolean, dir: File): StagedPage {
        File(dir, "source.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val ratio = minOf(1f, 360f / bitmap.width, 520f / bitmap.height)
        val thumb = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()), true)
        try { File(dir, "cover.png").outputStream().use { check(thumb.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { if (thumb !== bitmap) thumb.recycle() }
        write(File(dir, "edits.json"), "[]")
        return StagedPage(LocalPage(id, name.take(160).ifBlank { "Image" }, bitmap.width, bitmap.height, reduced), dir)
    }

    fun discard(pages: List<StagedPage>) { pages.forEach { if (it.folder.parentFile == staging) it.folder.deleteRecursively() } }

    @Synchronized fun commitChapter(title: String, pages: List<StagedPage>, appendTo: String? = null, sourceUrl: String? = null): LocalBook {
        require(pages.isNotEmpty()) { "Select at least one image." }
        require(pages.map { it.page.id }.distinct().size == pages.size)
        val existing = appendTo?.let(::readBook)
        require(pages.size + (existing?.pages?.size ?: 0) <= MAX_PAGES) { "A chapter can contain up to 200 images." }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val dir = folder(id).apply { mkdirs() }
        val destination = File(dir, "pages").apply { mkdirs() }
        val moved = mutableListOf<Pair<File, File>>()
        try {
            for (page in pages) {
                require(page.folder.parentFile == staging && page.folder.isDirectory) { "Import files are no longer available. Please select the images again." }
                val target = File(destination, page.page.id)
                check(!target.exists() && page.folder.renameTo(target)) { "Could not save the images. Check free storage and try again." }
                moved += page.folder to target
            }
            val all = existing?.pages.orEmpty() + pages.map { it.page }
            val first = all.first()
            val book = LocalBook(id, title.trim().take(120).ifBlank { "Untitled chapter" }, first.width, first.height,
                all.any { it.reduced }, existing?.added ?: System.currentTimeMillis(), all, existing?.sourceUrl ?: sourceUrl)
            writeBook(book)
            return book
        } catch (error: Exception) {
            moved.asReversed().forEach { (from, to) -> to.renameTo(from) }
            if (existing == null) dir.deleteRecursively()
            throw error
        }
    }

    fun import(uri: Uri): LocalBook {
        val page = stage(uri)
        return try { commitChapter(page.page.name.substringBeforeLast('.'), listOf(page)) }
        finally { discard(listOf(page)) }
    }
    fun addBitmap(title: String, bitmap: Bitmap, reduced: Boolean = false): LocalBook {
        val page = stageBitmap(title, bitmap, reduced)
        return try { commitChapter(title, listOf(page)) } finally { discard(listOf(page)) }
    }

    @Synchronized fun updateChapter(id: String, title: String, pageIds: List<String>): LocalBook {
        val old = readBook(id)
        require(pageIds.isNotEmpty()) { "Keep at least one page, or remove the whole chapter." }
        require(pageIds.distinct().size == pageIds.size && pageIds.all { id -> old.pages.any { it.id == id } })
        val pages = pageIds.map { id -> old.pages.first { it.id == id } }
        val book = old.copy(title = title.trim().take(120).ifBlank { old.title }, pages = pages,
            width = pages.first().width, height = pages.first().height, reduced = pages.any { it.reduced })
        writeBook(book)
        // Unreferenced files may be collected only after the metadata commit succeeds.
        old.pages.filter { it.id !in pageIds && !it.legacy }.forEach { pageFolder(old, it).deleteRecursively() }
        return book
    }

    @Synchronized fun thumbnail(id: String): Bitmap? {
        val book = runCatching { readBook(id) }.getOrNull() ?: return null
        return pageThumbnail(book, book.pages.first())
    }
    fun pageThumbnail(book: LocalBook, page: LocalPage): Bitmap? = BitmapFactory.decodeFile(File(pageFolder(book, page), "cover.png").path)

    fun open(book: LocalBook): OpenBook = openPage(book, book.pages.first())
    @Synchronized fun openPage(book: LocalBook, page: LocalPage): OpenBook {
        require(book.pages.any { it.id == page.id })
        val dir = pageFolder(book, page)
        val original = requireNotNull(BitmapFactory.decodeFile(File(dir, "source.png").path)) { "This page could not be read." }
        return try {
            val edits = readEdits(dir)
            OpenBook(book, original, if (edits.isEmpty()) original else BubbleEditRenderer.composite(original, edits), edits, page)
        } catch (error: Exception) { original.recycle(); throw error }
    }

    @Synchronized fun saveEdit(bookId: String, source: Bitmap, edit: BubbleEdit, pageId: String? = null) {
        val book = readBook(bookId)
        val dir = pageFolder(book, book.pages.first { pageId == null || it.id == pageId })
        val validated = BubbleEditRenderer.preview(source, edit).getOrThrow()
        validated.crop.recycle(); validated.fit.ink.recycle()
        val existing = readEdits(dir)
        require(existing.none { it.id != edit.id && it.region.overlaps(edit.region) }) {
            "This area overlaps a saved translation. Restore that translation before changing its boundaries."
        }
        require(existing.size < 40 || existing.any { it.id == edit.id }) { "This page already has 40 edited bubbles." }
        writeEdits(dir, existing.filterNot { it.id == edit.id } + edit)
        invalidatePreparation(bookId, book.pages.first { pageId == null || it.id == pageId }.id)
    }
    @Synchronized fun removeEdit(bookId: String, id: String, pageId: String? = null) {
        val book = readBook(bookId)
        val dir = pageFolder(book, book.pages.first { pageId == null || it.id == pageId })
        writeEdits(dir, readEdits(dir).filterNot { it.id == id })
        invalidatePreparation(bookId, book.pages.first { pageId == null || it.id == pageId }.id)
    }
    @Synchronized fun removeBook(bookId: String) { check(folder(bookId).deleteRecursively()) { "The chapter could not be removed." } }

    @Synchronized fun preparation(book: LocalBook, key: String): Map<String, PagePreparation> {
        val file = File(folder(book.id), "preparation.json")
        if (!file.exists()) return emptyMap()
        val obj = JSONObject(read(file))
        if (obj.optString("key") != key) return emptyMap()
        val pages = obj.getJSONObject("pages")
        return book.pages.mapNotNull { page -> pages.optJSONObject(page.id)?.let {
            page.id to PagePreparation(it.getString("message"), it.getBoolean("review"))
        } }.toMap()
    }
    @Synchronized fun recordPreparation(book: LocalBook, key: String, pageId: String, value: PagePreparation) {
        require(book.pages.any { it.id == pageId })
        val values = preparation(book, key) + (pageId to value)
        write(File(folder(book.id), "preparation.json"), JSONObject().put("key", key).put("pages", JSONObject().apply {
            values.forEach { (id, report) -> put(id, JSONObject().put("message", report.message).put("review", report.needsReview)) }
        }).toString())
    }
    private fun invalidatePreparation(bookId: String, pageId: String) {
        val file = File(folder(bookId), "preparation.json")
        if (file.exists()) {
            val obj = JSONObject(read(file)); obj.getJSONObject("pages").remove(pageId); write(file, obj.toString())
        }
    }

    @Synchronized fun progress(book: LocalBook): ReadingPosition {
        val first = ReadingPosition(book.pages.first().id, 0)
        return runCatching {
            val data = JSONObject(read(File(folder(book.id), "progress.json")))
            ReadingPosition(data.getString("page"), data.getInt("offset").coerceAtLeast(0)).takeIf { p -> book.pages.any { it.id == p.pageId } } ?: first
        }.getOrDefault(first)
    }
    @Synchronized fun saveProgress(bookId: String, position: ReadingPosition) {
        if (!File(folder(bookId), "book.json").exists()) return
        val book = readBook(bookId)
        if (book.pages.none { it.id == position.pageId }) return
        write(File(folder(bookId), "progress.json"), JSONObject().put("page", position.pageId).put("offset", position.offset.coerceAtLeast(0)).toString())
    }

    @Synchronized fun savedLines(): List<SavedLine> {
        if (!savedFile.exists()) return emptyList()
        val list = JSONArray(read(savedFile))
        return (0 until list.length()).map { i -> list.getJSONObject(i).let { SavedLine(it.getString("id"), it.getString("japanese"), it.getString("source"), it.optString("sampleId").takeIf(String::isNotBlank), it.optString("language", "ja")) } }
    }
    @Synchronized fun toggleSaved(line: SavedLine): List<SavedLine> {
        val lines = savedLines()
        val next = if (lines.any { it.id == line.id }) lines.filterNot { it.id == line.id } else listOf(line) + lines
        write(savedFile, JSONArray().apply { next.forEach { put(JSONObject().put("id", it.id).put("japanese", it.japanese).put("source", it.source).put("sampleId", it.sampleId.orEmpty()).put("language", it.languageTag)) } }.toString())
        return next
    }

    fun areaCenters(book: OpenBook, region: BubbleRegion): List<android.graphics.Point>? {
        val file = File(pageFolder(book.book, book.page), "areas.json")
        if (!file.exists()) return null
        val entry = JSONObject(read(file)).optJSONArray(areaKey(region)) ?: return null
        require(entry.length() in 1..BubbleAreas.MAX_AREAS)
        return (0 until entry.length()).map { i -> entry.getJSONArray(i).let { android.graphics.Point(it.getInt(0), it.getInt(1)) } }
    }

    fun saveAreas(book: OpenBook, region: BubbleRegion, centers: List<android.graphics.Point>) {
        BubbleAreas.split(region, centers)
        require(readEdits(pageFolder(book.book, book.page)).none { it.region.overlaps(region) }) {
            "Restore the translations in this joined bubble before changing its areas."
        }
        val file = File(pageFolder(book.book, book.page), "areas.json")
        val obj = if (file.exists()) JSONObject(read(file)) else JSONObject()
        require(obj.has(areaKey(region)) || obj.length() < 40) { "This image already has 40 saved area groups." }
        obj.put(areaKey(region), JSONArray().apply { centers.forEach { put(JSONArray().put(it.x).put(it.y)) } })
        write(file, obj.toString())
    }

    private fun areaKey(region: BubbleRegion) = listOf(region.left, region.top, region.width, region.height).joinToString(":")

    private fun readEdits(dir: File): List<BubbleEdit> {
        val array = JSONArray(read(File(dir, "edits.json")))
        require(array.length() <= 40)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val w = obj.getInt("width"); val h = obj.getInt("height")
            require(w > 0 && h > 0 && w.toLong() * h <= 900_000)
            val surface = obj.optJSONObject("surface")?.let { s ->
                val corners = s.getJSONArray("corners"); require(corners.length() == 4)
                val rows = s.optJSONArray("rows")
                require(rows == null || rows.length() <= 32_768)
                com.ruyo.reader.BackgroundSurface(s.getInt("left"), s.getInt("top"), s.getInt("width"), s.getInt("height"), (0..3).map { corners.getInt(it) },
                    if (rows == null) emptyList() else (0 until rows.length()).map { rows.getInt(it) })
            }
            val region = BubbleRegion(obj.getInt("left"), obj.getInt("top"), PixelMask(w, h, unpack(obj.getString("interior"), w * h)), unpack(obj.getString("erase"), w * h), obj.getInt("color"),
                surface, obj.optInt("textColor", Color.rgb(39, 42, 53)), obj.optBoolean("inpaint"))
            BubbleEdit(obj.getString("id"), region, obj.getString("text"), obj.getInt("margin"), obj.optDouble("fontScale", 1.0).toFloat(),
                languageTag = obj.optString("language", "ja"), fontFamily = obj.optString("fontFamily", "sans-serif"),
                bold = obj.optBoolean("bold"), italic = obj.optBoolean("italic"),
                sourceLetterHeight = obj.optDouble("sourceLetterHeight", Double.NaN).toFloat().takeIf { it.isFinite() && it > 0f },
                matchSourceSize = obj.optBoolean("matchSourceSize"))
        }
    }
    private fun writeEdits(dir: File, edits: List<BubbleEdit>) = write(File(dir, "edits.json"), JSONArray().apply {
        edits.forEach { edit -> put(JSONObject().put("id", edit.id).put("text", edit.japanese).put("margin", edit.margin).put("fontScale", edit.fontScale.toDouble())
            .put("language", edit.languageTag).put("fontFamily", edit.fontFamily).put("bold", edit.bold).put("italic", edit.italic)
            .put("sourceLetterHeight", edit.sourceLetterHeight?.toDouble()).put("matchSourceSize", edit.matchSourceSize)
            .put("left", edit.region.left).put("top", edit.region.top).put("width", edit.region.width).put("height", edit.region.height)
            .put("inpaint", edit.region.inpaint).put("textColor", edit.region.textColor).put("surface", edit.region.backgroundSurface?.let { s -> JSONObject()
                .put("left", s.left).put("top", s.top).put("width", s.width).put("height", s.height).put("corners", JSONArray(s.corners)).put("rows", JSONArray(s.rows)) })
            .put("color", edit.region.backgroundColor).put("interior", pack(edit.region.interior.copyPixels())).put("erase", pack(edit.region.eraseMask))) }
    }.toString())

    private fun folder(id: String): File { require(id.matches(Regex("[a-f0-9-]{36}"))); return File(root, id) }
    private fun pageFolder(book: LocalBook, page: LocalPage): File {
        require(page.id.matches(Regex("[a-f0-9-]{36}")))
        return if (page.legacy) folder(book.id) else File(folder(book.id), "pages/${page.id}")
    }
    private fun decodeBook(obj: JSONObject): LocalBook {
        val book = LocalBook(obj.getString("id"), obj.getString("title"), obj.getInt("width"), obj.getInt("height"), obj.getBoolean("reduced"), obj.getLong("added"))
        val array = obj.optJSONArray("pages") ?: return book // Version 1: retain paths, masks, IDs and progress compatibility.
        require(array.length() in 1..MAX_PAGES)
        val pages = (0 until array.length()).map { i -> array.getJSONObject(i).let {
            LocalPage(it.getString("id"), it.getString("name"), it.getInt("width"), it.getInt("height"), it.getBoolean("reduced"), it.optBoolean("legacy"))
        } }
        return book.copy(pages = pages, sourceUrl = obj.optString("sourceUrl").takeIf(String::isNotBlank))
    }
    private fun writeBook(book: LocalBook) = write(File(folder(book.id), "book.json"), JSONObject()
        .put("version", 2).put("id", book.id).put("title", book.title).put("width", book.width).put("height", book.height)
        .put("reduced", book.reduced).put("added", book.added).put("sourceUrl", book.sourceUrl.orEmpty())
        .put("pages", JSONArray().apply { book.pages.forEach { page -> put(JSONObject().put("id", page.id).put("name", page.name)
            .put("width", page.width).put("height", page.height).put("reduced", page.reduced).put("legacy", page.legacy)) } }).toString())
    private fun read(file: File) = AtomicFile(file).readFully().toString(Charsets.UTF_8)
    private fun write(file: File, text: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    private fun pack(bits: BooleanArray): String {
        val bytes = ByteArray((bits.size + 7) / 8)
        for (i in bits.indices) if (bits[i]) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    private fun unpack(encoded: String, size: Int): BooleanArray {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size == (size + 7) / 8)
        return BooleanArray(size) { bytes[it / 8].toInt() and (1 shl (it % 8)) != 0 }
    }
    companion object { const val MAX_PAGES = 200; const val MAX_IMAGE_BYTES = 40L * 1024 * 1024 }
}
