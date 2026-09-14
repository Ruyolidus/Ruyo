package com.ruyo.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruyo.data.SavedLine
import com.ruyo.data.OpenBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import com.ruyo.sample.SampleChapter

@Composable
internal fun SampleReader(model: RuyoModel) {
    val scroll = rememberLazyListState()
    Column(Modifier.fillMaxSize().testTag("sample-reader")) {
        ReaderControls(model.japanese, { model.japanese = it }, "${(scroll.firstVisibleItemIndex + 1).coerceAtMost(model.samples.size)} / ${model.samples.size}")
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF25282B)), state = scroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(model.samples, key = { _, page -> page.line.id }) { index, page ->
                ReaderPage(if (model.japanese) page.translated else page.original, "Panel ${index + 1}", "sample-panel-$index",
                    study = if (model.japanese) ({ model.studySample(page.line.id) }) else null) { x, y ->
                    if (model.japanese && page.interior[x - page.bubbleBounds.left.toInt(), y - page.bubbleBounds.top.toInt()]) model.studySample(page.line.id)
                }
            }
            item { Text("End of sample", Modifier.fillMaxWidth().padding(24.dp), color = Color(0xFFC0C5CA), style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Text("Tap a Japanese bubble to study · Pinch to zoom", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun BookReader(model: RuyoModel) {
    val book = model.chapter ?: return
    val position = model.readingPosition
    val start = book.pages.indexOfFirst { it.id == position?.pageId }.coerceAtLeast(0)
    val scroll = rememberLazyListState(start, position?.offset ?: 0)
    LaunchedEffect(scroll, book.id) {
        snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { (index, offset) ->
            book.pages.getOrNull(index)?.let { model.rememberPosition(it.id, offset) }
        }
    }
    DisposableEffect(book.id) { onDispose { model.flushPosition() } }
    Column(Modifier.fillMaxSize().testTag("book-reader")) {
        ReaderControls(model.japanese, { model.japanese = it }, "${(scroll.firstVisibleItemIndex + 1).coerceAtMost(book.pages.size)} / ${book.pages.size}")
        if (model.selecting) Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Tap inside a plain, light bubble. Pinch to reach small dialogue.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                model.selectionError?.let { Text(it, Modifier.padding(top = 8.dp).testTag("selection-error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF25282B)).testTag("chapter-scroll"), state = scroll) {
            itemsIndexed(book.pages, key = { _, page -> page.id }) { index, page ->
                var retry by remember(page.id) { mutableIntStateOf(0) }
                val loaded by produceState<Result<OpenBook>?>(null, book.id, page.id, model.pageRevision, retry) {
                    value = null
                    value = try { Result.success(model.pageLoader.load(book, page)) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { Result.failure(error) }
                }
                val data = loaded?.getOrNull()
                if (data == null) Box(Modifier.fillMaxWidth().aspectRatio(page.width.toFloat() / page.height), contentAlignment = Alignment.Center) {
                    if (loaded == null) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Page ${index + 1} could not be opened", color = Color.White)
                        TextButton(onClick = { retry++ }) { Text("Retry", color = Color.White) }
                    }
                } else ReaderPage(if (model.japanese && !model.selecting) data.displayed else data.original, "Page ${index + 1}", if (index == 0) "imported-page" else "imported-page-${page.id}") { x, y ->
                    if (!model.busy) {
                        if (model.selecting) model.selectBubble(x, y, data)
                        else if (model.japanese) data.edits.findLast { it.region.contains(x, y) }?.let { model.studyEdit(it, data) }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Text(if (model.selecting) "Plain bubbles · Tap inside an existing edit to revise it" else "Pencil to edit · Tap Japanese to save · Pinch to zoom",
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReaderControls(japanese: Boolean, onLanguage: (Boolean) -> Unit, detail: String) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onLanguage(false) }, modifier = Modifier.testTag("show-original"), colors = ButtonDefaults.textButtonColors(contentColor = if (!japanese) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) { Text("Original", fontWeight = if (!japanese) FontWeight.SemiBold else FontWeight.Normal) }
            TextButton(onClick = { onLanguage(true) }, modifier = Modifier.testTag("show-japanese"), colors = ButtonDefaults.textButtonColors(contentColor = if (japanese) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) { Text("Japanese", fontWeight = if (japanese) FontWeight.SemiBold else FontWeight.Normal) }
            Spacer(Modifier.weight(1f))
            Text(detail, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReaderPage(bitmap: Bitmap, label: String, tag: String, study: (() -> Unit)? = null, onTap: (Int, Int) -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val currentTap by rememberUpdatedState(onTap)
    val transforms = rememberTransformableState { factor, movement, _ ->
        zoom = (zoom * factor).coerceIn(1f, 4f)
        val limitX = viewport.width * (zoom - 1) / 2
        val limitY = viewport.height * (zoom - 1) / 2
        pan = Offset((pan.x + movement.x).coerceIn(-limitX, limitX), (pan.y + movement.y).coerceIn(-limitY, limitY))
    }
    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).clipToBounds().testTag(tag)
        .onSizeChanged { viewport = it }
        .transformable(transforms, canPan = { zoom > 1f })
        .pointerInput(bitmap.width, bitmap.height, zoom, pan) {
            detectTapGestures { tap ->
                val center = Offset(size.width / 2f, size.height / 2f)
                val imagePoint = (tap - center - pan) / zoom + center
                val scale = size.width.toFloat() / bitmap.width
                currentTap((imagePoint.x / scale).toInt(), (imagePoint.y / scale).toInt())
            }
        }.semantics { if (study != null) onClick("Study Japanese bubble") { study(); true } }) {
        Image(bitmap.asImageBitmap(), label, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }, contentScale = ContentScale.FillBounds)
        if (zoom > 1.05f) FilledTonalButton(onClick = { zoom = 1f; pan = Offset.Zero }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Reset zoom", style = MaterialTheme.typography.labelSmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudySheet(model: RuyoModel, line: SavedLine) {
    val sample = SampleChapter.lines.find { it.id == line.sampleId }
    var selectedTab by rememberSaveable(line.id) { mutableIntStateOf(0) }
    val isSaved = model.saved.any { it.id == line.id }
    ModalBottomSheet(onDismissRequest = { model.lesson = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().testTag("study-sheet").navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(line.source, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { model.lesson = null }, modifier = Modifier.size(40.dp)) { Icon(AppIcons.Close, "Close lesson", Modifier.size(20.dp)) }
                }
                Text(line.japanese, fontSize = 26.sp, lineHeight = 38.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                if (sample != null) Text(sample.reading, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            if (sample != null) {
                TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Meaning", "Grammar", "Words").forEachIndexed { index, title -> Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) }) }
                }
                Column(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 280.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (selectedTab) {
                        0 -> {
                            Text(sample.meaning, style = MaterialTheme.typography.bodyLarge)
                            Column { SectionLabel("Example"); Text(sample.example, style = MaterialTheme.typography.bodyMedium) }
                        }
                        1 -> Text(sample.grammar, style = MaterialTheme.typography.bodyLarge)
                        else -> sample.vocabulary.forEach { (word, meaning) -> Column { Text(word, style = MaterialTheme.typography.titleMedium); Text(meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                }
            } else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("Manual Japanese")
                Text("This is the text you entered for this bubble. You can save it now; AI grammar and vocabulary explanations are coming in a later build.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Button(onClick = { model.toggleSaved(line) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).height(48.dp).testTag("save-sentence")) {
                Icon(if (isSaved) AppIcons.Check else AppIcons.Bookmark, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text(if (isSaved) "Saved · remove" else "Save sentence")
            }
        }
    }
}
