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
        snapshotFlow { scroll.layoutInfo.visibleItemsInfo.map { it.index } }.distinctUntilChanged().collect { model.scrollTranslation?.viewport(it) }
    }
    DisposableEffect(session.id) { onDispose { model.pauseScrolling() } }
    Column(Modifier.fillMaxSize().testTag("web-reader")) {
        ReaderControls(model.japanese, { model.japanese = it },
            ((scroll.firstVisibleItemIndex + 1).coerceAtMost(session.images.size)).toString() + " / " + session.images.size, "Translated")
        ScrollTranslationControls(model)
        model.selectionError?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("selection-error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF25282B)).testTag("web-reading-scroll"), state = scroll) {
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
                    if (!model.busy) model.editWebBubble(index, x, y)
                }
            }
            item { Text("End of loaded chapter images", Modifier.fillMaxWidth().padding(24.dp), color = Color.White, style = MaterialTheme.typography.bodySmall) }
        }
        Surface {
            Text(model.scrollTranslation?.notes?.get(scroll.firstVisibleItemIndex) ?: "Tap a bubble to edit · Temporary reading session",
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ScrollTranslationControls(model: RuyoModel) {
    val translation = model.scrollTranslation ?: return
    var settings by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(translation.status ?: if (translation.running) "Translate as you scroll" else TextLanguages.label(model.targetLanguage),
                        style = MaterialTheme.typography.labelMedium)
                    Text(translation.error ?: model.activeProfile?.name ?: "Choose an AI provider", style = MaterialTheme.typography.bodySmall,
                        color = if (translation.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { if (translation.running) model.pauseScrolling() else model.startScrolling() }, enabled = !model.busy,
                    modifier = Modifier.testTag("scroll-translate")) { Text(if (translation.running) "Pause" else "Translate") }
                IconButton(onClick = { settings = !settings }, modifier = Modifier.testTag("reading-translation-settings")) { Icon(AppIcons.Settings, "Translation settings", Modifier.size(20.dp)) }
            }
            if (settings) Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguagePicker(model.targetLanguage, model::changeTargetLanguage, !model.busy, tag = "reading-language")
                var scripts by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { scripts = true }) { Text("Source: " + model.ocrScript.label) }
                    DropdownMenu(expanded = scripts, onDismissRequest = { scripts = false }) {
                        OcrScript.entries.forEach { script -> DropdownMenuItem(text = { Text(script.label) }, onClick = { model.changeOcrScript(script); scripts = false }) }
                    }
                }
                TextButton(onClick = model::editProfiles, modifier = Modifier.testTag("reading-providers")) { Text("AI providers") }
                Text("Translate processes visible images and one ahead with your selected provider. Opening a bubble recognizes, translates, and previews it automatically. Saved edits stay as you set them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
