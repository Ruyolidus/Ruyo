package com.ruyo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ruyo.ai.*
import com.ruyo.reader.TextLanguages

@Composable
internal fun ProviderProfilesScreen(model: RuyoModel) {
    var editing by remember { mutableStateOf<ProviderProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ProviderProfile?>(null) }
    val formOpen = editing != null || creating
    BackHandler(formOpen) { if (!model.busy) { editing = null; creating = false } }
    if (formOpen) {
        ProviderForm(model, editing, onDone = { editing = null; creating = false })
    } else LazyColumn(Modifier.fillMaxSize().testTag("provider-profiles"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Your AI providers", style = MaterialTheme.typography.titleLarge)
            Text("Choose which profile translates your selected text. Keys stay encrypted on this device; requests go directly to the provider you choose.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        model.profileError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        items(model.profiles, key = { it.id }) { profile ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clickable(enabled = !model.busy) { model.selectProfile(profile.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = model.activeProfileId == profile.id, onClick = { model.selectProfile(profile.id) }, enabled = !model.busy)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                        Text(profile.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(java.net.URI(profile.baseUrl).host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editing = profile }, enabled = !model.busy) { Text("Edit") }
                    TextButton(onClick = { removing = profile }, enabled = !model.busy) { Text("Remove") }
                }
            }
        }
        item { Button(onClick = { creating = true }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("add-provider")) { Text("Add provider") } }
        item { Text("OpenAI, DeepSeek and other compatible APIs, Claude, Gemini, and local compatible servers are supported. Model access and charges depend on your provider account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    removing?.let { profile ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove " + profile.name + "?") },
            text = { Text("This removes its saved key from Ruyo. Your chapters and translations remain available.") },
            confirmButton = { TextButton(onClick = { model.deleteProfile(profile.id); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ProviderForm(model: RuyoModel, previous: ProviderProfile?, onDone: () -> Unit) {
    var name by remember(previous?.id) { mutableStateOf(previous?.name ?: "") }
    var kind by remember(previous?.id) { mutableStateOf(previous?.kind ?: ProviderKind.OPENAI) }
    var endpoint by remember(previous?.id) { mutableStateOf(previous?.baseUrl ?: ProviderKind.OPENAI.endpoint) }
    var modelId by remember(previous?.id) { mutableStateOf(previous?.model ?: "") }
    var requestTimeout by remember(previous?.id) { mutableStateOf(previous?.requestTimeout ?: RequestTimeout.DEFAULT) }
    var timeoutMenu by remember { mutableStateOf(false) }
    // Deliberately never Saveable: entered keys are neither persisted nor restored as UI state.
    var apiKey by remember(previous?.id) { mutableStateOf("") }
    var removeKey by remember(previous?.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("provider-form"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(if (previous == null) "Add provider" else "Edit provider", style = MaterialTheme.typography.titleLarge) }
        item {
            Box {
                OutlinedButton(onClick = { menu = true }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text(kind.label) }
                DropdownMenu(menu, { menu = false }) {
                    ProviderKind.entries.forEach { type -> DropdownMenuItem(text = { Text(type.label) }, onClick = { kind = type; endpoint = type.endpoint; menu = false; apiKey = ""; removeKey = false }) }
                }
            }
        }
        item {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("Profile name") }, placeholder = { Text("My translation model") }, singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("provider-name"))
        }
        item {
            OutlinedTextField(endpoint, { endpoint = it.take(512) }, label = { Text("API base URL") }, singleLine = true, enabled = !model.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("Include the API version path, such as /v1. DeepSeek: https://api.deepseek.com/v1") }, modifier = Modifier.fillMaxWidth().testTag("provider-endpoint"))
        }
        if (kind == ProviderKind.COMPATIBLE) item {
            TextButton(onClick = { endpoint = "http://127.0.0.1:11434/v1"; removeKey = true; apiKey = "" }, enabled = !model.busy) { Text("Use a local model on this phone") }
            Text("Local servers must offer an OpenAI-compatible chat API. Remote servers require HTTPS.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(modelId, { modelId = it.take(120) }, label = { Text("Model ID") }, singleLine = true, enabled = !model.busy,
                supportingText = { Text("Enter a text or chat model ID from your provider.") }, modifier = Modifier.fillMaxWidth().testTag("provider-model"))
        }
        item {
            Text("Request timeout", style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { timeoutMenu = true }, enabled = !model.busy,
                    modifier = Modifier.fillMaxWidth().testTag("provider-timeout")) { Text(requestTimeout.label) }
                DropdownMenu(timeoutMenu, { timeoutMenu = false }) {
                    RequestTimeout.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { requestTimeout = option; timeoutMenu = false },
                            modifier = Modifier.testTag("provider-timeout-${option.name}"))
                    }
                }
            }
            Text(if (requestTimeout == RequestTimeout.UNLIMITED)
                "Wait for each translation or explanation until you cancel. Your server may still enforce its own time limit."
                else "Time allowed for each translation or explanation, including model loading. Increase this for slower local models.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Connecting to the server has a separate 30-second limit.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(apiKey, { apiKey = it.take(4096); removeKey = false }, label = { Text(if (previous?.hasKey == true) "Replacement API key" else "API key") }, singleLine = true, enabled = !model.busy,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                supportingText = { Text(if (previous?.hasKey == true) "Leave blank to keep the saved key. Re-enter it if changing the provider or address." else if (kind == ProviderKind.COMPATIBLE) "Optional only when your server allows requests without a key." else "Stored with Android Keystore encryption.") },
                modifier = Modifier.fillMaxWidth().testTag("provider-key"))
        }
        if (kind == ProviderKind.COMPATIBLE && previous?.hasKey == true) item {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(removeKey, { removeKey = it; if (it) apiKey = "" }); Text("Remove the saved key") }
        }
        model.profileError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        item {
            Button(onClick = {
                val profile = ProviderProfile(id = previous?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim(), kind = kind, baseUrl = endpoint.trim(), model = modelId.trim(), requestTimeout = requestTimeout)
                model.saveProfile(profile, apiKey.takeIf { it.isNotBlank() }, removeKey) { apiKey = ""; onDone() }
            }, enabled = !model.busy && name.isNotBlank() && endpoint.isNotBlank() && modelId.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("save-provider")) { Text("Save profile") }
            TextButton(onClick = { apiKey = ""; onDone() }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
internal fun AiEditorControls(model: RuyoModel, draft: EditorDraft) {
    var expanded by remember(draft.edit.id) { mutableStateOf(false) }
    var scriptMenu by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }
    val working = model.busy || model.aiStatus != null
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        model.aiStatus?.let { status ->
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = model::cancelAi) { Text("Cancel") }
            }
        }
        model.aiError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ai-error")) }
        if (model.activeProfile == null) {
            OutlinedButton(onClick = model::editProfiles, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("Choose an AI provider") }
            Text("Choose a provider once. New bubbles will translate and preview automatically when opened.", style = MaterialTheme.typography.bodySmall)
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(model.activeProfile?.name.orEmpty(), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = model::translateText, enabled = !working, modifier = Modifier.testTag("translate-source")) { Text(if (draft.edit.japanese.isBlank()) "Translate and preview" else "Translate again") }
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("toggle-ai")) { Text(if (expanded) "Hide source and provider" else "Source and provider") }
        if (expanded) {
            Box {
                OutlinedButton(onClick = { scriptMenu = true }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("Source script: " + model.ocrScript.label) }
                DropdownMenu(scriptMenu, { scriptMenu = false }) {
                    OcrScript.entries.forEach { script -> DropdownMenuItem(text = { Text(script.label) }, onClick = { model.changeOcrScript(script); scriptMenu = false }) }
                }
            }
            OutlinedButton(onClick = model::recognizeText, enabled = !working, modifier = Modifier.fillMaxWidth().testTag("recognize-source")) { Text("Recognize original text") }
            OutlinedTextField(draft.sourceText, model::changeSourceText, label = { Text("Original text") }, minLines = 2, maxLines = 5, enabled = !working,
                supportingText = { Text("Check recognition before translating. Other source scripts can be entered manually.") }, modifier = Modifier.fillMaxWidth().testTag("source-text"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { profileMenu = true }, enabled = !working && model.profiles.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(model.activeProfile?.name ?: "Choose provider") }
                    DropdownMenu(profileMenu, { profileMenu = false }) {
                        model.profiles.forEach { profile -> DropdownMenuItem(text = { Text(profile.name) }, onClick = { model.selectProfile(profile.id); profileMenu = false }) }
                    }
                }
                TextButton(onClick = model::editProfiles, enabled = !working) { Text("Manage") }
            }
            model.activeProfile?.let { profile ->
                Text("Sends this text to " + java.net.URI(profile.baseUrl).host + " using " + profile.model + ". Provider charges may apply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

        }
    }
}
