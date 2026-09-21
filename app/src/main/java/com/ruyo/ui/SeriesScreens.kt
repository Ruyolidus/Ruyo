package com.ruyo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ruyo.reader.TextLanguages

@Composable
internal fun SeriesNameDialog(initial: String = "", dismiss: () -> Unit, save: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (initial.isBlank()) "New series" else "Rename series") },
        text = { OutlinedTextField(title, { title = it.take(120) }, label = { Text("Series name") }, placeholder = { Text("The title of your comic") }, singleLine = true, modifier = Modifier.testTag("series-name")) },
        confirmButton = { TextButton(onClick = { save(title); dismiss() }, enabled = title.isNotBlank(), modifier = Modifier.testTag("save-series")) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
internal fun SeriesPicker(model: RuyoModel, selected: String?, enabled: Boolean, choose: (String?) -> Unit, create: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("series-picker")) {
            Text("Series: " + (model.series.firstOrNull { it.id == selected }?.title ?: "Ungrouped"), maxLines = 2)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Ungrouped") }, onClick = { choose(null); expanded = false })
            model.series.forEach { group -> DropdownMenuItem(text = { Text(group.title) }, onClick = { choose(group.id); expanded = false }) }
            if (create != null) DropdownMenuItem(text = { Text("New series…") }, onClick = { expanded = false; create() })
        }
    }
}

@Composable
internal fun SeriesScreen(model: RuyoModel, add: () -> Unit) {
    val group = model.activeSeries ?: return
    val books = model.orderedChapters(group.id)
    var rename by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("series-screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("${books.size} chapters", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { rename = true }, enabled = !model.busy) { Text("Rename") }
                TextButton(onClick = { remove = true }, enabled = !model.busy) { Text("Remove series") }
            }
            Button(onClick = add, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("add-series-chapter")) { Text("Add chapter") }
        }
        if (books.isEmpty()) item { Text("Import images, a PDF, a CBZ, or pages from a website. Each import becomes a chapter in this series.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { model.openBook(book) }.padding(vertical = 12.dp).testTag("series-chapter-" + book.id)) {
                    Text("${index + 1}. ${book.title}", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${book.pages.size} pages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { model.moveSeriesChapter(book.id, -1) }, enabled = !model.busy && index > 0, modifier = Modifier.testTag("chapter-up-" + book.id)) { Icon(AppIcons.Up, "Move chapter up", Modifier.size(18.dp)) }
                IconButton(onClick = { model.moveSeriesChapter(book.id, 1) }, enabled = !model.busy && index < books.lastIndex) { Icon(AppIcons.Down, "Move chapter down", Modifier.size(18.dp)) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    if (rename) SeriesNameDialog(group.title, { rename = false }, model::renameSeries)
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Remove this series?") },
        text = { Text("Its chapters will remain in your library as ungrouped chapters.") },
        confirmButton = { TextButton(onClick = { remove = false; model.removeSeries() }) { Text("Remove series") } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } })
}

@Composable
internal fun PreparationScreen(model: RuyoModel) {
    val book = model.chapter ?: return
    val done = model.preparationReports.size
    val review = model.preparationReports.count { it.value.needsReview }
    Column(Modifier.fillMaxSize().testTag("chapter-preparation")) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(book.title, style = MaterialTheme.typography.titleLarge)
                Text("$done / ${book.pages.size} pages processed", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { done.toFloat() / book.pages.size }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
                Text(model.preparationError ?: model.preparationStatus, color = if (model.preparationError == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                Text("Target: " + TextLanguages.label(model.targetLanguage), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = model::editProfiles, enabled = !model.preparationRunning) { Text(model.activeProfile?.name ?: "Choose AI provider") }
                Text("Keep Ruyo open while preparing. You can pause and resume; completed translations are saved. Original pages are always available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (review > 0) Text("$review pages have lettering that needs review. Artwork, gradients, or undetected text can remain in the original language.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(book.pages, key = { _, p -> p.id }) { index, page ->
                val report = model.preparationReports[page.id]
                if (report != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Page ${index + 1}", style = MaterialTheme.typography.titleSmall)
                        Text(report.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (report.needsReview && !model.preparationRunning) TextButton(onClick = { model.reviewPreparedPage(page.id) }) { Text("Review") }
                }
            }
        }
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.preparationRunning) OutlinedButton(onClick = { model.pausePreparation() }, modifier = Modifier.fillMaxWidth().testTag("pause-preparation")) { Text("Pause preparation") }
            else if (done < book.pages.size) Button(onClick = model::startPreparation, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("resume-preparation")) { Text("Resume preparation") }
            Button(onClick = model::finishPreparation, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("read-prepared-chapter")) {
                Text(if (model.preparationRunning || done < book.pages.size) "Pause and read now" else "Read chapter")
            }
        }
    }
}
