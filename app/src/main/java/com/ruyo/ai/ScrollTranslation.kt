package com.ruyo.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ruyo.data.PagePreparation
import com.ruyo.data.LocalBookStore
import com.ruyo.data.OpenBook
import com.ruyo.reader.*
import kotlinx.coroutines.*

data class TranslationSettings(val profileId: String, val language: String, val script: OcrScript)

/** One worker, visible images first, then one image ahead. Viewport changes never restart a paid call. */
class ScrollTranslation(
    private val scope: CoroutineScope,
    private var pageCount: Int,
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
    private var visible = emptyList<Int>()
    fun resize(count: Int) { pageCount = count; viewport(visible) }
    fun viewport(visible: List<Int>) {
        this.visible = visible
        val valid = visible.distinct().filter { it in 0 until pageCount }.sorted()
        window = (valid + listOfNotNull(valid.lastOrNull()?.plus(1)?.takeIf { it < pageCount })).distinct()
        schedule()
    }
    fun start() { error = null; running = true; schedule() }
    fun pause(): Job? {
        running = false; generation++; status = null
        return job?.also { it.cancel() }
    }
    fun reset() { pause(); completed.clear(); notes = emptyMap(); error = null }
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
    private val lineCache = object : java.util.LinkedHashMap<String, List<OcrLine>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OcrLine>>?) = size > 3
    }
    private val replies = mutableMapOf<String, String>()
    suspend fun process(page: OpenBook, settings: TranslationSettings, keepGoing: () -> Boolean, status: (String) -> Unit,
        changed: suspend () -> Unit): String? = prepare(page, settings, keepGoing, status, changed)?.message

    suspend fun prepare(page: OpenBook, settings: TranslationSettings, keepGoing: () -> Boolean, status: (String) -> Unit,
        changed: suspend () -> Unit): PagePreparation? {
        if (!keepGoing()) return null
        val sourceKey = page.book.id + ":" + page.page.id + ":" + settings.script.name
        val lines = lineCache[sourceKey] ?: run {
            status("Recognizing dialogue on this device…")
            withContext(Dispatchers.Default) { ocr.lines(page.original, settings.script) }.also { lineCache[sourceKey] = it }
        }
        val groups = withContext(Dispatchers.Default) { AutoBubbleDetector.detect(page.original, lines) { store.areaCenters(page, it) } }
        if (!keepGoing()) return null
        val secret = withContext(Dispatchers.IO) { profiles.get(settings.profileId) }
        val existing = page.edits.toMutableList()
        val candidates = mutableListOf<DetectedBubble>()
        for (group in groups) {
            currentCoroutineContext().ensureActive()
            if (!keepGoing()) return null
            if (group.centers.size > 1 && existing.none { it.region.overlaps(group.region) }) {
                withContext(Dispatchers.IO) { store.saveAreas(page, group.region, group.centers) }
            }
            candidates += group.bubbles.filter { bubble -> existing.none { it.region.overlaps(bubble.region) } }
        }
        val pending = candidates.take((40 - existing.size).coerceAtLeast(0))
        var skipped = candidates.size - pending.size; var count = 0; var offset = 0
        fun key(bubble: DetectedBubble) = listOf(sourceKey, bubble.region.left, bubble.region.top, bubble.region.width, bubble.region.height,
            secret.profile.id, secret.profile.baseUrl, secret.profile.model, settings.language, bubble.source).joinToString("|")
        while (offset < pending.size) {
            currentCoroutineContext().ensureActive()
            if (!keepGoing()) return null
            val batch = mutableListOf<DetectedBubble>()
            var characters = 0
            while (offset < pending.size && batch.size < 4 && characters + pending[offset].source.length <= 6000) {
                val bubble = pending[offset++]; batch += bubble; characters += bubble.source.length
            }
            val missing = batch.mapIndexedNotNull { i, bubble -> if (key(bubble) !in replies) SourceDialogue(i.toString(), bubble.source) else null }
            if (missing.isNotEmpty()) {
                status("Translating " + missing.size + if (missing.size == 1) " bubble…" else " bubbles together…")
                val result = translate.translateBatch(secret, missing, settings.language)
                currentCoroutineContext().ensureActive()
                require(result.keys == missing.map { it.id }.toSet() && result.values.all { it.isNotBlank() && it.length <= 512 }) {
                    "The provider did not return every dialogue separately. The original is unchanged."
                }
                missing.forEach { item -> replies[key(batch[item.id.toInt()])] = requireNotNull(result[item.id]) }
            }
            var saved = false
            for (bubble in batch) {
                currentCoroutineContext().ensureActive()
                val sourceHeight = withContext(Dispatchers.Default) { SourceLettering.estimateHeight(bubble.region) }
                val edit = BubbleEdit(region = bubble.region, japanese = requireNotNull(replies[key(bubble)]),
                    margin = maxOf(2, minOf(bubble.region.width, bubble.region.height) / 14), languageTag = settings.language,
                    sourceLetterHeight = sourceHeight, matchSourceSize = sourceHeight != null)
                val fits = withContext(Dispatchers.Default) {
                    BubbleEditRenderer.preview(page.original, edit).fold(onSuccess = { preview ->
                        preview.crop.recycle(); preview.fit.ink.recycle(); true
                    }, onFailure = { false })
                }
                if (!fits) { skipped++; continue }
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.IO) { store.saveEdit(page.book.id, page.original, edit, page.page.id) }
                existing += edit; count++; saved = true
            }
            if (saved) changed()
        }
        val uncovered = lines.count { line -> existing.none { it.languageTag == settings.language && it.region.contains(line.x, line.y) } }
        val review = skipped > 0 || uncovered > 0
        val note = when {
            lines.isEmpty() -> "No dialogue detected. Check the original page."
            review -> "Some lettering needs review; unsupported areas keep their original pixels."
            else -> "Detected dialogue is ready."
        }
        return PagePreparation(note, review)
    }
}
