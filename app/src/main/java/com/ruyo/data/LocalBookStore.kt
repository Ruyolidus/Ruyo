package com.ruyo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import com.ruyo.importer.ImageImporter
import com.ruyo.reader.BubbleEdit
import com.ruyo.reader.BubbleEditRenderer
import com.ruyo.reader.BubbleRegion
import com.ruyo.reader.PixelMask
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LocalBook(val id: String, val title: String, val width: Int, val height: Int, val reduced: Boolean, val added: Long)
data class OpenBook(val book: LocalBook, val original: Bitmap, val displayed: Bitmap, val edits: List<BubbleEdit>)
data class SavedLine(val id: String, val japanese: String, val source: String, val sampleId: String? = null)

/** All methods perform disk work and must be called on an IO/background dispatcher. */
class LocalBookStore(context: Context) {
    private val root = File(context.filesDir, "books").apply { mkdirs() }
    private val savedFile = File(context.filesDir, "saved-lines.json")
    private val appContext = context.applicationContext

    fun list(): List<LocalBook> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
        runCatching { decodeBook(JSONObject(AtomicFile(File(folder, "book.json")).readFully().toString(Charsets.UTF_8))) }.getOrNull()
    }.sortedByDescending { it.added }

    fun import(uri: Uri): LocalBook {
        val image = ImageImporter.load(appContext, uri)
        return try { addBitmap(image.name.substringBeforeLast('.', image.name), image.bitmap, image.reducedForPreview) }
        finally { image.bitmap.recycle() }
    }

    @Synchronized
    fun addBitmap(title: String, bitmap: Bitmap, reduced: Boolean = false): LocalBook {
        val book = LocalBook(UUID.randomUUID().toString(), title.take(120).ifBlank { "Untitled image" }, bitmap.width, bitmap.height, reduced, System.currentTimeMillis())
        val folder = folder(book.id).apply { mkdirs() }
        try {
            File(folder, "source.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val ratio = minOf(1f, 360f / bitmap.width, 520f / bitmap.height)
            val thumbnail = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()), true)
            File(folder, "cover.png").outputStream().use { check(thumbnail.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            if (thumbnail !== bitmap) thumbnail.recycle()
            write(File(folder, "edits.json"), "[]")
            write(File(folder, "book.json"), JSONObject().put("id", book.id).put("title", book.title).put("width", book.width).put("height", book.height).put("reduced", book.reduced).put("added", book.added).toString())
        } catch (error: Exception) { folder.deleteRecursively(); throw error }
        return book
    }

    fun thumbnail(id: String): Bitmap? = BitmapFactory.decodeFile(File(folder(id), "cover.png").path)

    fun open(book: LocalBook): OpenBook {
        val original = requireNotNull(BitmapFactory.decodeFile(File(folder(book.id), "source.png").path)) { "The image could not be read." }
        val edits = readEdits(book.id)
        return OpenBook(book, original, if (edits.isEmpty()) original else BubbleEditRenderer.composite(original, edits), edits)
    }

    @Synchronized
    fun saveEdit(bookId: String, source: Bitmap, edit: BubbleEdit) {
        val validated = BubbleEditRenderer.preview(source, edit).getOrThrow()
        validated.crop.recycle(); validated.fit.ink.recycle()
        val existing = readEdits(bookId)
        require(existing.size < 40 || existing.any { it.id == edit.id }) { "This image already has 40 edited bubbles." }
        val next = existing.filterNot { it.id == edit.id } + edit
        writeEdits(bookId, next)
    }

    @Synchronized
    fun removeEdit(bookId: String, id: String) = writeEdits(bookId, readEdits(bookId).filterNot { it.id == id })
    @Synchronized
    fun removeBook(bookId: String) { check(folder(bookId).deleteRecursively()) { "The image could not be removed." } }

    fun savedLines(): List<SavedLine> {
        if (!savedFile.exists()) return emptyList()
        val list = JSONArray(AtomicFile(savedFile).readFully().toString(Charsets.UTF_8))
        return (0 until list.length()).map { i -> list.getJSONObject(i).let { SavedLine(it.getString("id"), it.getString("japanese"), it.getString("source"), it.optString("sampleId").takeIf(String::isNotBlank)) } }
    }

    @Synchronized
    fun toggleSaved(line: SavedLine): List<SavedLine> {
        val lines = savedLines()
        val next = if (lines.any { it.id == line.id }) lines.filterNot { it.id == line.id } else listOf(line) + lines
        write(savedFile, JSONArray().apply { next.forEach { put(JSONObject().put("id", it.id).put("japanese", it.japanese).put("source", it.source).put("sampleId", it.sampleId.orEmpty())) } }.toString())
        return next
    }

    private fun readEdits(id: String): List<BubbleEdit> {
        val array = JSONArray(AtomicFile(File(folder(id), "edits.json")).readFully().toString(Charsets.UTF_8))
        require(array.length() <= 40)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val w = obj.getInt("width"); val h = obj.getInt("height")
            require(w > 0 && h > 0 && w.toLong() * h <= 900_000)
            val region = BubbleRegion(obj.getInt("left"), obj.getInt("top"), PixelMask(w, h, unpack(obj.getString("interior"), w * h)), unpack(obj.getString("erase"), w * h), obj.getInt("color"))
            BubbleEdit(obj.getString("id"), region, obj.getString("text"), obj.getInt("margin"))
        }
    }

    private fun writeEdits(id: String, edits: List<BubbleEdit>) = write(File(folder(id), "edits.json"), JSONArray().apply {
        edits.forEach { edit -> put(JSONObject().put("id", edit.id).put("text", edit.japanese).put("margin", edit.margin)
            .put("left", edit.region.left).put("top", edit.region.top).put("width", edit.region.width).put("height", edit.region.height)
            .put("color", edit.region.backgroundColor).put("interior", pack(edit.region.interior.copyPixels())).put("erase", pack(edit.region.eraseMask))) }
    }.toString())

    private fun folder(id: String): File { require(id.matches(Regex("[a-f0-9-]{36}"))); return File(root, id) }
    private fun decodeBook(obj: JSONObject) = LocalBook(obj.getString("id"), obj.getString("title"), obj.getInt("width"), obj.getInt("height"), obj.getBoolean("reduced"), obj.getLong("added"))
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
}
