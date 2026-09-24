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
    suspend fun process(page: OpenBook, settings: TranslationSettings, keepGoing: () -> Boolean, status: (String) -> Unit,
        changed: suspend () -> Unit): String? = prepare(page, settings, keepGoing, status, changed)?.message

    suspend fun prepare(page: OpenBook, settings: TranslationSettings, keepGoing: () -> Boolean, status: (String) -> Unit,
        changed: suspend () -> Unit): PagePreparation? {
        if (!keepGoing()) return null
        val sourceKey = page.book.id + ":" + page.page.id + ":" + settings.script.name
        val lines = lineCache[sourceKey] ?: run {
            status("Recognizing text on this device…")
            withContext(Dispatchers.Default) { ocr.lines(page.original, settings.script) }.also { lineCache[sourceKey] = it }
        }
        val existing = page.edits.toMutableList()
        var repairedSaved = 0
        for (index in existing.indices) {
            currentCoroutineContext().ensureActive()
            if (!keepGoing()) return null
            val edit = existing[index]
            if (edit.cleanupVersion >= 5 || edit.cleanupLocked) continue
            val sourceLines = lines.filter { edit.region.contains(it.x, it.y) }
            if (sourceLines.isEmpty()) continue
            val region = withContext(Dispatchers.Default) { TextRegionRepair.refine(page.original, edit.region, sourceLines, lines, allowExpansion = true) }
            if (region === edit.region || existing.any { it.id != edit.id && it.region.overlaps(region) }) continue
            val replacement = edit.copy(region = region, cleanupVersion = 5)
            val valid = withContext(Dispatchers.Default) { BubbleEditRenderer.preview(page.original, replacement).fold(
                onSuccess = { it.crop.recycle(); it.fit.ink.recycle(); true }, onFailure = { false }) }
            if (!valid) continue
            withContext(Dispatchers.IO) {
                store.backupCleanup(page)
                store.saveEdit(page.book.id, page.original, replacement, page.page.id)
            }
            existing[index] = replacement; repairedSaved++
        }
        if (repairedSaved > 0) changed()
        val remaining = lines.filter { line -> existing.none { it.region.contains(line.x, line.y) } }
        val candidates = withContext(Dispatchers.Default) { AutoBubbleDetector.analyze(page.original, remaining, lines, existing.map { it.region }) { store.areaCenters(page, it) } }
        if (!keepGoing()) return null
        val secret = withContext(Dispatchers.IO) { profiles.get(settings.profileId) }
        val cached = withContext(Dispatchers.IO) { store.textTranslations(page) }.toMutableMap()
        val pending = candidates.filter { it.source.isNotBlank() && it.source.length <= 2000 }
        var cleanupFailed = candidates.size - pending.size; var fitFailed = 0; var offset = 0; var artworkRepairs = 0
        val alreadyRendered = existing.count { it.languageTag == settings.language }
        var translated = alreadyRendered; var rendered = alreadyRendered
        fun key(area: TextArea) = translationCacheKey(secret.profile, settings.language, area.source)
        while (offset < pending.size) {
            currentCoroutineContext().ensureActive()
            if (!keepGoing()) return null
            val batch = mutableListOf<TextArea>(); var characters = 0
            while (offset < pending.size && batch.size < 4 && characters + pending[offset].source.length <= 6000) {
                val area = pending[offset++]; batch += area; characters += area.source.length
            }
            val missing = batch.mapIndexedNotNull { i, area -> if (key(area) !in cached) SourceDialogue(i.toString(), area.source) else null }
                .distinctBy { key(batch[it.id.toInt()]) }
            if (missing.isNotEmpty()) {
                status("Translating " + missing.size + if (missing.size == 1) " text area…" else " text areas together…")
                val result = translate.translateBatch(secret, missing, settings.language)
                currentCoroutineContext().ensureActive()
                require(result.keys == missing.map { it.id }.toSet() && result.values.all { it.isNotBlank() && it.length <= 512 }) {
                    "The provider did not return every text area separately. Completed work is saved."
                }
                for (item in missing) {
                    val cacheKey = key(batch[item.id.toInt()]); val text = requireNotNull(result[item.id])
                    withContext(Dispatchers.IO) { store.saveTextTranslation(page, cacheKey, text) }
                    cached[cacheKey] = text
                }
            }
            var saved = false
            for (area in batch) {
                currentCoroutineContext().ensureActive(); translated++
                val region = area.region
                if (region == null || existing.size >= 40 || existing.any { it.region.overlaps(region) }) { cleanupFailed++; continue }
                val sourceHeight = area.lines.map { (it.bottom - it.top).toFloat() }.sorted().let { it[it.size / 2] }
                val edit = BubbleEdit(region = region, japanese = requireNotNull(cached[key(area)]),
                    margin = maxOf(2, minOf(region.width, region.height) / 14), languageTag = settings.language,
                    sourceLetterHeight = sourceHeight, matchSourceSize = true, cleanupVersion = 5)
                val fits = withContext(Dispatchers.Default) {
                    BubbleEditRenderer.preview(page.original, edit).fold(onSuccess = { preview ->
                        preview.crop.recycle(); preview.fit.ink.recycle(); true
                    }, onFailure = { false })
                }
                if (!fits) { fitFailed++; continue }
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.IO) { store.saveEdit(page.book.id, page.original, edit, page.page.id) }
                existing += edit; rendered++; if (area.needsReview) artworkRepairs++; saved = true
            }
            if (saved) changed()
        }
        val uncovered = lines.count { line -> existing.none { it.languageTag == settings.language && it.region.contains(line.x, line.y) } }
        val review = lines.isEmpty() || cleanupFailed > 0 || fitFailed > 0 || uncovered > 0 || artworkRepairs > 0
        val note = when {
            lines.isEmpty() -> "No text recognized. Check the original page."
            cleanupFailed > 0 || fitFailed > 0 -> "$rendered text areas swapped. $cleanupFailed need cleanup; $fitFailed could not fit. Their translations are saved."
            uncovered > 0 -> "$rendered text areas swapped. Some recognized text is still unchanged."
            artworkRepairs > 0 -> "$rendered text areas swapped, including $artworkRepairs artwork repairs to review."
            else -> "$rendered text areas swapped."
        }
        return PagePreparation(note, review, alreadyRendered + candidates.size, translated, rendered, cleanupFailed, fitFailed)
    }
}

/** Per-page cache identity excludes credentials and survives engine/cleanup changes. */
internal fun translationCacheKey(profile: ProviderProfile, language: String, source: String): String {
    val normalized = source.trim().replace(Regex("\\s+"), " ")
    val data = org.json.JSONArray(listOf(profile.kind.name, profile.baseUrl, profile.model, language, normalized)).toString()
    return java.security.MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString("") { "%02x".format(it) }
}
