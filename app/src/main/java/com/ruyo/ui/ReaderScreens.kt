package com.ruyo.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
    LaunchedEffect(scroll, book.id) {
        snapshotFlow { scroll.layoutInfo.visibleItemsInfo.map { it.index } }.distinctUntilChanged().collect { model.scrollTranslation?.viewport(it) }
    }
    DisposableEffect(book.id) { onDispose { model.flushPosition(); model.pauseScrolling() } }
    Box(Modifier.fillMaxSize().testTag("book-reader")) {
      Column(Modifier.fillMaxSize()) {
        ReaderControls(model.japanese, { model.japanese = it }, "${(scroll.firstVisibleItemIndex + 1).coerceAtMost(book.pages.size)} / ${book.pages.size}", "Translated",
            navigation = model.seriesFor(book.id) != null,
            previous = model.neighbor(-1)?.let { previous -> ({ model.openBook(previous) }) },
            next = model.neighbor(1)?.let { next -> ({ model.openBook(next) }) })
        if (model.selecting) Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !model.drawingArea, onClick = { model.drawingArea = false }, label = { Text("Tap text") })
                    FilterChip(selected = model.drawingArea, onClick = { model.drawingArea = true }, label = { Text("Draw area") }, modifier = Modifier.testTag("draw-text-area"))
                }
                Text(if (model.drawingArea) "Hold and drag around a text block. Leave a little space around the letters." else "Tap a bubble or caption. Use Draw area for text over artwork.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                model.selectionError?.let { Text(it, Modifier.padding(top = 8.dp).testTag("selection-error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF25282B)).testTag("chapter-scroll"), state = scroll, contentPadding = PaddingValues(bottom = 68.dp)) {
            itemsIndexed(book.pages, key = { _, page -> page.id }) { index, page ->
                var retry by remember(page.id) { mutableIntStateOf(0) }
                val loaded by produceState<Result<OpenBook>?>(null, book.id, page.id, model.pageRevision, retry) {
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
                } else ReaderPage(if (model.japanese && !model.selecting) data.displayed else data.original, "Page ${index + 1}", if (index == 0) "imported-page" else "imported-page-${page.id}",
                    onArea = if (model.selecting && model.drawingArea && !model.busy) ({ l, t, r, b -> model.selectTextArea(l, t, r, b, data); Unit }) else null) { x, y ->
                    if (!model.busy) {
                        if (model.selecting) model.selectBubble(x, y, data)
                        else if (model.japanese) data.edits.findLast { it.region.contains(x, y) }?.let { model.studyEdit(it, data) }
                    }
                }
            }
        }
      }
      ReaderOverlay(model, ((scroll.firstVisibleItemIndex + 1).coerceAtMost(book.pages.size)).toString() + " / " + book.pages.size,
          Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(12.dp))
    }
}

@Composable
internal fun ReaderControls(japanese: Boolean, onLanguage: (Boolean) -> Unit, detail: String, translatedLabel: String = "Japanese", navigation: Boolean = false, previous: (() -> Unit)? = null, next: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onLanguage(false) }, modifier = Modifier.testTag("show-original"), colors = ButtonDefaults.textButtonColors(contentColor = if (!japanese) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) { Text("Original", fontWeight = if (!japanese) FontWeight.SemiBold else FontWeight.Normal) }
            TextButton(onClick = { onLanguage(true) }, modifier = Modifier.testTag("show-japanese"), colors = ButtonDefaults.textButtonColors(contentColor = if (japanese) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) { Text(translatedLabel, fontWeight = if (japanese) FontWeight.SemiBold else FontWeight.Normal) }
            Spacer(Modifier.weight(1f))
            Text(detail, Modifier.padding(end = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (navigation) {
                IconButton(onClick = { previous?.invoke() }, enabled = previous != null, modifier = Modifier.size(40.dp).testTag("previous-chapter")) { Icon(AppIcons.Back, "Previous chapter", Modifier.size(18.dp)) }
                IconButton(onClick = { next?.invoke() }, enabled = next != null, modifier = Modifier.size(40.dp).testTag("next-chapter")) { Icon(AppIcons.Next, "Next chapter", Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
internal fun ReaderPage(bitmap: Bitmap, label: String, tag: String, study: (() -> Unit)? = null,
    onArea: ((Int, Int, Int, Int) -> Unit)? = null, onTap: (Int, Int) -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val currentTap by rememberUpdatedState(onTap)
    val currentArea by rememberUpdatedState(onArea)
    var areaStart by remember { mutableStateOf<Offset?>(null) }
    var areaEnd by remember { mutableStateOf<Offset?>(null) }
    val transforms = rememberTransformableState { factor, movement, _ ->
        zoom = (zoom * factor).coerceIn(1f, 4f)
        val limitX = viewport.width * (zoom - 1) / 2
        val limitY = viewport.height * (zoom - 1) / 2
        pan = Offset((pan.x + movement.x).coerceIn(-limitX, limitX), (pan.y + movement.y).coerceIn(-limitY, limitY))
    }
    Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).clipToBounds().testTag(tag)
        .onSizeChanged { viewport = it }
        .transformable(transforms, canPan = { zoom > 1f })
        .pointerInput(bitmap.width, bitmap.height, zoom, pan, onArea != null) {
            if (onArea != null) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { areaStart = it; areaEnd = it },
                    onDragCancel = { areaStart = null; areaEnd = null },
                    onDragEnd = {
                        val a = areaStart; val b = areaEnd
                        if (a != null && b != null) {
                            val center = Offset(size.width / 2f, size.height / 2f); val scale = size.width.toFloat() / bitmap.width
                            val first = ((a - center - pan) / zoom + center) / scale
                            val last = ((b - center - pan) / zoom + center) / scale
                            currentArea?.invoke(minOf(first.x, last.x).toInt(), minOf(first.y, last.y).toInt(), maxOf(first.x, last.x).toInt(), maxOf(first.y, last.y).toInt())
                        }
                        areaStart = null; areaEnd = null
                    },
                    onDrag = { change, _ -> change.consume(); areaEnd = change.position })
            } else {
            detectTapGestures { tap ->
                val center = Offset(size.width / 2f, size.height / 2f)
                val imagePoint = (tap - center - pan) / zoom + center
                val scale = size.width.toFloat() / bitmap.width
                currentTap((imagePoint.x / scale).toInt(), (imagePoint.y / scale).toInt())
            }
            }
        }.semantics { if (study != null) onClick("Study Japanese bubble") { study(); true } }) {
        Image(bitmap.asImageBitmap(), label, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }, contentScale = ContentScale.FillBounds)
        val start = areaStart; val end = areaEnd
        if (start != null && end != null) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val corner = Offset(minOf(start.x, end.x), minOf(start.y, end.y))
            val extent = androidx.compose.ui.geometry.Size(kotlin.math.abs(end.x - start.x), kotlin.math.abs(end.y - start.y))
            drawRect(Color(0x443BA4D8), corner, extent)
            drawRect(Color.White, corner, extent, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        if (zoom > 1.05f) FilledTonalButton(onClick = { zoom = 1f; pan = Offset.Zero }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Reset zoom", style = MaterialTheme.typography.labelSmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudySheet(model: RuyoModel, line: SavedLine) {
    val sample = SampleChapter.lines.find { it.id == line.sampleId }
    var selectedTab by rememberSaveable(line.id) { mutableIntStateOf(0) }
    val isSaved = model.saved.any { it.id == line.id }
    LaunchedEffect(line, model.activeProfileId, model.foreground) {
        if (sample == null && model.foreground) model.explainLesson()
    }
    ModalBottomSheet(onDismissRequest = model::closeLesson, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().testTag("study-sheet").navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("This line", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = model::closeLesson) { Icon(AppIcons.Close, "Close lesson", Modifier.size(20.dp)) }
            }
            TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surface) {
                (if (sample == null) listOf("Meaning", "Grammar", "Words", "Practice") else listOf("Meaning", "Grammar", "Words")).forEachIndexed { index, title ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title, maxLines = 1, style = MaterialTheme.typography.labelMedium) })
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 180.dp).testTag("lesson-content"),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text(line.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(line.japanese, fontSize = 24.sp, lineHeight = 36.sp, modifier = Modifier.padding(top = 8.dp))
                    val reading = sample?.reading ?: model.explanation?.reading
                    if (!reading.isNullOrBlank()) Text(reading, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (sample != null) {
                    when (selectedTab) {
                        0 -> item { Text(sample.meaning); Spacer(Modifier.height(16.dp)); SectionLabel("Example"); Text(sample.example) }
                        1 -> item { Text(sample.grammar) }
                        else -> sample.vocabulary.forEach { (word, meaning) -> item { Text(word, style = MaterialTheme.typography.titleMedium); Text(meaning) } }
                    }
                } else {
                    val value = model.explanation
                    if (value == null) item {
                        if (model.explanationBusy) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Preparing your explanation…", Modifier.padding(start = 12.dp))
                            }
                        } else {
                            Text(model.explanationError ?: "Explain this dialogue with your selected AI provider.")
                            TextButton(onClick = { if (model.activeProfile == null) model.editProfiles() else model.explainLesson() }, modifier = Modifier.testTag("retry-lesson")) {
                                Text(if (model.activeProfile == null) "Choose AI provider" else "Retry explanation")
                            }
                        }
                        Text("Only this dialogue text is sent. Lessons are cached on this device.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        item { Text("AI explanation · English", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        when (selectedTab) {
                            0 -> {
                                item {
                                    Text(value.meaning, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("lesson-meaning"))
                                    if (value.note.isNotBlank()) Text(value.note, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge)
                                    TextButton(onClick = { selectedTab = 1 }) { Text("How it works") }
                                }
                                value.examples.forEachIndexed { index, example ->
                                    item {
                                        var help by rememberSaveable(line.id, example.text) { mutableStateOf(false) }
                                        SectionLabel("You could also say")
                                        Text(example.text, style = MaterialTheme.typography.titleMedium)
                                        Text(example.explanation, Modifier.padding(top = 8.dp))
                                        TextButton(onClick = { help = !help }, modifier = Modifier.testTag("example-help-$index")) { Text(if (help) "Hide reading & words" else "Reading & words") }
                                        if (help) {
                                            Text(example.reading, Modifier.testTag("example-reading-$index"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            if (example.pronunciation != example.reading) Text(example.pronunciation, style = MaterialTheme.typography.bodyMedium)
                                            example.words.forEach { word ->
                                                Text(word.word + " · " + word.reading, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                                                Text(word.meaning)
                                            }
                                        }
                                    }
                                }
                                item { OutlinedButton(onClick = { selectedTab = 3 }) { Text("Try a quick question") } }
                            }
                            1 -> value.grammar.forEach { point -> item { Text(point.text, style = MaterialTheme.typography.titleMedium); Text(point.explanation) } }
                            2 -> {
                                if (line.languageTag.startsWith("ja")) item { Text("JLPT levels are approximate AI estimates.", style = MaterialTheme.typography.bodySmall) }
                                value.vocabulary.forEach { word -> item {
                                    Text(word.word, style = MaterialTheme.typography.titleMedium)
                                    if (word.reading.isNotBlank()) Text(word.reading, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(word.meaning)
                                    if (line.languageTag.startsWith("ja") && word.level.isNotBlank()) Text("Approx. " + word.level, style = MaterialTheme.typography.labelSmall)
                                } }
                            }
                            else -> value.exercises.forEachIndexed { index, exercise -> item {
                                var revealed by rememberSaveable(line.id, exercise.question) { mutableStateOf(false) }
                                var choice by rememberSaveable(line.id, exercise.question) { mutableStateOf<String?>(null) }
                                var hint by rememberSaveable(line.id, exercise.question) { mutableStateOf(false) }
                                Text("Quick question" + if (value.exercises.size > 1) " " + (index + 1) else "", style = MaterialTheme.typography.labelSmall)
                                Text(exercise.question, style = MaterialTheme.typography.bodyLarge)
                                exercise.choices.forEach { option ->
                                    OutlinedButton(onClick = { choice = option }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(option) }
                                }
                                if (choice != null) Text(if (choice == exercise.answer) "That fits." else "Not quite. Try another, or open the hint.", Modifier.padding(top = 8.dp).testTag("practice-feedback"))
                                if (exercise.hint.isNotBlank()) {
                                    TextButton(onClick = { hint = !hint }) { Text(if (hint) "Hide hint" else "Need a hint?") }
                                    if (hint) Text(exercise.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide answer" else "Show answer") }
                                if (revealed || choice == exercise.answer) { Text(exercise.answer, style = MaterialTheme.typography.titleMedium); Text(exercise.answerReading); Text(exercise.explanation) }
                            } }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (model.lessonEditable) OutlinedButton(onClick = model::editLesson, enabled = !model.busy, modifier = Modifier.height(48.dp).testTag("edit-lesson")) {
                    Icon(AppIcons.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Edit")
                }
                Button(onClick = { model.toggleSaved(line) }, enabled = !model.busy, modifier = Modifier.weight(1f).height(48.dp).testTag("save-sentence")) {
                    Icon(if (isSaved) AppIcons.Check else AppIcons.Bookmark, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (isSaved) "Saved · remove" else "Save sentence")
                }
            }
        }
    }
}
