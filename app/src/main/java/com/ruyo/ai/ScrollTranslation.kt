package com.ruyo.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ruyo.data.LocalBookStore
import com.ruyo.data.OpenBook
import com.ruyo.reader.*
import kotlinx.coroutines.*

data class TranslationSettings(val profileId: String, val language: String, val script: OcrScript)

/** One worker, visible images first, then one image ahead. Viewport changes never restart a paid call. */
class ScrollTranslation(
    private val scope: CoroutineScope,
    private val pageCount: Int,
    private val process: suspend (Int, () -> Boolean, (String) -> Unit) -> String?,
) {
    var running by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var notes by mutableStateOf<Map<Int, String>>(emptyMap()); private set
    private val completed = mutableSetOf<Int>()
    private var window = emptyList<Int>()
    private var job: Job? = null
    private var generation = 0
    fun viewport(visible: List<Int>) {
        val valid = visible.distinct().filter { it in 0 until pageCount }.sorted()
        window = (valid.take(3) + listOfNotNull(valid.lastOrNull()?.plus(1)?.takeIf { it < pageCount })).distinct()
        schedule()
    }
    fun start() { error = null; running = true; schedule() }
    fun pause(): Job? {
        running = false; generation++; status = null
        return job?.also { it.cancel() }
    }
    fun invalidate(index: Int) { completed.remove(index); notes = notes - index }
    private fun schedule() {
        if (!scope.isActive || !running || job?.isActive == true || job?.isCompleted == false) return
        val next = window.firstOrNull { it !in completed } ?: return
        val version = generation
        // LAZY avoids assigning an already-completed coroutine back into job.
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var index: Int? = next
                while (running && version == generation && index != null) {
                    val page = index
                    status = "Reading image " + (page + 1) + "…"
                    val note = process(page, { running && version == generation && page in window }) { status = it }
                    ensureActive()
                    if (version != generation) return@launch
                    if (note != null) { completed += page; notes = notes + (page to note) }
                    index = window.firstOrNull { it !in completed }
                }
            } catch (_: TimeoutCancellationException) {
                if (version == generation) { error = "Translation timed out. Tap Translate to retry."; running = false }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (version == generation) { error = failure.message ?: "Could not translate. Tap Translate to retry."; running = false }
            } finally {
                if (version == generation) status = null
                job = null
                if (running) schedule()
            }
        }
        job = worker; worker.start()
    }
}

/** A successful fit is saved before compositing; unsupported regions keep their source pixels. */
class PageTranslationPipeline(
    private val store: LocalBookStore,
    private val ocr: OcrService,
    private val profiles: ProfileStore,
    private val translate: TranslationService,
) {
    private val unfitted = mutableSetOf<String>()
    suspend fun process(page: OpenBook, settings: TranslationSettings, keepGoing: () -> Boolean, status: (String) -> Unit,
        changed: suspend () -> Unit): String? {
        if (!keepGoing()) return null
        status("Recognizing dialogue on this device…")
        val lines = withContext(Dispatchers.Default) { ocr.lines(page.original, settings.script) }
        val groups = withContext(Dispatchers.Default) {
            AutoBubbleDetector.detect(page.original, lines) { store.areaCenters(page, it) }
        }
        val secret = withContext(Dispatchers.IO) { profiles.get(settings.profileId) }
        val existing = page.edits.toMutableList()
        var skipped = 0; var count = 0
        for (group in groups) {
            currentCoroutineContext().ensureActive()
            if (!keepGoing()) return null
            if (group.centers.size > 1 && existing.none { it.region.overlaps(group.region) }) {
                withContext(Dispatchers.IO) { store.saveAreas(page, group.region, group.centers) }
            }
            for (bubble in group.bubbles) {
                currentCoroutineContext().ensureActive()
                if (!keepGoing()) return null
                if (existing.any { it.region.overlaps(bubble.region) }) continue
                if (existing.size >= 40) { skipped++; continue }
                val attempt = listOf(page.book.id, page.page.id, bubble.region.left, bubble.region.top, bubble.region.width, bubble.region.height,
                    settings.profileId, settings.language, bubble.source).joinToString("|")
                if (attempt in unfitted) { skipped++; continue }
                val sourceHeight = withContext(Dispatchers.Default) { SourceLettering.estimateHeight(bubble.region) }
                status("Translating dialogue " + (count + 1) + "…")
                val text = translate.translate(secret, bubble.source, settings.language)
                currentCoroutineContext().ensureActive()
                require(text.isNotBlank() && text.length <= 512) { "The translation exceeds the area limit. Tap the bubble to edit it." }
                val edit = BubbleEdit(region = bubble.region, japanese = text, margin = maxOf(2, minOf(bubble.region.width, bubble.region.height) / 14),
                    languageTag = settings.language, sourceLetterHeight = sourceHeight, matchSourceSize = sourceHeight != null)
                val fits = withContext(Dispatchers.Default) {
                    BubbleEditRenderer.preview(page.original, edit).fold(onSuccess = { preview ->
                        preview.crop.recycle(); preview.fit.ink.recycle(); true
                    }, onFailure = { false })
                }
                if (!fits) { unfitted += attempt; skipped++; continue }
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.IO) { store.saveEdit(page.book.id, page.original, edit, page.page.id) }
                existing += edit; count++
                changed()
            }
        }
        return when {
            groups.all { it.bubbles.isEmpty() } -> "No supported dialogue bubbles found. Tap a bubble to edit."
            skipped > 0 -> "Saved " + count + " translations · " + skipped + " areas need manual editing"
            else -> "Dialogue ready · Tap a bubble to edit"
        }
    }
}
