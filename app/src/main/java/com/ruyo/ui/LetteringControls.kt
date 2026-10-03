package com.ruyo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ruyo.reader.BubbleEdit
import com.ruyo.reader.LetteringFont
import com.ruyo.reader.LetteringStyle
import com.ruyo.reader.TextLanguages

@Composable
internal fun LanguagePicker(language: String, onChange: (String) -> Unit, enabled: Boolean = true, tag: String = "edit-language", label: String = "Target language") {
    var expanded by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf(language) }
    var error by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text(label, Modifier.weight(1f))
            Text(TextLanguages.label(language), color = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            TextLanguages.common.forEach { item ->
                DropdownMenuItem(text = { Text(TextLanguages.label(item)) }, onClick = { expanded = false; onChange(item) }, modifier = Modifier.testTag("language-" + item))
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Other language…") }, onClick = { expanded = false; code = language; error = null; custom = true })
        }
    }
    if (custom) AlertDialog(onDismissRequest = { custom = false }, title = { Text("Choose a language") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Enter its language code, such as fon, sw, ar, or zh-Hant. Available lettering depends on the fonts on this device.")
            OutlinedTextField(code, { code = it; error = null }, label = { Text("Language code") }, singleLine = true, isError = error != null, modifier = Modifier.testTag("custom-language-code"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = {
            runCatching { TextLanguages.normalize(code) }.onSuccess { custom = false; onChange(it) }.onFailure { error = "Enter a valid language code, such as ja, fr, or ar." }
        }) { Text("Use language") } }, dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancel") } })
}

@Composable
internal fun LetteringControls(edit: BubbleEdit, model: RuyoModel) {
    var fontMenu by remember { mutableStateOf(false) }
    LanguagePicker(edit.languageTag, model::changeEditLanguage, !model.busy)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { fontMenu = true }, enabled = !model.busy, modifier = Modifier.fillMaxWidth().testTag("lettering-font")) {
            Text("Font", Modifier.weight(1f))
            Text(LetteringFont.fromId(edit.fontFamily).label, color = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(fontMenu, onDismissRequest = { fontMenu = false }) {
            LetteringFont.entries.forEach { font ->
                DropdownMenuItem(text = { Text(font.label) }, onClick = { fontMenu = false; model.changeFont(font.family) }, modifier = Modifier.testTag("font-" + font.name.lowercase()))
            }
        }
    }
    Text("Lettering", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LetteringStyle.entries.forEach { style ->
            FilterChip(edit.letteringStyle == style, { model.changeLetteringStyle(style) }, label = { Text(style.label) },
                enabled = !model.busy, modifier = Modifier.testTag("lettering-style-${style.name}"))
        }
    }
    if (edit.letteringStyle == LetteringStyle.TRANSLUCENT) {
        Text("Fill opacity · ${(edit.fillOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Slider(edit.fillOpacity, model::changeFillOpacity, enabled = !model.busy, modifier = Modifier.testTag("lettering-opacity"))
    }
    if (edit.letteringStyle == LetteringStyle.OUTLINE) Text("Hollow letters show the repaired artwork through their centers. Use a larger size for clear reading.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(edit.bold, { model.changeBold(!edit.bold) }, label = { Text("Bold") }, enabled = !model.busy, modifier = Modifier.testTag("font-bold"))
        FilterChip(edit.italic, { model.changeItalic(!edit.italic) }, label = { Text("Italic") }, enabled = !model.busy, modifier = Modifier.testTag("font-italic"))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Match original size", style = MaterialTheme.typography.bodyMedium)
            Text(if (edit.sourceLetterHeight == null) "No reliable size estimate available" else "Estimated from the original lettering; still shrinks to fit",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(edit.matchSourceSize, model::changeMatchSource, enabled = edit.sourceLetterHeight != null && !model.busy, modifier = Modifier.testTag("match-source-size"))
    }
    Text("Fonts use this device's script fallbacks. Source font recognition and additional font imports are coming later.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
