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
import com.ruyo.data.*
import com.ruyo.importer.NaturalOrder
import com.ruyo.reader.*
import com.ruyo.sample.SampleChapter
import com.ruyo.sample.SamplePage
import com.ruyo.web.WebChapter
import com.ruyo.web.WebImageDownload
import kotlinx.coroutines.*
import kotlin.math.ceil
import kotlin.math.hypot

data class EditorDraft(val edit: BubbleEdit, val crop: Bitmap, val initialMask: BooleanArray, val existing: Boolean = false,
    val preview: BubblePreview? = null, val revision: Int = 0, val previewVersion: Int = 0, val previewError: String? = null)
data class ChapterImport(val title: String, val pages: List<StagedPage>, val appendTo: String? = null, val sourceUrl: String? = null,
    val returnRoute: String = "home", val failures: List<String> = emptyList())
data class ImportProgress(val done: Int, val total: Int)

class RuyoModel(application: Application) : AndroidViewModel(application) {
    val store = LocalBookStore(application)
    val pageLoader = ReaderPageLoader(store)
    private val prefs = application.getSharedPreferences("settings", 0)
    private var operation: Job? = null
    private var progressJob: Job? = null
    var theme by mutableStateOf(prefs.getString("theme", "system") ?: "system"); private set
    var tab by mutableStateOf("library")
    var route by mutableStateOf("home"); private set
    var books by mutableStateOf<List<LocalBook>>(emptyList()); private set
    var saved by mutableStateOf<List<SavedLine>>(emptyList()); private set
    var samples by mutableStateOf<List<SamplePage>>(emptyList()); private set
    var chapter by mutableStateOf<LocalBook?>(null); private set
    var readingPosition by mutableStateOf<ReadingPosition?>(null); private set
    var pageRevision by mutableStateOf(0); private set
    var opened by mutableStateOf<OpenBook?>(null); private set
    var draft by mutableStateOf<EditorDraft?>(null); private set
    var importing by mutableStateOf<ChapterImport?>(null); private set
    var importProgress by mutableStateOf<ImportProgress?>(null); private set
    var lesson by mutableStateOf<SavedLine?>(null)
    var busy by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var selecting by mutableStateOf(false)
    var japanese by mutableStateOf(true)
    var webUrl by mutableStateOf("")

    init { refresh() }
    fun refresh() = task {
        val data = withContext(Dispatchers.IO) { store.list() to store.savedLines() }
        books = data.first; saved = data.second
        if (samples.isEmpty()) samples = withContext(Dispatchers.Default) { SampleChapter.build() }
        ready = true
    }
    fun changeTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply() }
    fun home() {
        flushPosition()
        route = "home"; selecting = false; draft = null; opened = null; chapter = null
        viewModelScope.launch { pageLoader.clear() }
    }
    fun openSample() { route = "sample"; japanese = true }
    fun browse() { route = "web" }
    fun managePages() { selecting = false; route = "pages" }
    fun reader() { route = "book" }

    fun importImage(uri: Uri) = importImages(listOf(uri))
    fun importImages(uris: List<Uri>, appendTo: String? = null) = task {
        if (uris.isEmpty()) return@task
        val previous = importing
        val target = previous?.appendTo ?: appendTo
        val before = previous?.pages.orEmpty()
        val returnRoute = previous?.returnRoute ?: route
        val existingBook = target?.let { withContext(Dispatchers.IO) { store.readBook(it) } }
        val limit = LocalBookStore.MAX_PAGES - before.size - (existingBook?.pages?.size ?: 0)
        require(uris.distinct().size <= limit) { "Choose no more than $limit additional images." }
        stageBatch(uris.distinct(), before, returnRoute, target, previous?.title ?: existingBook?.title, previous?.sourceUrl, true) { uri, cancelled -> store.stage(uri, cancelled) }
    }
    fun importWeb(source: WebChapter, selectedUrls: Set<String>, cookies: Map<String, String>, userAgent: String) = task {
        val images = source.images.filter { it.url in selectedUrls }
        require(images.isNotEmpty()) { "Select at least one image." }
        stageBatch(images, emptyList(), "web", null, source.title, source.url, false) { image, cancelled ->
            store.stageStream(image.name, cancelled) { WebImageDownload().open(image.url, source.url, userAgent, cookies[image.url], cancelled) }
        }
    }
    private suspend fun <T> stageBatch(inputs: List<T>, before: List<StagedPage>, returnRoute: String, appendTo: String?, title: String?, url: String?, sort: Boolean,
        load: (T, () -> Unit) -> StagedPage) {
        val created = mutableListOf<StagedPage>()
        val failures = mutableListOf<String>()
        importProgress = ImportProgress(0, inputs.size)
        try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                var bytes = before.sumOf { page -> page.folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
                for ((index, input) in inputs.withIndex()) {
                    context.ensureActive()
                    try {
                        val page = load(input) { context.ensureActive() }
                        val size = page.folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        if (bytes + size > 512L * 1024 * 1024) {
                            store.discard(listOf(page))
                            for (remaining in index until inputs.size) failures += "Image ${remaining + 1}: The 512 MB batch limit was reached. Add this image in another batch."
                            break
                        }
                        created += page; bytes += size
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { failures += "Image ${index + 1}: ${error.message ?: "Could not read this image."}" }
                    withContext(Dispatchers.Main) { importProgress = ImportProgress(index + 1, inputs.size) }
                }
            }
            val pages = before + if (sort) created.sortedWith { a, b -> NaturalOrder.compare(a.page.name, b.page.name) } else created
            importing = ChapterImport(title ?: pages.firstOrNull()?.page?.name?.substringBeforeLast('.') ?: "New chapter", pages, appendTo, url, returnRoute, failures)
            route = "import"
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { store.discard(created) }
            message = "Import cancelled"
            throw error
        } finally { importProgress = null }
    }
    fun cancelImportWork() { if (importProgress != null) operation?.cancel() }
    fun renameImport(title: String) { importing = importing?.copy(title = title.take(120)) }
    fun moveImport(id: String, delta: Int) {
        importing = importing?.let { current ->
            val pages = current.pages.toMutableList(); val index = pages.indexOfFirst { it.page.id == id }
            val target = index + delta
            if (index in pages.indices && target in pages.indices) pages.add(target, pages.removeAt(index))
            current.copy(pages = pages)
        }
    }
    fun sortImport() { importing = importing?.let { it.copy(pages = it.pages.sortedWith { a, b -> NaturalOrder.compare(a.page.name, b.page.name) }) } }
    fun dropImport(id: String) {
        val pages = importing?.pages.orEmpty().filter { it.page.id == id }
        importing = importing?.let { it.copy(pages = it.pages.filterNot { page -> page.page.id == id }) }
        viewModelScope.launch(Dispatchers.IO) { store.discard(pages) }
    }
    fun discardImport() {
        val current = importing ?: return
        importing = null; route = current.returnRoute
        viewModelScope.launch(Dispatchers.IO) { store.discard(current.pages) }
    }
    fun saveImport() = task {
        val current = importing ?: return@task
        val book = withContext(Dispatchers.IO) { store.commitChapter(current.title, current.pages, current.appendTo, current.sourceUrl) }
        importing = null
        books = withContext(Dispatchers.IO) { store.list() }
        setChapter(book)
        message = "${current.pages.size} ${if (current.pages.size == 1) "image" else "images"} added to the chapter"
    }
    fun openBook(book: LocalBook) = task { setChapter(withContext(Dispatchers.IO) { store.readBook(book.id) }) }
    private suspend fun setChapter(book: LocalBook) {
        pageLoader.clear()
        chapter = book; opened = null
        readingPosition = withContext(Dispatchers.IO) { store.progress(book) }
        pageRevision++; route = "book"; japanese = true; selecting = false
    }
    fun updateChapter(title: String, pageIds: List<String>) = task {
        val book = chapter ?: return@task
        val updated = withContext(Dispatchers.IO) { store.updateChapter(book.id, title, pageIds) }
        books = withContext(Dispatchers.IO) { store.list() }
        setChapter(updated)
        message = "Chapter updated"
    }
    fun rememberPosition(pageId: String, offset: Int) {
        val book = chapter ?: return
        val position = ReadingPosition(pageId, offset)
        if (readingPosition == position) return
        readingPosition = position
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(350)
            withContext(Dispatchers.IO) { store.saveProgress(book.id, position) }
        }
    }
    fun flushPosition() {
        val book = chapter ?: return
        val position = readingPosition ?: return
        progressJob?.cancel()
        progressJob = viewModelScope.launch(Dispatchers.IO) { store.saveProgress(book.id, position) }
    }
    fun removeBook(book: LocalBook) = task {
        withContext(Dispatchers.IO) { store.removeBook(book.id) }
        pageLoader.clear()
        books = books.filterNot { it.id == book.id }; home(); message = "Chapter removed from the library"
    }
    fun selectBubble(x: Int, y: Int, page: OpenBook? = opened) = task {
        val book = page ?: return@task
        val existing = book.edits.findLast { it.region.contains(x, y) }
        val region = if (existing != null) existing.region else when (val result = withContext(Dispatchers.Default) { BubbleSelector.select(book.original, x, y) }) {
            is SelectionResult.Selected -> result.region
            is SelectionResult.Rejected -> { message = result.reason; return@task }
        }
        val edit = existing ?: BubbleEdit(region = region, japanese = "", margin = maxOf(4, minOf(region.width, region.height) / 14))
        val crop = Bitmap.createBitmap(book.original, region.left, region.top, region.width, region.height)
        opened = book
        draft = EditorDraft(edit, crop, region.eraseMask.copyOf(), existing != null)
        selecting = false; route = "editor"
    }
    fun changeText(value: String) { draft = draft?.let { it.copy(edit = it.edit.copy(japanese = value.take(512)), preview = null, previewError = null, revision = it.revision + 1) } }
    fun changeMargin(value: Int) { draft = draft?.let { it.copy(edit = it.edit.copy(margin = value), preview = null, previewError = null, revision = it.revision + 1) } }
    fun resetMask() { draft = draft?.let { it.copy(edit = it.edit.copy(region = it.edit.region.copy(eraseMask = it.initialMask.copyOf())), preview = null, previewError = null, revision = it.revision + 1) } }
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
        draft = current.copy(edit = current.edit.copy(region = region.copy(eraseMask = bits)), preview = null, previewError = null, revision = current.revision + 1)
    }
    fun preview() = task {
        val current = draft ?: return@task
        val source = opened?.original ?: return@task
        draft = current.copy(previewError = null)
        val result = withContext(Dispatchers.Default) { BubbleEditRenderer.preview(source, current.edit) }
        // A cancelled editor or newer text must never receive a stale rendering result.
        val latest = draft ?: return@task
        if (route != "editor" || latest.edit.id != current.edit.id || latest.revision != current.revision) return@task
        draft = if (result.isSuccess) latest.copy(preview = result.getOrThrow(), previewVersion = latest.previewVersion + 1)
        else latest.copy(preview = null, previewError = result.exceptionOrNull()?.message ?: "Could not fit this text. Try shorter dialogue or less padding.")
    }
    fun saveEdit() = task {
        val current = draft ?: return@task
        if (current.preview == null) return@task
        val book = opened ?: return@task
        withContext(Dispatchers.IO) { store.saveEdit(book.book.id, book.original, current.edit, book.page.id) }
        pageLoader.clear(); pageRevision++
        opened = null; draft = null; route = "book"; japanese = true
        message = "Bubble saved"
    }
    fun removeEdit() = task {
        val current = draft ?: return@task
        val book = opened ?: return@task
        withContext(Dispatchers.IO) { store.removeEdit(book.book.id, current.edit.id, book.page.id) }
        pageLoader.clear(); pageRevision++
        opened = null; draft = null; route = "book"; message = "Original bubble restored"
    }
    fun cancelEditor() { draft = null; opened = null; route = "book" }
    fun studySample(id: String) {
        val line = SampleChapter.lines.first { it.id == id }
        lesson = SavedLine("sample:$id", line.japanese, "Before the rain", id)
    }
    fun studyEdit(edit: BubbleEdit, page: OpenBook? = opened) {
        lesson = SavedLine("${page?.book?.id}:${page?.page?.id}:${edit.id}:${edit.japanese.hashCode()}", edit.japanese, page?.book?.title ?: "Imported chapter")
    }
    fun toggleSaved(line: SavedLine) = task { saved = withContext(Dispatchers.IO) { store.toggleSaved(line) } }
    private fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        operation = viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "Something went wrong. Please try again." }
            finally { busy = false }
        }
    }
}
