package com.ruyo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ruyo.ai.OcrScript
import com.ruyo.data.OpenBook
import com.ruyo.reader.TextLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun WebReaderScreen(model: RuyoModel) {
    val session = model.webReading ?: return
    val scroll = rememberLazyListState(model.webPosition.first, model.webPosition.second)
    LaunchedEffect(scroll, session.id) {
        snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) ->
            model.rememberWebPosition(index, offset)
        }
    }
    LaunchedEffect(scroll, session.id) {
        snapshotFlow {
            val visible = scroll.layoutInfo.visibleItemsInfo.filter { it.index < session.images.size }
            val first = visible.firstOrNull()
            visible.map { it.index } to (if (first == null || first.size == 0) 0f else (-first.offset).coerceAtLeast(0).toFloat() / first.size)
        }.distinctUntilChanged().collect { (indices, fraction) ->
            model.webViewport(indices, fraction)
            model.scrollTranslation?.viewport(indices)
        }
    }
    DisposableEffect(session.id) { onDispose { model.pauseScrolling() } }
    Box(Modifier.fillMaxSize().background(Color(0xFF25282B)).testTag("web-reader")) {
        LazyColumn(Modifier.fillMaxSize().background(Color(0xFF25282B)).testTag("web-reading-scroll"), state = scroll) {
            itemsIndexed(session.images, key = { _, image -> image.url }) { index, image ->
                var retry by remember(image.url) { mutableIntStateOf(0) }
                val loaded by produceState<Result<OpenBook>?>(null, session.id, index, model.pageRevision, retry) {
                    // Keep the last frame visible while applying an incremental swap.
                    value = try { Result.success(session.load(index)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { Result.failure(failure) }
                }
                val page = loaded?.getOrNull()
                if (page == null) Box(Modifier.fillMaxWidth().aspectRatio(if (image.width > 0 && image.height > 0) (image.width.toFloat() / image.height).coerceIn(0.08f, 4f) else 0.7f),
                    contentAlignment = Alignment.Center) {
                    if (loaded == null) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(loaded?.exceptionOrNull()?.message ?: "This image could not be opened.", color = Color.White)
                        TextButton(onClick = { retry++ }) { Text("Retry image", color = Color.White) }
                    }
                } else ReaderPage(if (model.japanese) page.displayed else page.original, "Web image " + (index + 1), "web-page-" + index) { x, y ->
                    if (!model.busy) {
                        val translated = if (model.japanese) page.edits.findLast { it.region.contains(x, y) } else null
                        if (translated != null) model.studyEdit(translated, page, index)
                        else model.editWebBubble(index, x, y)
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp).padding(bottom = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (session.images.isEmpty()) "Waiting for chapter images…" else model.webLoadStatus, color = Color.White, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = model::returnToWebsite) { Text("Website", color = Color.White) }
                }
            }
        }
        ReaderOverlay(model, ((scroll.firstVisibleItemIndex + 1).coerceAtMost(session.images.size)).toString() + " / " + session.images.size,
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(12.dp))
        model.selectionError?.let { error ->
            Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(error, Modifier.padding(12.dp).testTag("selection-error"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderOverlay(model: RuyoModel, detail: String, modifier: Modifier = Modifier) {
    if (model.route == "book") { ChapterReaderTools(model, modifier); return }
    val translation = model.scrollTranslation ?: return
    var settings by rememberSaveable { mutableStateOf(false) }
    Surface(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), tonalElevation = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!model.readerImmersive) {
                if (translation.running && translation.status != null) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(14.dp), strokeWidth = 2.dp)
                TextButton(onClick = { if (translation.running) model.pauseScrolling() else model.startScrolling() }, enabled = !model.busy,
                    modifier = Modifier.testTag("scroll-translate")) {
                    Text(if (translation.running) "Pause" else if (translation.error != null) "Retry" else "Translate")
                }
            }
            IconButton(onClick = { settings = true }, modifier = Modifier.testTag("reading-translation-settings")) {
                Icon(AppIcons.Settings, "Reading controls", Modifier.size(21.dp), tint = if (translation.error == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            }
        }
    }
    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Reading · " + detail, style = MaterialTheme.typography.titleLarge)
                Text(translation.error ?: translation.status ?: if (translation.running) "Ready for the next image" else "Translation paused",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = if (translation.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Minimal reader", Modifier.weight(1f))
                    Switch(checked = model.readerImmersive, onCheckedChange = { model.readerImmersive = it }, modifier = Modifier.testTag("minimal-reader"))
                }
                ReaderControls(model.japanese, { model.japanese = it }, "", "Translated")
                Button(onClick = { if (translation.running) model.pauseScrolling() else model.startScrolling(); settings = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (translation.running) "Pause translation" else "Translate as you scroll")
                }
            }
            item {
                LanguagePicker(model.targetLanguage, model::changeTargetLanguage, !model.busy, tag = "reading-language")
                var scripts by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { scripts = true }) { Text("Source: " + model.ocrScript.label) }
                    DropdownMenu(expanded = scripts, onDismissRequest = { scripts = false }) {
                        OcrScript.entries.forEach { script -> DropdownMenuItem(text = { Text(script.label) }, onClick = { model.changeOcrScript(script); scripts = false }) }
                    }
                }
                TextButton(onClick = { settings = false; model.editProfiles() }, modifier = Modifier.testTag("reading-providers")) { Text(model.activeProfile?.name ?: "Choose AI provider") }
            }
            item {
                Text("Only recognised dialogue text is sent to your provider. Comic images stay on this device. Nearby bubbles are translated in small groups; saved swaps are reused.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Pinch to zoom. Tap translated dialogue to study. Edit from its lesson sheet; tap untranslated web dialogue to translate it.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            item {
                if (model.route == "webread") OutlinedButton(onClick = { settings = false; model.returnToWebsite() }, modifier = Modifier.fillMaxWidth()) { Text("Return to website") }
                else OutlinedButton(onClick = { settings = false; model.toggleSelection() }, modifier = Modifier.fillMaxWidth()) { Text(if (model.selecting) "Finish editing bubbles" else "Edit bubbles") }
                TextButton(onClick = { settings = false; model.home() }, modifier = Modifier.fillMaxWidth()) { Text("Library") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterReaderTools(model: RuyoModel, modifier: Modifier) {
    var settings by rememberSaveable { mutableStateOf(false) }
    Surface(modifier, shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
        IconButton(onClick = { settings = true }, modifier = Modifier.testTag("reading-translation-settings")) { Icon(AppIcons.Settings, "Reading controls", Modifier.size(21.dp)) }
    }
    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Reading controls", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hide title bar", Modifier.weight(1f))
                Switch(model.readerImmersive, { model.readerImmersive = it })
            }
            LanguagePicker(model.targetLanguage, model::changeTargetLanguage, !model.busy, tag = "reading-language")
            Text("Preparation fills untranslated supported dialogue. Your saved edits are kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { settings = false; model.startPreparation() }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("prepare-chapter")) { Text("Prepare whole chapter") }
            OutlinedButton(onClick = { settings = false; model.toggleSelection() }, modifier = Modifier.fillMaxWidth()) { Text("Edit bubbles") }
            TextButton(onClick = { settings = false; model.editProfiles() }) { Text(model.activeProfile?.name ?: "Choose AI provider") }
            Text("Tap translated dialogue to study. Original and Translated stay available in the reader.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
