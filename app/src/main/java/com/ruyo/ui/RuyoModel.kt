package com.ruyo.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.PointF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ruyo.data.*
import com.ruyo.ai.*
import com.ruyo.importer.NaturalOrder
import com.ruyo.importer.DocumentImporter
import com.ruyo.reader.*
import com.ruyo.sample.SampleChapter
import com.ruyo.sample.SamplePage
import com.ruyo.web.WebChapter
import com.ruyo.web.WebImageDownload
import com.ruyo.web.WebReadingSession
import com.ruyo.web.LiveChapter
import kotlinx.coroutines.*
import kotlin.math.ceil
import kotlin.math.hypot

data class EditorDraft(val edit: BubbleEdit, val crop: Bitmap, val initialMask: BooleanArray, val existing: Boolean = false,
    val sourceText: String = "", val preview: BubblePreview? = null, val revision: Int = 0, val previewVersion: Int = 0, val previewError: String? = null)
data class AreaSelection(val region: BubbleRegion, val crop: Bitmap, val centers: List<Point>, val x: Int, val y: Int, val error: String? = null)

data class ChapterImport(val title: String, val pages: List<StagedPage>, val appendTo: String? = null, val sourceUrl: String? = null,
    val returnRoute: String = "home", val failures: List<String> = emptyList(), val seriesId: String? = null, val translateBeforeReading: Boolean = false)
data class ImportProgress(val done: Int, val total: Int)

class RuyoModel @JvmOverloads constructor(application: Application,
    private val profileStore: ProfileStore = EncryptedProfileStore(application),
    private val ocrService: OcrService = BubbleOcr(),
    private val translationService: TranslationService = TranslationClient(),
    private val webSessionFactory: (Application, WebChapter, Map<String, String>, String) -> WebReadingSession = { app, source, cookies, agent -> WebReadingSession(app, source, cookies, agent) },
    private val explanationService: ExplanationService = ExplanationClient(),
) : AndroidViewModel(application) {
    val store = LocalBookStore(application)
    val seriesStore = SeriesStore(application)
    var series by mutableStateOf<List<ComicSeries>>(emptyList()); private set
    var activeSeriesId by mutableStateOf<String?>(null); private set
    val activeSeries get() = series.firstOrNull { it.id == activeSeriesId }
    var preparationRunning by mutableStateOf(false); private set
    var preparationStatus by mutableStateOf("Ready to prepare this chapter"); private set
    var preparationError by mutableStateOf<String?>(null); private set
    var preparationReports by mutableStateOf<Map<String, PagePreparation>>(emptyMap()); private set
    private var preparationJob: Job? = null
    private var prepareAfterTask = false
    private val preparationKey get() = "cleanup-v2:" + targetLanguage + ":" + ocrScript.name
    fun seriesFor(bookId: String) = series.firstOrNull { bookId in it.chapters }
    fun orderedChapters(seriesId: String): List<LocalBook> = series.firstOrNull { it.id == seriesId }?.chapters.orEmpty().mapNotNull { id -> books.firstOrNull { it.id == id } }
    fun neighbor(delta: Int): LocalBook? {
        val current = chapter ?: return null
        val group = seriesFor(current.id) ?: return null
        val ordered = orderedChapters(group.id)
        val index = ordered.indexOfFirst { it.id == current.id }
        return if (index < 0) null else ordered.getOrNull(index + delta)
    }
    fun openSeries(id: String) { activeSeriesId = id; route = "series" }
    fun createSeries(title: String, forImport: Boolean = false) = task {
        val created = withContext(Dispatchers.IO) { seriesStore.create(title) }
        series = withContext(Dispatchers.IO) { seriesStore.list() }
        if (forImport) importing = importing?.copy(seriesId = created.id)
        else { activeSeriesId = created.id; route = "series" }
    }
    fun renameSeries(title: String) = task {
        val id = activeSeriesId ?: return@task
        withContext(Dispatchers.IO) { seriesStore.rename(id, title) }
        series = withContext(Dispatchers.IO) { seriesStore.list() }
    }
    fun removeSeries() = task {
        val id = activeSeriesId ?: return@task
        withContext(Dispatchers.IO) { seriesStore.remove(id) }
        series = withContext(Dispatchers.IO) { seriesStore.list() }; home()
    }
    fun moveSeriesChapter(bookId: String, delta: Int) = task {
        val group = activeSeries ?: return@task
        val ids = group.chapters.toMutableList(); val index = ids.indexOf(bookId); val target = index + delta
        if (index >= 0 && target in ids.indices) {
            ids.add(target, ids.removeAt(index))
            withContext(Dispatchers.IO) { seriesStore.reorder(group.id, ids) }
            series = withContext(Dispatchers.IO) { seriesStore.list() }
        }
    }
    fun leaveReader() {
        flushPosition(); closeLesson(); pauseScrolling(); pausePreparation()
        val group = chapter?.let { seriesFor(it.id) }
        if (group != null) openSeries(group.id) else home()
    }
    fun setImportSeries(id: String?) { importing = importing?.copy(seriesId = id) }
    fun setImportTranslation(value: Boolean) { importing = importing?.copy(translateBeforeReading = value) }
    fun pausePreparation(): Job? = preparationJob?.also { it.cancel() }
    fun finishPreparation() = task { pausePreparation()?.join(); pageLoader.clear(); pageRevision++; route = "book" }
    fun reviewPreparedPage(pageId: String) = task {
        pausePreparation()?.join(); pageLoader.clear()
        readingPosition = ReadingPosition(pageId, 0); pageRevision++; selecting = true; route = "book"
    }
    fun startPreparation() {
        prepareChapter(retrySkipped = false)
    }
    fun retrySkippedPreparation() {
        prepareChapter(retrySkipped = true)
    }
    private fun prepareChapter(retrySkipped: Boolean) {
        val book = chapter ?: return
        if (busy || preparationJob?.isCompleted == false) return
        if (activeProfile == null) { editProfiles(); return }
        val settings = TranslationSettings(requireNotNull(activeProfileId), targetLanguage, ocrScript)
        val key = preparationKey
        val pipeline = PageTranslationPipeline(store, ocrService, profileStore, translationService)
        preparationRunning = true; preparationError = null; route = "prepare"
        preparationJob = viewModelScope.launch {
            try {
                scrollTranslation?.pause()?.join(); pageLoader.clear()
                preparationReports = withContext(Dispatchers.IO) { store.preparation(book, key) }
                for ((index, metadata) in book.pages.withIndex()) {
                    ensureActive()
                    val previous = preparationReports[metadata.id]
                    if (previous != null && (!retrySkipped || !previous.needsReview)) continue
                    preparationStatus = "Page ${index + 1} of ${book.pages.size} · Recognizing dialogue…"
                    val page = pageLoader.load(book, metadata)
                    val coroutine = currentCoroutineContext()
                    val report = pipeline.prepare(page, settings, { coroutine.isActive }, { detail -> preparationStatus = "Page ${index + 1} of ${book.pages.size} · " + detail }) {
                        pageLoader.clear(); pageRevision++
                    } ?: continue
                    ensureActive()
                    withContext(Dispatchers.IO) { store.recordPreparation(book, key, metadata.id, report) }
                    preparationReports = preparationReports + (metadata.id to report)
                    pageLoader.clear()
                }
                preparationStatus = "Chapter preparation finished"
            } catch (_: TimeoutCancellationException) {
                preparationError = "The provider timed out. Resume to continue with the unfinished page."
            } catch (cancelled: CancellationException) {
                preparationStatus = "Preparation paused. Completed translations are saved."
                throw cancelled
            } catch (error: Exception) {
                preparationError = error.message ?: "Could not prepare this chapter. Completed translations are saved."
            } finally { preparationRunning = false }
        }
    }
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
    var areaSelection by mutableStateOf<AreaSelection?>(null); private set
    var importing by mutableStateOf<ChapterImport?>(null); private set
    var importProgress by mutableStateOf<ImportProgress?>(null); private set
    var lesson by mutableStateOf<SavedLine?>(null); private set
    var explanation by mutableStateOf<AiLesson?>(null); private set
    var explanationBusy by mutableStateOf(false); private set
    var explanationError by mutableStateOf<String?>(null); private set
    var lessonEditable by mutableStateOf(false); private set
    private var lessonEdit: (() -> Unit)? = null
    private var lessonOperation: Job? = null
    private var lessonGeneration = 0L
    private val lessonCache = LessonCache(application)
    var busy by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var selecting by mutableStateOf(false)
    var selectionError by mutableStateOf<String?>(null); private set
    var japanese by mutableStateOf(true)
    var webHostActive by mutableStateOf(false); private set
    var foreground by mutableStateOf(true); private set
    var readerImmersive by mutableStateOf(false)
    var webLoadStatus by mutableStateOf("Watching for more chapter images…"); private set
    var webVisible by mutableStateOf<List<Int>>(emptyList()); private set
    var webFraction by mutableStateOf(0f); private set
    fun appForeground(value: Boolean) { foreground = value; if (!value) { pauseScrolling(); pausePreparation(); cancelExplanation() } }
    fun webViewport(indices: List<Int>, fraction: Float) { webVisible = indices; webFraction = fraction }
    suspend fun acceptWebDiscovery(sessionId: String, source: WebChapter, cookies: Map<String, String>) {
        val session = webReading ?: return
        if (session.id != sessionId || route != "webread" || source.url != session.chapter.url) return
        session.updateCookies(cookies)
        val merged = LiveChapter.merge(session.images, source.images)
        if (merged == session.images) return
        val appendOnly = merged.take(session.images.size) == session.images
        val resume = scrollTranslation?.running == true
        val anchor = session.images.getOrNull(webPosition.first)?.url
        if (!appendOnly) { scrollTranslation?.pause()?.join(); scrollTranslation?.reset() }
        if (webReading !== session || route != "webread") return
        session.updateImages(merged)
        if (!appendOnly && anchor != null) webPosition = merged.indexOfFirst { it.url == anchor }.coerceAtLeast(0) to webPosition.second
        scrollTranslation?.resize(merged.size)
        if (!appendOnly && resume) scrollTranslation?.start()
        webLoadStatus = "More chapter images loaded"
    }
    fun webLoadingMessage(value: String) { webLoadStatus = value }
    var webUrl by mutableStateOf("")
    var webAddressExpanded by mutableStateOf(false)
    var targetLanguage by mutableStateOf(runCatching { TextLanguages.normalize(prefs.getString("targetLanguage", "ja") ?: "ja") }.getOrDefault("ja")); private set

    var profiles by mutableStateOf<List<ProviderProfile>>(emptyList()); private set
    var activeProfileId by mutableStateOf(prefs.getString("activeProfile", null)); private set
    val activeProfile get() = profiles.firstOrNull { it.id == activeProfileId }
    var profileError by mutableStateOf<String?>(null); private set
    var aiStatus by mutableStateOf<String?>(null); private set
    var aiError by mutableStateOf<String?>(null); private set
    var ocrScript by mutableStateOf(runCatching { OcrScript.valueOf(prefs.getString("ocrScript", "LATIN") ?: "LATIN") }.getOrDefault(OcrScript.LATIN)); private set
    private var profileReturnRoute = "home"
    private var aiOperation: Job? = null
    private var aiGeneration = 0L
    private var automaticEditorId: String? = null
    var webReading by mutableStateOf<WebReadingSession?>(null); private set
    var webPosition by mutableStateOf(0 to 0); private set
    var scrollTranslation by mutableStateOf<ScrollTranslation?>(null); private set
    private var scrollSettings: TranslationSettings? = null
    private var editorWebIndex: Int? = null
    private val editorStore get() = if (editorWebIndex != null) requireNotNull(webReading).store else store
    private val editorReturnRoute get() = if (editorWebIndex != null) "webread" else "book"

    fun readWebsite(source: WebChapter, cookies: Map<String, String>, userAgent: String) = task {
        scrollTranslation?.pause()?.join()
        val previous = webReading
        if (previous != null && previous.chapter.url == source.url) {
            val anchor = previous.images.getOrNull(webPosition.first)?.url
            previous.updateCookies(cookies)
            previous.updateImages(LiveChapter.merge(previous.images, source.images))
            if (anchor != null) webPosition = previous.images.indexOfFirst { it.url == anchor }.coerceAtLeast(0) to webPosition.second
            route = "webread"; readerImmersive = false; japanese = true; selectionError = null
            configureScroll(previous.store, previous.images.size, { previous.load(it) }, { previous.changed() })
            return@task
        }
        webReading?.close()
        withContext(Dispatchers.IO) { WebReadingSession.clearAbandoned(getApplication()) }
        val session = webSessionFactory(getApplication(), source, cookies, userAgent)
        webReading = session; webPosition = 0 to 0; editorWebIndex = null
        webLoadStatus = "Watching for more chapter images…"
        route = "webread"; japanese = true; selectionError = null
        configureScroll(session.store, source.images.size, { session.load(it) }, { session.changed() })
    }
    fun clearWebsiteSession() = task {
        scrollTranslation?.pause()?.join()
        webReading?.close(); webReading = null; scrollTranslation = null; webPosition = 0 to 0
    }
    fun rememberWebPosition(index: Int, offset: Int) { webPosition = index to offset }
    fun returnToWebsite() { pauseScrolling(); route = "web"; selecting = false; selectionError = null }
    fun pauseScrolling() { scrollTranslation?.pause() }
    fun startScrolling() {
        if (busy) return
        if (activeProfile == null) { editProfiles(); return }
        scrollSettings = TranslationSettings(requireNotNull(activeProfileId), targetLanguage, ocrScript)
        japanese = true; selecting = false
        scrollTranslation?.start()
    }
    private fun configureScroll(storage: LocalBookStore, count: Int, load: suspend (Int) -> OpenBook, invalidate: suspend () -> Unit) {
        val pipeline = PageTranslationPipeline(storage, ocrService, profileStore, translationService)
        scrollTranslation = ScrollTranslation(viewModelScope, count) { index, keepGoing, status ->
            val settings = requireNotNull(scrollSettings)
            val page = load(index)
            pipeline.process(page, settings, keepGoing, status) { invalidate(); pageRevision++ }
        }
    }
    fun editWebBubble(index: Int, x: Int, y: Int) = task {
        val session = webReading ?: return@task
        scrollTranslation?.pause()?.join()
        session.changed()
        val page = session.load(index)
        editorWebIndex = index
        selectBubbleInPage(page, x, y)
    }

    init { refresh() }
    fun refresh() = task {
        val data = withContext(Dispatchers.IO) { store.list() to store.savedLines() }
        books = data.first; saved = data.second
        series = withContext(Dispatchers.IO) { seriesStore.list() }
        if (samples.isEmpty()) samples = withContext(Dispatchers.Default) { SampleChapter.build() }
        loadProfiles()
        ready = true
    }
    fun changeTheme(value: String) { theme = value; prefs.edit().putString("theme", value).apply() }
    fun home() {
        closeLesson(); cancelAi(); pauseScrolling(); pausePreparation(); activeSeriesId = null; webHostActive = false; readerImmersive = false; areaSelection = null; editorWebIndex = null
        flushPosition()
        route = "home"; selecting = false; draft = null; opened = null; chapter = null
        viewModelScope.launch { pageLoader.clear() }
    }
    fun changeTargetLanguage(value: String) { pausePreparation(); scrollTranslation?.reset(); targetLanguage = TextLanguages.normalize(value); prefs.edit().putString("targetLanguage", targetLanguage).apply() }
    fun openSample() { route = "sample"; japanese = true }
    fun browse() { pauseScrolling(); webHostActive = true; route = "web" }
    fun managePages() { pauseScrolling(); selecting = false; route = "pages" }
    fun reader() { route = "book" }

    fun importImage(uri: Uri) = importImages(listOf(uri))
    fun importImages(uris: List<Uri>, appendTo: String? = null) = importFiles(uris, appendTo)
    fun importFiles(uris: List<Uri>, appendTo: String? = null) = task {
        if (uris.isEmpty()) return@task
        val previous = importing
        val target = previous?.appendTo ?: appendTo
        val before = previous?.pages.orEmpty()
        val returnRoute = previous?.returnRoute ?: route
        val existingBook = target?.let { withContext(Dispatchers.IO) { store.readBook(it) } }
        val limit = LocalBookStore.MAX_PAGES - before.size - (existingBook?.pages?.size ?: 0)
        require(uris.distinct().size <= limit) { "Choose no more than $limit additional images." }
        stageBatch(uris.distinct(), before, returnRoute, target, previous?.title ?: existingBook?.title, previous?.sourceUrl, true) { uri, cancelled, capacity, budget ->
            DocumentImporter(getApplication(), store).stage(uri, capacity, budget, cancelled)
        }
    }
    fun importWeb(source: WebChapter, selectedUrls: Set<String>, cookies: Map<String, String>, userAgent: String) = task {
        val images = source.images.filter { it.url in selectedUrls }
        require(images.isNotEmpty()) { "Select at least one image." }
        stageBatch(images, emptyList(), "web", null, source.title, source.url, false) { image, cancelled, _, _ ->
            listOf(store.stageStream(image.name, cancelled) { WebImageDownload().open(image.url, source.url, userAgent, cookies[image.url], cancelled) })
        }
    }
    private suspend fun <T> stageBatch(inputs: List<T>, before: List<StagedPage>, returnRoute: String, appendTo: String?, title: String?, url: String?, sort: Boolean,
        load: (T, () -> Unit, Int, Long) -> List<StagedPage>) {
        val created = mutableListOf<StagedPage>()
        val failures = mutableListOf<String>()
        importProgress = ImportProgress(0, inputs.size)
        val previous = importing
        val existing = appendTo?.let { withContext(Dispatchers.IO) { store.readBook(it) } }
        try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                var bytes = before.sumOf { page -> page.folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
                for ((index, input) in inputs.withIndex()) {
                    context.ensureActive()
                    try {
                        val pages = load(input, { context.ensureActive() }, LocalBookStore.MAX_PAGES - before.size - created.size - (existing?.pages?.size ?: 0), 512L * 1024 * 1024 - bytes)
                        val size = pages.sumOf { page -> page.folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
                        if (bytes + size > 512L * 1024 * 1024) {
                            store.discard(pages)
                            for (remaining in index until inputs.size) failures += "Image ${remaining + 1}: The 512 MB batch limit was reached. Add this image in another batch."
                            break
                        }
                        created += pages; bytes += size
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { failures += "Image ${index + 1}: ${error.message ?: "Could not read this image."}" }
                    withContext(Dispatchers.Main) { importProgress = ImportProgress(index + 1, inputs.size) }
                }
            }
            val pages = before + if (sort) created.sortedWith { a, b -> NaturalOrder.compare(a.page.name, b.page.name) } else created
            importing = ChapterImport(title ?: pages.firstOrNull()?.page?.name?.substringBeforeLast('.') ?: "New chapter", pages, appendTo, url, returnRoute, failures,
                if (previous != null) previous.seriesId else existing?.let { seriesFor(it.id)?.id } ?: activeSeriesId,
                previous?.translateBeforeReading ?: (activeProfile != null))
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
        val groupResult = withContext(Dispatchers.IO) { runCatching { seriesStore.assign(book.id, current.seriesId) } }
        series = withContext(Dispatchers.IO) { seriesStore.list() }
        setChapter(book)
        prepareAfterTask = current.translateBeforeReading && activeProfile != null
        message = if (groupResult.isFailure) "Chapter saved, but it could not be placed in that series." else if (prepareAfterTask) null else "Chapter saved"

    }
    fun openBook(book: LocalBook) = task { flushPosition(); setChapter(withContext(Dispatchers.IO) { store.readBook(book.id) }) }
    private suspend fun setChapter(book: LocalBook) {
        pausePreparation()?.join()
        scrollTranslation?.pause()?.join()
        editorWebIndex = null
        pageLoader.clear()
        chapter = book; opened = null
        readingPosition = withContext(Dispatchers.IO) { store.progress(book) }
        preparationReports = withContext(Dispatchers.IO) { store.preparation(book, preparationKey) }
        pageRevision++; route = "book"; japanese = true; selecting = false
        configureScroll(store, book.pages.size, { index -> pageLoader.load(book, book.pages[index]) }, { pageLoader.clear() })
    }
    fun updateChapter(title: String, pageIds: List<String>, seriesId: String? = chapter?.let { seriesFor(it.id)?.id }) = task {
        pausePreparation()?.join()
        scrollTranslation?.pause()?.join()
        val book = chapter ?: return@task
        val updated = withContext(Dispatchers.IO) { store.updateChapter(book.id, title, pageIds) }
        books = withContext(Dispatchers.IO) { store.list() }
        withContext(Dispatchers.IO) { seriesStore.assign(updated.id, seriesId) }
        series = withContext(Dispatchers.IO) { seriesStore.list() }
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
        pausePreparation()?.join()
        scrollTranslation?.pause()?.join()
        withContext(Dispatchers.IO) { store.removeBook(book.id); seriesStore.removeChapter(book.id) }
        series = withContext(Dispatchers.IO) { seriesStore.list() }
        pageLoader.clear()
        books = books.filterNot { it.id == book.id }; home(); message = "Chapter removed from the library"
    }
    fun selectBubble(x: Int, y: Int, page: OpenBook? = opened) = task {
        val book = page ?: return@task
        pausePreparation()?.join()
        scrollTranslation?.pause()?.join()
        editorWebIndex = null
        // A completed background swap may have been saved during cancellation.
        pageLoader.clear()
        selectBubbleInPage(pageLoader.load(book.book, book.page), x, y)
    }
    private suspend fun selectBubbleInPage(book: OpenBook, x: Int, y: Int) {
        selectionError = null
        val existing = book.edits.findLast { it.region.contains(x, y) }
        val region = if (existing != null) existing.region else when (val result = withContext(Dispatchers.Default) { BubbleSelector.select(book.original, x, y) }) {
            is SelectionResult.Selected -> result.region
            is SelectionResult.Rejected -> {
                val lines = try { withContext(Dispatchers.Default) { ocrService.lines(book.original, ocrScript) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
                val fallback = withContext(Dispatchers.Default) {
                    TextRegionRepair.groups(lines).firstOrNull { group -> group.any { line ->
                        val halo = maxOf(4, (line.bottom - line.top) / 3)
                        x in (line.left - halo)..(line.right + halo) && y in (line.top - halo)..(line.bottom + halo)
                    } }?.let { TextRegionRepair.select(book.original, it, lines) }
                }
                if (fallback == null || book.edits.any { it.region.overlaps(fallback) }) {
                    selectionError = "Could not isolate the lettering on a smooth background. Check Original text in reader settings, then tap the lettering. Detailed artwork still needs manual cleanup."
                    return
                }
                fallback
            }
        }
        opened = book
        areaSelection = null
        if (existing != null) { openEditor(book, region, existing); return }
        val centers = withContext(Dispatchers.IO) { editorStore.areaCenters(book, region) }
        val suggested = centers ?: withContext(Dispatchers.Default) { BubbleAreas.suggest(region) }
        areaSelection = AreaSelection(region, Bitmap.createBitmap(book.original, region.left, region.top, region.width, region.height), suggested, x, y)
        if (centers == null && suggested.size > 1) withContext(Dispatchers.IO) { editorStore.saveAreas(book, region, suggested) }
        val selected = withContext(Dispatchers.Default) { BubbleAreas.split(region, suggested).first { it.contains(x, y) } }
        openEditor(book, selected)
    }
    private suspend fun openEditor(book: OpenBook, region: BubbleRegion, existing: BubbleEdit? = null) {
        cancelAi(); aiError = null
        val sourceHeight = existing?.sourceLetterHeight ?: withContext(Dispatchers.Default) { SourceLettering.estimateHeight(region) }
        val edit = existing ?: BubbleEdit(region = region, japanese = "", margin = maxOf(2, minOf(region.width, region.height) / 14),
            languageTag = targetLanguage, sourceLetterHeight = sourceHeight, matchSourceSize = sourceHeight != null)
        val crop = Bitmap.createBitmap(book.original, region.left, region.top, region.width, region.height)
        opened = book
        draft = EditorDraft(edit, crop, region.eraseMask.copyOf(), existing != null)
        selecting = false; route = "editor"
        automaticEditorId = edit.id
    }
    fun editAreas() {
        val area = areaSelection ?: return
        val book = opened ?: return
        if (book.edits.any { edit ->
            val r = edit.region
            (0 until r.width * r.height).any { r.interior[it % r.width, it / r.width] && area.region.contains(r.left + it % r.width, r.top + it / r.width) }
        }) { message = "Restore the translations in this joined bubble before changing its areas."; return }
        if (draft?.edit?.japanese?.isNotBlank() == true) { message = "Save or clear this translation before changing its area."; return }
        cancelAi()
        route = "areas"
    }
    fun setAreaCenters(centers: List<Point>) { areaSelection = areaSelection?.copy(centers = centers, error = null) }
    fun cancelAreas() { if (draft != null) route = "editor" else cancelEditor() }
    fun applyAreas() = task {
        val area = areaSelection ?: return@task
        val book = opened ?: return@task
        val parts = withContext(Dispatchers.Default) { runCatching { BubbleAreas.split(area.region, area.centers) } }
        if (parts.isFailure) { areaSelection = area.copy(error = parts.exceptionOrNull()?.message); return@task }
        withContext(Dispatchers.IO) { editorStore.saveAreas(book, area.region, area.centers) }
        val selected = parts.getOrThrow().firstOrNull { it.contains(area.x, area.y) } ?: parts.getOrThrow().first()
        openEditor(book, selected)
        message = if (parts.getOrThrow().size > 1) "Areas saved. Tap each part in the reader to edit it independently." else "Using one text area"
    }
    fun toggleSelection() { pauseScrolling(); selectionError = null; selecting = !selecting }
    fun changeText(value: String) { cancelAi(); draft = draft?.let { it.copy(edit = it.edit.copy(japanese = value.take(512)), preview = null, previewError = null, revision = it.revision + 1) } }
    fun changeEditLanguage(value: String) { updateLettering { it.copy(languageTag = TextLanguages.normalize(value)) } }
    fun changeFont(value: String) { updateLettering { it.copy(fontFamily = LetteringFont.fromId(value).family) } }
    fun changeBold(value: Boolean) { updateLettering { it.copy(bold = value) } }
    fun changeItalic(value: Boolean) { updateLettering { it.copy(italic = value) } }
    fun changeMatchSource(value: Boolean) { updateLettering { it.copy(matchSourceSize = value && it.sourceLetterHeight != null) } }
    private fun updateLettering(change: (BubbleEdit) -> BubbleEdit) {
        if (busy) return
        cancelAi()
        draft = draft?.let { it.copy(edit = change(it.edit), preview = null, previewError = null, revision = it.revision + 1) }
        if (draft?.edit?.japanese?.isNotBlank() == true) preview()
    }
    fun changeFontScale(value: Float) { cancelAi(); draft = draft?.let { it.copy(edit = it.edit.copy(fontScale = value.coerceIn(0.6f, 1.6f)), preview = null, previewError = null, revision = it.revision + 1) } }
    fun changeMargin(value: Int) { cancelAi(); draft = draft?.let { it.copy(edit = it.edit.copy(margin = value), preview = null, previewError = null, revision = it.revision + 1) } }
    fun resetMask() { cancelAi(); draft = draft?.let { it.copy(edit = it.edit.copy(region = it.edit.region.copy(eraseMask = it.initialMask.copyOf())), preview = null, previewError = null, revision = it.revision + 1) } }
    fun brush(points: List<PointF>, radius: Float, add: Boolean) {
        cancelAi()
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
        if (aiStatus != null) return@task
        val current = draft ?: return@task
        if (current.preview == null) return@task
        val book = opened ?: return@task
        withContext(Dispatchers.IO) { editorStore.saveEdit(book.book.id, book.original, current.edit, book.page.id) }
        finishEditor("Bubble saved")
    }
    fun removeEdit() = task {
        val current = draft ?: return@task
        val book = opened ?: return@task
        withContext(Dispatchers.IO) { editorStore.removeEdit(book.book.id, current.edit.id, book.page.id) }
        finishEditor("Original bubble restored")
    }
    private suspend fun finishEditor(notice: String) {
        if (editorWebIndex != null) webReading?.changed() else pageLoader.clear()
        pageRevision++
        val index = editorWebIndex ?: chapter?.pages?.indexOfFirst { it.id == opened?.page?.id }
        if (index != null && index >= 0) scrollTranslation?.invalidate(index)
        opened = null; draft = null; areaSelection = null
        route = editorReturnRoute; editorWebIndex = null; japanese = true; message = notice
    }
    fun cancelEditor() {
        cancelAi(); aiError = null; areaSelection = null; draft = null; opened = null
        route = editorReturnRoute; editorWebIndex = null
    }
    fun studySample(id: String) {
        val line = SampleChapter.lines.first { it.id == id }
        showLesson(SavedLine("sample:$id", line.japanese, "Before the rain", id))
    }
    fun showLesson(line: SavedLine) {
        closeLesson(); lesson = line
    }
    fun closeLesson() {
        cancelExplanation(); lesson = null; explanation = null; explanationError = null
        lessonEdit = null; lessonEditable = false
    }
    fun studyEdit(edit: BubbleEdit, page: OpenBook? = opened, webIndex: Int? = null) {
        showLesson(SavedLine("${page?.book?.id}:${page?.page?.id}:${edit.id}:${edit.japanese.hashCode()}", edit.japanese, page?.book?.title ?: "Imported chapter", languageTag = edit.languageTag))
        if (page != null) {
            val sessionId = webReading?.id
            val imageUrl = webIndex?.let { webReading?.images?.getOrNull(it)?.url }
            lessonEditable = true
            lessonEdit = { task {
                scrollTranslation?.pause()?.join()
                val fresh = if (webIndex != null) {
                    val session = webReading ?: return@task
                    require(session.id == sessionId) { "This reading session has ended." }
                    val index = session.images.indexOfFirst { it.url == imageUrl }
                    require(index >= 0) { "This image is no longer available." }
                    session.changed(); editorWebIndex = index; session.load(index)
                } else { editorWebIndex = null; pageLoader.clear(); pageLoader.load(page.book, page.page) }
                val current = fresh.edits.firstOrNull { it.id == edit.id }
                require(current != null) { "This translation has been removed." }
                openEditor(fresh, current.region, current)
            } }
        }
    }
    fun editLesson() { val action = lessonEdit ?: return; closeLesson(); action() }
    fun cancelExplanation() {
        lessonGeneration++; lessonOperation?.cancel(); lessonOperation = null; explanationBusy = false
    }
    fun explainLesson() {
        val line = lesson ?: return
        if (line.sampleId != null || explanationBusy || explanation != null) return
        cancelExplanation(); explanationError = null
        val profile = activeProfile
        if (profile == null) { explanationError = "Choose an AI provider to explain this dialogue."; return }
        val generation = lessonGeneration
        explanationBusy = true
        lessonOperation = viewModelScope.launch {
            try {
                val key = lessonCache.key(line.japanese, line.languageTag, profile)
                val cached = withContext(Dispatchers.IO) { lessonCache.read(key) }
                val value = cached ?: run {
                    val secret = withContext(Dispatchers.IO) { profileStore.get(profile.id) }
                    explanationService.explain(secret, line.japanese, line.languageTag)
                }
                ensureActive()
                if (generation != lessonGeneration || lesson != line || activeProfileId != profile.id) return@launch
                explanation = value
                if (cached == null) withContext(Dispatchers.IO) { runCatching { lessonCache.write(key, value) } }
            } catch (_: TimeoutCancellationException) {
                if (generation == lessonGeneration) explanationError = "The provider took too long. Retry or choose a faster model."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == lessonGeneration) explanationError = (error as? TranslationFailure)?.message ?: "Could not prepare this lesson. Check your provider and try again."
            } finally { if (generation == lessonGeneration) { explanationBusy = false; lessonOperation = null } }
        }
    }
    fun toggleSaved(line: SavedLine) = task { saved = withContext(Dispatchers.IO) { store.toggleSaved(line) } }
    private suspend fun loadProfiles() {
        try {
            profiles = withContext(Dispatchers.IO) { profileStore.list() }
            profileError = null
            if (profiles.none { it.id == activeProfileId }) selectProfile(profiles.firstOrNull()?.id)
        } catch (error: Exception) { profileError = error.message ?: "Could not open the encrypted provider store." }
    }
    fun editProfiles() { closeLesson(); cancelAi(); pauseScrolling(); pausePreparation(); profileReturnRoute = route; route = "profiles" }
    fun closeProfiles() { route = profileReturnRoute; if (route == "editor") prepareEditorAutomatically() }
    fun selectProfile(id: String?) {
        pausePreparation(); cancelAi(); cancelExplanation(); explanation = null; scrollTranslation?.reset(); activeProfileId = id
        prefs.edit().putString("activeProfile", id).apply()
    }
    fun saveProfile(profile: ProviderProfile, replacementKey: String?, removeKey: Boolean, onSaved: () -> Unit) = task {
        try {
            withContext(Dispatchers.IO) { profileStore.save(profile, replacementKey, removeKey) }
            loadProfiles(); selectProfile(profile.id); onSaved()
            message = "Provider saved securely on this device"
        } catch (error: Exception) { profileError = error.message ?: "Could not save the provider." }
    }
    fun deleteProfile(id: String) = task {
        cancelAi()
        try { withContext(Dispatchers.IO) { profileStore.delete(id) }; loadProfiles() }
        catch (error: Exception) { profileError = error.message ?: "Could not remove this provider." }
    }
    fun changeSourceText(value: String) {
        cancelAi()
        if (value.length > 2000) { aiError = "Use no more than 2000 source characters per area."; return }
        aiError = null; draft = draft?.copy(sourceText = value)
    }
    fun changeOcrScript(value: OcrScript) {
        pausePreparation(); cancelAi(); scrollTranslation?.reset(); ocrScript = value; prefs.edit().putString("ocrScript", value.name).apply()
    }
    fun cancelAi() {
        aiGeneration++; aiOperation?.cancel(); aiOperation = null; aiStatus = null
    }
    fun recognizeText() = startAi(true)
    fun translateText() = startAi(false, recognizeFirst = true)
    private fun prepareEditorAutomatically() {
        val current = draft ?: return
        if (route != "editor" || busy || aiStatus != null) return
        if (current.edit.japanese.isNotBlank()) { if (current.preview == null) preview() }
        else if (activeProfile != null) translateText()
    }
    private fun startAi(recognize: Boolean, recognizeFirst: Boolean = false) {
        if (busy || aiStatus != null) return
        val current = draft ?: return
        val source = opened?.original ?: return
        val profileId = activeProfileId
        if (!recognize && (profileId == null || activeProfile == null)) { aiError = "Add or select a provider profile first."; return }
        if (!recognize && !recognizeFirst && current.sourceText.isBlank()) { aiError = "Recognize or enter the original text first."; return }
        val generation = ++aiGeneration
        val script = ocrScript
        aiError = null; aiStatus = if (recognize || recognizeFirst && current.sourceText.isBlank()) "Recognizing text on this device…" else "Translating with " + activeProfile?.name + "…"
        aiOperation = viewModelScope.launch {
            try {
                var input = current.sourceText
                if (!recognize && recognizeFirst && input.isBlank()) {
                    input = withContext(Dispatchers.Default) { ocrService.recognize(source, current.edit.region, script) }
                    require(input.isNotBlank() && input.length <= 2000) { "This area contains too much text. Split it into smaller areas." }
                    ensureActive()
                    val latest = draft
                    if (generation != aiGeneration || route != "editor" || latest?.edit?.id != current.edit.id || latest.revision != current.revision || latest.sourceText != current.sourceText) return@launch
                    draft = latest.copy(sourceText = input)
                    aiStatus = "Translating with " + activeProfile?.name + "…"
                }
                val result = if (recognize) withContext(Dispatchers.Default) { ocrService.recognize(source, current.edit.region, script) }
                else {
                    val secret = withContext(Dispatchers.IO) { profileStore.get(requireNotNull(profileId)) }
                    translationService.translate(secret, input, current.edit.languageTag)
                }
                ensureActive()
                val latest = draft
                if (generation != aiGeneration || route != "editor" || latest?.edit?.id != current.edit.id || latest.revision != current.revision || latest.sourceText != input) return@launch
                if (recognize) {
                    require(result.isNotBlank() && result.length <= 2000) { "This area contains too much text. Split it into smaller areas." }
                    draft = latest.copy(sourceText = result)
                } else {
                    require(result.isNotBlank() && result.length <= 512) { "The translation exceeds 512 characters. It has not been shortened or applied." }
                    draft = latest.copy(edit = latest.edit.copy(japanese = result), preview = null, previewError = null, revision = latest.revision + 1)
                    preview()
                }
            } catch (_: TimeoutCancellationException) {
                if (generation == aiGeneration) aiError = "The request timed out. Retry or choose another profile."
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == aiGeneration) aiError = error.message ?: "Could not translate this area. Please try again."
            } finally { if (generation == aiGeneration) { aiStatus = null; aiOperation = null } }
        }
    }

    private fun task(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        operation = viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "Something went wrong. Please try again." }
            finally {
                busy = false
                val pending = automaticEditorId
                automaticEditorId = null
                if (pending != null && draft?.edit?.id == pending) prepareEditorAutomatically()
                if (prepareAfterTask) { prepareAfterTask = false; startPreparation() }
            }
        }
    }
}
