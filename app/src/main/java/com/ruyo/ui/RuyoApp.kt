package com.ruyo.ui

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ruyo.data.LocalBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RuyoApp(model: RuyoModel = viewModel()) {
    RuyoTheme(model.theme) { AppContent(model) }
}

@Composable
private fun AppContent(model: RuyoModel) {
    val view = LocalView.current
    val dark = model.theme == "dark" || model.theme == "system" && isSystemInDarkTheme()
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val snackbars = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importImage) }
    var discard by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<LocalBook?>(null) }
    fun back() { if (model.route == "editor") discard = true else model.home() }
    BackHandler(model.route != "home" && model.lesson == null) { if (!model.busy) back() }
    LaunchedEffect(model.message) {
        model.message?.let { message ->
            model.message = null
            snackbars.showSnackbar(message, withDismissAction = true)
        }
    }
    val home = model.route == "home"
    Scaffold(
        modifier = Modifier.testTag("app-root"),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            Column {
                AppBar(
                    title = when (model.route) {
                        "sample" -> "Before the rain"
                        "book" -> model.opened?.book?.title.orEmpty()
                        "editor" -> "Edit bubble"
                        else -> when (model.tab) { "saved" -> "Saved"; "settings" -> "Settings"; else -> "Library" }
                    },
                    back = if (home) null else ({ if (!model.busy) back() }),
                    actions = {
                        when {
                            home && model.tab == "library" -> IconButton(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !model.busy) { Icon(AppIcons.Plus, "Import image") }
                            model.route == "book" -> {
                                IconButton(onClick = { model.selecting = !model.selecting }, enabled = !model.busy) { Icon(if (model.selecting) AppIcons.Close else AppIcons.Edit, if (model.selecting) "Cancel selection" else "Edit bubbles") }
                                IconButton(onClick = { remove = model.opened?.book }, enabled = !model.busy) { Icon(AppIcons.Trash, "Remove image") }
                            }
                            model.route == "editor" -> TextButton(onClick = model::saveEdit, enabled = model.draft?.preview != null && !model.busy, modifier = Modifier.testTag("save-edit")) { Text("Save") }
                        }
                    },
                )
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                else HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        bottomBar = {
            if (home) Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf(Triple("library", "Library", AppIcons.Library), Triple("saved", "Saved", AppIcons.Bookmark), Triple("settings", "Settings", AppIcons.Settings)).forEach { (id, title, icon) ->
                        NavigationBarItem(selected = model.tab == id, onClick = { model.tab = id },
                            icon = { Icon(icon, null, Modifier.size(22.dp)) }, label = { Text(title) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surface,
                                selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("nav-$id"))
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (model.route) {
                "sample" -> SampleReader(model)
                "book" -> BookReader(model)
                "editor" -> model.draft?.let { BubbleEditorScreen(model, it) }
                else -> when (model.tab) {
                    "saved" -> SavedScreen(model)
                    "settings" -> SettingsScreen(model)
                    else -> LibraryScreen(model) { picker.launch(arrayOf("image/*")) }
                }
            }
        }
    }
    model.lesson?.let { StudySheet(model, it) }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard this edit?") },
        text = { Text("Changes since your last save will be lost. The original image is kept.") },
        confirmButton = { TextButton(onClick = { discard = false; model.cancelEditor() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
    remove?.let { book -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove image?") },
        text = { Text("Remove this image and its bubble edits from Ruyo? The file you imported from your device will stay where it is.") },
        confirmButton = { TextButton(onClick = { remove = null; model.removeBook(book) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } }) }
}

@Composable
internal fun AppBar(title: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 60.dp).padding(start = if (back == null) 20.dp else 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (back != null) IconButton(onClick = back) { Icon(AppIcons.Back, "Back") }
            Text(title, style = if (back == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
private fun LibraryScreen(model: RuyoModel, onImport: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val books = model.books.filter { it.title.contains(query, ignoreCase = true) }
    val sampleVisible = filter != "Imported" && "Before the rain".contains(query, ignoreCase = true)
    LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), modifier = Modifier.fillMaxSize().testTag("library-screen"),
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${model.books.size + 1} ${if (model.books.isEmpty()) "title" else "titles"}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }, modifier = Modifier.size(40.dp)) { Icon(if (searching) AppIcons.Close else AppIcons.Search, if (searching) "Close search" else "Search library", Modifier.size(20.dp)) }
                }
                if (searching) OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search titles") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Imported", "Samples").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }, shape = RoundedCornerShape(6.dp)) }
                }
            }
        }
        if (!model.ready) item(span = { GridItemSpan(maxLineSpan) }) {
            if (model.busy) LoadingState("Opening library") else EmptyState(AppIcons.Library, "Library unavailable", "Please try loading your library again.") { TextButton(onClick = model::refresh) { Text("Retry") } }
        }
        if (sampleVisible && model.ready) item(key = "sample") {
            BookCover(model.samples.firstOrNull()?.translated, "Before the rain", "Sample · 3 panels", "book-sample", onClick = model::openSample)
        }
        if (filter != "Samples" && model.ready) items(books, key = { it.id }) { book ->
            val cover by produceState<Bitmap?>(null, book.id) { value = withContext(Dispatchers.IO) { model.store.thumbnail(book.id) } }
            BookCover(cover, book.title, "Imported image", "book-${book.id}") { model.openBook(book) }
        }
        if (model.ready && (filter == "Imported" && books.isEmpty() || query.isNotBlank() && books.isEmpty() && !sampleVisible)) item(span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(AppIcons.Image, if (query.isBlank()) "No imported images" else "No matching titles",
                if (query.isBlank()) "Add a comic image from your device to start editing its bubbles." else "Try another title.") {
                if (query.isBlank()) Button(onClick = onImport, enabled = !model.busy) { Icon(AppIcons.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Import image") }
            }
        }
        if (model.books.isEmpty() && filter == "All" && query.isBlank() && model.ready) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Add your own comic", style = MaterialTheme.typography.titleMedium)
                Text("Import an image to select bubbles and try your own Japanese text.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onImport, enabled = !model.busy) { Icon(AppIcons.Plus, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Import image") }
            }
        }
    }
}

@Composable
private fun BookCover(bitmap: Bitmap?, title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    Column(Modifier.testTag(tag).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(AppIcons.Image, null, Modifier.size(28.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), shape = RoundedCornerShape(3.dp)) {
                Text(if (tag == "book-sample") "SAMPLE" else "LOCAL", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun SavedScreen(model: RuyoModel) {
    if (model.saved.isEmpty()) {
        EmptyState(AppIcons.Bookmark, "No saved sentences", "Tap a Japanese bubble while reading, then save the sentence to revisit it here.") {
            OutlinedButton(onClick = model::openSample, enabled = model.ready) { Text("Read sample") }
        }
    } else LazyColumn(Modifier.fillMaxSize().testTag("saved-screen"), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Text("${model.saved.size} ${if (model.saved.size == 1) "sentence" else "sentences"}", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(model.saved, key = { it.id }) { line ->
            Column(Modifier.fillMaxWidth().clickable { model.lesson = line }.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(line.japanese, fontSize = 20.sp, lineHeight = 30.sp)
                Text(line.source, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SettingsScreen(model: RuyoModel) {
    LazyColumn(Modifier.fillMaxSize().testTag("settings-screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            SectionLabel("Appearance")
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column {
                    listOf("system" to "Use device setting", "light" to "Light", "dark" to "Dark").forEachIndexed { index, (id, label) ->
                        Row(Modifier.fillMaxWidth().clickable { model.changeTheme(id) }.testTag("theme-$id").padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = model.theme == id, onClick = { model.changeTheme(id) })
                            Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                        if (index < 2) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        item {
            SectionLabel("On this device")
            SettingLine("Imported images", model.books.size.toString())
            SettingLine("Saved sentences", model.saved.size.toString())
            Text("Images and edits are stored on this device. Your source files are kept intact.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionLabel("About")
            SettingLine("Ruyo", "0.2.0 preview")
            Text("Read comics. Study Japanese.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("This build includes manual bubble editing and sample lessons. AI translation and website imports are being developed.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp)) }
@Composable private fun SettingLine(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable internal fun LoadingState(label: String) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp); Text(label, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyMedium) } } }
@Composable internal fun EmptyState(icon: ImageVector, title: String, body: String, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(4.dp)); action()
    }
}
