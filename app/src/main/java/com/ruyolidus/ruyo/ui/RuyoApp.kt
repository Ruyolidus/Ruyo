package com.ruyolidus.ruyo.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruyolidus.ruyo.importer.ImageImporter
import com.ruyolidus.ruyo.importer.ImportedImage
import com.ruyolidus.ruyo.sample.SampleChapter
import com.ruyolidus.ruyo.sample.SampleLine
import com.ruyolidus.ruyo.sample.SamplePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RuyoApp() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("reading", 0) }
    var screen by rememberSaveable { mutableStateOf("home") }
    var imageUri by rememberSaveable { mutableStateOf("") }
    var japanese by rememberSaveable { mutableStateOf(true) }
    var selectedLineId by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(preferences.getStringSet("savedSampleLines", emptySet())!!.toSet()) }
    val chapter by produceState<Result<List<SamplePage>>?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { runCatching { SampleChapter.build() } }
    }
    val imported by produceState<Result<ImportedImage>?>(initialValue = null, imageUri) {
        value = null
        if (imageUri.isNotBlank()) {
            value = withContext(Dispatchers.IO) {
                runCatching { ImageImporter.load(context, Uri.parse(imageUri)) }
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            imageUri = uri.toString()
            screen = "import"
        }
    }
    BackHandler(enabled = screen != "home" && selectedLineId == null) { screen = "home" }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (screen != "home") TextButton(onClick = { screen = "home" }) { Text("← Back") }
                    else Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center) {
                        Text("る", color = MaterialTheme.colorScheme.onPrimary, fontSize = 25.sp)
                    }
                    Text("Ruyo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text("PREVIEW 01", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                }
        },
    ) { padding ->
        when (screen) {
            "sample" -> SampleReader(padding, chapter, japanese, { japanese = it }, { selectedLineId = it })
            "import" -> ImportedReader(padding, imported, { picker.launch(arrayOf("image/*")) })
            else -> HomeScreen(padding, chapter?.getOrNull()?.firstOrNull(), saved.size,
                onSample = { screen = "sample" }, onImport = { picker.launch(arrayOf("image/*")) },
                onSaved = { screen = "saved" }, savedOnly = screen == "saved", saved = saved,
                onLesson = { selectedLineId = it })
        }
    }

    val selected = SampleChapter.lines.find { it.id == selectedLineId }
    if (selected != null) LessonSheet(selected, selected.id in saved,
        onSave = {
            saved = if (selected.id in saved) saved - selected.id else saved + selected.id
            preferences.edit().putStringSet("savedSampleLines", saved).apply()
        }, onDismiss = { selectedLineId = null })
}

@Composable
private fun HomeScreen(
    padding: PaddingValues, cover: SamplePage?, savedCount: Int,
    onSample: () -> Unit, onImport: () -> Unit, onSaved: () -> Unit,
    savedOnly: Boolean, saved: Set<String>, onLesson: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Text(if (savedOnly) "Keep the words.\nKeep the story." else "A story first.\nJapanese follows.",
                style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(if (savedOnly) "Your saved sample sentences, ready to revisit."
                else "Read inside the artwork. Tap a line when you want to understand a little more.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        if (savedOnly) {
            if (saved.isEmpty()) item {
                NoteCard("Your collection starts with one line", "Open the sample story, tap a speech bubble, and save a sentence.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onSample) { Text("Read the sample") }
            }
            itemsIndexed(SampleChapter.lines.filter { it.id in saved }, key = { _, line -> line.id }) { _, line ->
                Card(onClick = { onLesson(line.id) }, shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(22.dp)) {
                        Text(line.japanese, fontSize = 22.sp, lineHeight = 32.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(line.meaning, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            item {
                Card(onClick = onSample, shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    if (cover != null) Image(cover.translated.asImageBitmap(), contentDescription = "Original Ruyo sample artwork",
                        modifier = Modifier.fillMaxWidth().height(230.dp), contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter)
                    else Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    Column(Modifier.padding(22.dp)) {
                        Text("START HERE", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Before the rain", style = MaterialTheme.typography.titleLarge)
                        Text("3 panels · beginner-friendly explanations", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = onSample, modifier = Modifier.fillMaxWidth()) { Text("Open sample story  →") }
                    }
                }
            }
            item {
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                    Text("＋  Open an image")
                }
                Spacer(Modifier.height(10.dp))
                Text("Image preview is available. Translation for your own images is coming next.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(onClick = onSaved, shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Your saved lines", style = MaterialTheme.typography.titleMedium)
                            Text("$savedCount sentences to revisit", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("↗", fontSize = 28.sp)
                    }
                }
            }
            item { Text("This preview works offline. Sample dialogue and lessons are prewritten; no AI requests are made.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SampleReader(
    padding: PaddingValues, result: Result<List<SamplePage>>?, japanese: Boolean,
    onLanguage: (Boolean) -> Unit, onLesson: (String) -> Unit,
) {
    if (result == null) { Loading(padding, "Preparing your sample story…"); return }
    val pages = result.getOrNull()
    if (pages == null) {
        Box(Modifier.padding(padding).padding(24.dp)) { NoteCard("The sample could not be prepared", "Please reopen the app and try again.") }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Before the rain", style = MaterialTheme.typography.headlineMedium)
                Text("Prewritten sample · tap a Japanese bubble to learn", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = japanese, onClick = { onLanguage(true) }, label = { Text("日本語") })
                    FilterChip(selected = !japanese, onClick = { onLanguage(false) }, label = { Text("Original") })
                }
            }
        }
        itemsIndexed(pages, key = { _, page -> page.line.id }) { index, page ->
            val bitmap = if (japanese) page.translated else page.original
            Column(Modifier.padding(horizontal = 16.dp)) {
                Image(bitmap.asImageBitmap(), contentDescription = "Panel ${index + 1}. ${if (japanese) page.line.japanese else page.line.original}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)
                        .clip(RoundedCornerShape(20.dp))
                        .pointerInput(page.line.id, japanese) {
                            detectTapGestures { tap ->
                                val scale = size.width.toFloat() / bitmap.width
                                val x = tap.x / scale - page.bubbleBounds.left
                                val y = tap.y / scale - page.bubbleBounds.top
                                if (japanese && x >= 0f && y >= 0f && page.interior[x.toInt(), y.toInt()]) onLesson(page.line.id)
                            }
                        }
                        .semantics {
                            if (japanese) onClick(label = "Study this Japanese sentence") { onLesson(page.line.id); true }
                        })
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1} / ${pages.size}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (japanese) TextButton(onClick = { onLesson(page.line.id) }) { Text("Study this line  ↗") }
                    else TextButton(onClick = { onLanguage(true) }) { Text("Read in Japanese") }
                }
            }
        }
        item { Text("End of the sample. Every saved sentence is one you can return to.",
            Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ImportedReader(padding: PaddingValues, result: Result<ImportedImage>?, onOpen: () -> Unit) {
    if (result == null) { Loading(padding, "Opening your image…"); return }
    val image = result.getOrNull()
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text(image?.name ?: "Could not open this image", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Text(if (image != null) "Preview only. Automatic bubble detection and translation are not available for imported images in this build."
                else "Choose a supported image that is available on your device.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (image != null) item {
            Image(image.bitmap.asImageBitmap(), contentDescription = image.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(image.bitmap.width.toFloat() / image.bitmap.height),
                contentScale = ContentScale.FillWidth)
            if (image.reducedForPreview) Text("This image was reduced for a memory-friendly preview.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
        item { OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Choose another image") } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LessonSheet(line: SampleLine, saved: Boolean, onSave: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text("A CLOSER LOOK", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(12.dp))
                Text(line.japanese, fontSize = 28.sp, lineHeight = 42.sp)
                Text(line.reading, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(line.meaning, style = MaterialTheme.typography.bodyLarge)
            }
            item { HorizontalDivider(); Spacer(Modifier.height(18.dp)); Text("How the sentence works", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp)); Text(line.grammar, style = MaterialTheme.typography.bodyLarge) }
            item {
                Text("Words to keep", fontWeight = FontWeight.SemiBold)
                line.vocabulary.forEach { (word, meaning) ->
                    Spacer(Modifier.height(12.dp))
                    Text(word, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    Text(meaning, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { NoteCard("One more example", line.example) }
            item {
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text(if (saved) "Saved · tap to remove" else "Save this sentence") }
                Spacer(Modifier.height(12.dp))
                Text("Prewritten sample explanation for the exact Japanese shown in this bubble.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NoteCard(title: String, body: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Loading(padding: PaddingValues, message: String) {
    Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(message)
    }
}
