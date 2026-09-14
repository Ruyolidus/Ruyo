package com.ruyo.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ruyo.data.LocalPage
import com.ruyo.importer.NaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ImportReviewScreen(model: RuyoModel, onAdd: () -> Unit) {
    val draft = model.importing ?: return
    Column(Modifier.fillMaxSize().testTag("import-review")) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if (draft.appendTo == null) "Review your chapter" else "Add pages to chapter", style = MaterialTheme.typography.titleMedium)
                Text("Check the image order before saving. Read from top to bottom.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { OutlinedTextField(draft.title, model::renameImport, enabled = !model.busy, label = { Text("Chapter title") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("chapter-title")) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${draft.pages.size} images", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = model::sortImport, enabled = !model.busy && draft.pages.size > 1) { Text("Sort by name") }
                    IconButton(onClick = onAdd, enabled = !model.busy) { Icon(AppIcons.Plus, "Add more images") }
                }
            }
            if (draft.failures.isNotEmpty()) item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(6.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${draft.failures.size} images could not be imported", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        draft.failures.take(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                        Text("Use + to retry those images. Only the pages below will be saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            itemsIndexed(draft.pages, key = { _, page -> page.page.id }) { index, staged ->
                val thumb by produceState<Bitmap?>(null, staged.page.id) { value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(File(staged.folder, "cover.png").path) } }
                PageOrderRow(staged.page, index, draft.pages.size, thumb, !model.busy,
                    { model.moveImport(staged.page.id, it) }, { model.dropImport(staged.page.id) })
            }
            if (draft.pages.isEmpty()) item { Text("No images yet. Use + to choose images from your device.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Button(onClick = model::saveImport, enabled = draft.pages.isNotEmpty() && !model.busy && draft.title.isNotBlank(),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).height(48.dp).testTag("save-chapter")) {
                Text(if (draft.appendTo == null) "Save chapter" else "Add ${draft.pages.size} images")
            }
        }
    }
}

@Composable
internal fun ChapterPagesScreen(model: RuyoModel, onAppend: () -> Unit) {
    val book = model.chapter ?: return
    var title by remember(book) { mutableStateOf(book.title) }
    var pages by remember(book) { mutableStateOf(book.pages) }
    var remove by remember { mutableStateOf<LocalPage?>(null) }
    Column(Modifier.fillMaxSize().testTag("chapter-pages")) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(title, { title = it.take(120) }, enabled = !model.busy, label = { Text("Chapter title") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${pages.size} pages", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { pages = pages.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) } }, enabled = !model.busy && pages.size > 1) { Text("Sort by name") }
                }
            }
            itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
                val thumb by produceState<Bitmap?>(null, page.id) { value = withContext(Dispatchers.IO) { model.store.pageThumbnail(book, page) } }
                PageOrderRow(page, index, pages.size, thumb, !model.busy, { delta ->
                    val list = pages.toMutableList(); val target = index + delta
                    if (target in list.indices) { list.add(target, list.removeAt(index)); pages = list }
                }, if (pages.size > 1) ({ remove = page }) else null)
            }
            item {
                OutlinedButton(onClick = onAppend, enabled = !model.busy && pages == book.pages && title == book.title, modifier = Modifier.fillMaxWidth()) { Icon(AppIcons.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add more images") }
                if (pages != book.pages || title != book.title) Text("Save your changes before adding more images.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        Surface {
            Button(onClick = { model.updateChapter(title, pages.map { it.id }) }, enabled = !model.busy && title.isNotBlank(),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).height(48.dp).testTag("save-page-order")) { Text("Save changes") }
        }
    }
    remove?.let { page -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove this page?") }, text = { Text("Its bubble edits will be removed when you save the chapter changes.") },
        confirmButton = { TextButton(onClick = { pages = pages.filterNot { it.id == page.id }; remove = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } }) }
}

@Composable
private fun PageOrderRow(page: LocalPage, index: Int, total: Int, thumbnail: Bitmap?, enabled: Boolean, move: (Int) -> Unit, remove: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().testTag("page-row-${page.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(60.dp).height(82.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (thumbnail != null) Image(thumbnail.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(AppIcons.Image, null, Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("${index + 1}. ${page.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${page.width} × ${page.height}${if (page.reduced) " · Working copy" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            IconButton(onClick = { move(-1) }, enabled = enabled && index > 0, modifier = Modifier.size(40.dp).testTag("move-up-${page.id}")) { Icon(AppIcons.Up, "Move page ${index + 1} up", Modifier.size(18.dp)) }
            IconButton(onClick = { move(1) }, enabled = enabled && index < total - 1, modifier = Modifier.size(40.dp).testTag("move-down-${page.id}")) { Icon(AppIcons.Down, "Move page ${index + 1} down", Modifier.size(18.dp)) }
        }
        if (remove != null) IconButton(onClick = remove, enabled = enabled, modifier = Modifier.size(40.dp).testTag("remove-page-${page.id}")) { Icon(AppIcons.Close, "Remove page ${index + 1}", Modifier.size(18.dp)) }
    }
    HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
