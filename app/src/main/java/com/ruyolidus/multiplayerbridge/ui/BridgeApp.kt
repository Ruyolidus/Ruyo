package com.ruyolidus.multiplayerbridge.ui

import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ruyolidus.multiplayerbridge.BridgeModel
import com.ruyolidus.multiplayerbridge.LibraryUiState
import com.ruyolidus.multiplayerbridge.data.ImportedGame

@Composable
fun BridgeApp(model: BridgeModel = viewModel()) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importGame)
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model.notice) {
        model.notice?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            model.clearNotice()
        }
    }
    BridgeScreen(model.state, snackbar, { picker.launch(arrayOf("*/*")) }, model::remove, model::refresh)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen(
    state: LibraryUiState,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onImport: () -> Unit,
    onRemove: (ImportedGame) -> Unit,
    onRetry: () -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ImportedGame?>(null) }
    val selected = state.games.find { it.id == selectedId }
    val canImport = !state.loading && !state.importing && state.error == null
    BackHandler(selected != null) { selectedId = null }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("bridge-root"),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).widthIn(max = 760.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected != null) {
                    IconButton(onClick = { selectedId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to games")
                    }
                    Text("Game details", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                } else {
                    Box(Modifier.padding(start = 12.dp, end = 12.dp)) { BridgeMark(32) }
                    Text("Multiplayer Bridge", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = { about = true }) { Icon(Icons.Default.Info, "About this build") }
            }
            if (state.importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Updating your library…", Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (selected != null) {
                GameDetails(selected, !state.importing) { removing = selected }
            } else {
                val filtered = state.games.filter {
                    it.app.label.contains(query, ignoreCase = true) || it.app.packageName.contains(query, ignoreCase = true)
                }
                LazyColumn(
                    Modifier.fillMaxSize().testTag("game-library"),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Your games", style = MaterialTheme.typography.headlineLarge)
                                Text("Saved on this phone", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            if (state.games.isNotEmpty()) {
                                FilledTonalIconButton(onClick = onImport, enabled = canImport, modifier = Modifier.size(52.dp)) {
                                    Icon(Icons.Default.Add, "Import game")
                                }
                            }
                        }
                    }
                    if (state.loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(56.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (state.error != null) {
                        item {
                            NoticeCard("Couldn’t open your library", state.error)
                            TextButton(onClick = onRetry) { Text("Try again") }
                        }
                    } else {
                        if (state.unreadableCount > 0) {
                            item { NoticeCard("Some files need attention", "${state.unreadableCount} saved file(s) could not be read. The original files have not been removed.") }
                        }
                        if (state.games.isEmpty()) {
                            item { EmptyLibrary(canImport, onImport) }
                        } else {
                            item {
                                OutlinedTextField(
                                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("Find a game") }, singleLine = true,
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    trailingIcon = if (query.isNotEmpty()) {{
                                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") }
                                    }} else null,
                                    shape = RoundedCornerShape(18.dp),
                                )
                            }
                            items(filtered, key = { it.id }) { game ->
                                GameRow(game) { selectedId = game.id }
                            }
                            if (filtered.isEmpty()) item {
                                Text("No games match your search.", Modifier.padding(vertical = 24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            NoticeCard("This first build", "Import and organize your game files. Playing them together in a room is the next milestone.")
                        }
                    }
                }
            }
        }
    }
    removing?.let { game ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove this saved game?") },
            text = { Text("Bridge’s copy of ${game.app.label} will be removed. The file you originally selected stays on your phone.") },
            confirmButton = {
                TextButton(onClick = { onRemove(game); removing = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep game") } },
        )
    }
    if (about) {
        ModalBottomSheet(onDismissRequest = { about = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("A place to play together", style = MaterialTheme.typography.headlineMedium)
                Text("Bring an offline game, invite someone, and take your turns together. That’s what we’re building.")
                NoticeCard("Available now", "APK import, game details, search, and a library that stays saved when you close the app.")
                Text("Shared rooms, text chat and running imported games are still in development.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text("Preview 0.1 · Android", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyLibrary(enabled: Boolean, onImport: () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(104.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center) { BridgeMark(58) }
            Spacer(Modifier.height(26.dp))
            Text("Bring your first game", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text("Choose an APK file saved on your phone. We’ll keep a separate copy in your library.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(26.dp))
            Button(onClick = onImport, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Import a game")
            }
        }
    }
}

@Composable
private fun GameRow(game: ImportedGame, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag("game-${game.id}")) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GameAvatar(game, 56)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(game.app.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(fileSize(game.bytes) + " · APK", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun GameDetails(game: ImportedGame, canRemove: Boolean, onRemove: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                GameAvatar(game, 80)
                SelectionContainer { Text(game.app.label, style = MaterialTheme.typography.headlineMedium) }
                Text("Saved in your library", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        item { NoticeCard("Ready for the next step", "This APK is saved. Opening it inside Bridge and inviting another player are not available in this build.") }
        if (game.app.minSdk > Build.VERSION.SDK_INT) item {
            NoticeCard("Needs a newer Android version", "This game requires Android API ${game.app.minSdk}. Your phone has API ${Build.VERSION.SDK_INT}.")
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    DetailField("Version", game.app.versionName.ifBlank { "Not specified" })
                    DetailField("File size", fileSize(game.bytes))
                    DetailField("Original file", game.originalName)
                    DetailField("Package", game.app.packageName)
                }
            }
        }
        item {
            OutlinedButton(onClick = onRemove, enabled = canRemove, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp)) { Text("Remove saved copy") }
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun NoticeCard(title: String, message: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GameAvatar(game: ImportedGame, size: Int) {
    Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape((size / 3).dp)),
        contentAlignment = Alignment.Center) {
        Text(game.app.label.trim().take(1).uppercase(), fontSize = (size / 2).sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun BridgeMark(size: Int) {
    val first = MaterialTheme.colorScheme.primary
    val second = if (MaterialTheme.colorScheme.background.red < .3f) Color(0xFFFFB49F) else Color(0xFFC46B4D)
    Canvas(Modifier.size(size.dp)) {
        val unit = this.size.width
        drawRoundRect(first, Offset(0f, unit * .15f), Size(unit * .34f, unit * .7f), androidx.compose.ui.geometry.CornerRadius(unit * .11f))
        drawRoundRect(second, Offset(unit * .66f, unit * .15f), Size(unit * .34f, unit * .7f), androidx.compose.ui.geometry.CornerRadius(unit * .11f))
        drawLine(first, Offset(unit * .28f, unit * .5f), Offset(unit * .72f, unit * .5f), unit * .16f)
    }
}

@Composable
private fun fileSize(bytes: Long) = Formatter.formatShortFileSize(LocalContext.current, bytes)
