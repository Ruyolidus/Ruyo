package com.ruyo.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ruyo.data.LocalBook
import com.ruyo.data.LocalBookStore
import com.ruyo.data.OpenBook
import com.ruyo.data.SavedLine
import com.ruyo.reader.BubbleEdit
import com.ruyo.reader.BubbleEditRenderer
import com.ruyo.reader.BubblePreview
import com.ruyo.reader.BubbleSelector
import com.ruyo.reader.SelectionResult
import com.ruyo.sample.SampleChapter
import com.ruyo.sample.SamplePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.hypot

data class EditorDraft(val edit: BubbleEdit, val crop: Bitmap, val initialMask: BooleanArray, val existing: Boolean = false, val preview: BubblePreview? = null)

class RuyoModel(application: Application) : AndroidViewModel(application) {
    val store = LocalBookStore(application)
    private val prefs = application.getSharedPreferences("settings", 0)
    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system"); private set
    var tab by mutableStateOf("library")
    var route by mutableStateOf("home"); private set
    var books by mutableStateOf<List<LocalBook>>(emptyList()); private set
    var saved by mutableStateOf<List<SavedLine>>(emptyList()); private set
    var samples by mutableStateOf<List<SamplePage>>(emptyList()); private set
    var opened by mutableStateOf<OpenBook?>(null); private set
    var draft by mutableStateOf<EditorDraft?>(null); private set
    var lesson by mutableStateOf<SavedLine?>(null)
    var busy by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var selecting by mutableStateOf(false)
    var japanese by mutableStateOf(true)

    init { refresh() }
    fun refresh() = task {
        val data = withContext(Dispatchers.IO) { store.list() to store.savedLines() }
        books = data.first; saved = data.second
        if (samples.isEmpty()) samples = withContext(Dispatchers.Default) { SampleChapter.build() }
        ready = true
    }
    fun changeTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply() }
    fun home() { route = "home"; selecting = false; draft = null; opened = null }
    fun openSample() { route = "sample"; japanese = true }
    fun importImage(uri: Uri) = task {
        val book = withContext(Dispatchers.IO) { store.import(uri) }
        books = withContext(Dispatchers.IO) { store.list() }
        opened = withContext(Dispatchers.IO) { store.open(book) }
        japanese = true; route = "book"
        message = "Image added to your library"
    }
    fun openBook(book: LocalBook) = task {
        opened = withContext(Dispatchers.IO) { store.open(book) }
        route = "book"; japanese = true; selecting = false
    }
    fun removeBook(book: LocalBook) = task {
        withContext(Dispatchers.IO) { store.removeBook(book.id) }
        books = books.filterNot { it.id == book.id }; home(); message = "Image removed from the library"
    }
    fun selectBubble(x: Int, y: Int) = task {
        val book = opened ?: return@task
        val existing = book.edits.findLast { it.region.contains(x, y) }
        val region = if (existing != null) existing.region else when (val result = withContext(Dispatchers.Default) { BubbleSelector.select(book.original, x, y) }) {
            is SelectionResult.Selected -> result.region
            is SelectionResult.Rejected -> { message = result.reason; return@task }
        }
        val edit = existing ?: BubbleEdit(region = region, japanese = "", margin = maxOf(4, minOf(region.width, region.height) / 14))
        val crop = Bitmap.createBitmap(book.original, region.left, region.top, region.width, region.height)
        draft = EditorDraft(edit, crop, region.eraseMask.copyOf(), existing != null)
        selecting = false; route = "editor"
    }
    fun changeText(value: String) { draft = draft?.let { it.copy(edit = it.edit.copy(japanese = value.take(512)), preview = null) } }
    fun changeMargin(value: Int) { draft = draft?.let { it.copy(edit = it.edit.copy(margin = value), preview = null) } }
    fun resetMask() { draft = draft?.let { it.copy(edit = it.edit.copy(region = it.edit.region.copy(eraseMask = it.initialMask.copyOf())), preview = null) } }
    fun brush(points: List<PointF>, radius: Float, add: Boolean) {
        val current = draft ?: return
        val region = current.edit.region
        val bits = region.eraseMask.copyOf()
        val allowed = region.interior.inset(2)
        fun stamp(p: PointF) {
            val r = ceil(radius).toInt()
            for (dy in -r..r) for (dx in -r..r) {
                val x = p.x.toInt() + dx; val y = p.y.toInt() + dy
                if (dx * dx + dy * dy <= radius * radius && allowed[x, y]) bits[y * region.width + x] = add
            }
        }
        points.firstOrNull()?.let(::stamp)
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val steps = maxOf(1, ceil(hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) / maxOf(1f, radius / 2)).toInt())
            for (step in 1..steps) { val t = step.toFloat() / steps; stamp(PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)) }
        }
        draft = current.copy(edit = current.edit.copy(region = region.copy(eraseMask = bits)), preview = null)
    }
    fun preview() = task {
        val current = draft ?: return@task
        val source = opened?.original ?: return@task
        val preview = withContext(Dispatchers.Default) { BubbleEditRenderer.preview(source, current.edit).getOrThrow() }
        draft = current.copy(preview = preview)
    }
    fun saveEdit() = task {
        val current = draft ?: return@task
        if (current.preview == null) return@task
        val book = opened ?: return@task
        withContext(Dispatchers.IO) { store.saveEdit(book.book.id, book.original, current.edit) }
        opened = withContext(Dispatchers.IO) { store.open(book.book) }
        draft = null; route = "book"; japanese = true
        message = "Bubble saved"
    }
    fun removeEdit() = task {
        val current = draft ?: return@task
        val book = opened ?: return@task
        withContext(Dispatchers.IO) { store.removeEdit(book.book.id, current.edit.id) }
        opened = withContext(Dispatchers.IO) { store.open(book.book) }
        draft = null; route = "book"; message = "Original bubble restored"
    }
    fun cancelEditor() { draft = null; route = "book" }
    fun studySample(id: String) {
        val line = SampleChapter.lines.first { it.id == id }
        lesson = SavedLine("sample:$id", line.japanese, "Before the rain", id)
    }
    fun studyEdit(edit: BubbleEdit) {
        lesson = SavedLine("${opened?.book?.id}:${edit.id}:${edit.japanese.hashCode()}", edit.japanese, opened?.book?.title ?: "Imported image")
    }
    fun toggleSaved(line: SavedLine) = task { saved = withContext(Dispatchers.IO) { store.toggleSaved(line) } }
    private fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "Something went wrong. Please try again." }
            finally { busy = false }
        }
    }
}
