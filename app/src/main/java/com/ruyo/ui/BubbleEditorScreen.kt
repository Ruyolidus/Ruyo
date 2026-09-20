package com.ruyo.ui

import android.graphics.Bitmap
import android.graphics.PointF
import com.ruyo.reader.BubbleEditRenderer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun BubbleEditorScreen(model: RuyoModel, draft: EditorDraft) {
    var tool by remember(draft.edit.id) { mutableStateOf("Erase text") }
    var radius by remember(draft.edit.id) { mutableFloatStateOf(8f) }
    var showingPreview by remember(draft.edit.id) { mutableStateOf(false) }
    var padding by remember(draft.edit.id, draft.edit.margin) { mutableFloatStateOf(draft.edit.margin.toFloat()) }
    var fontScale by remember(draft.edit.id, draft.edit.fontScale) { mutableFloatStateOf(draft.edit.fontScale) }
    val preferredSize = BubbleEditRenderer.preferredSize(model.opened?.original?.width ?: draft.crop.width, draft.edit)
    val fittedPercent = ((draft.preview?.fit?.fontSize ?: preferredSize) / preferredSize * 100).roundToInt()
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberLazyListState()
    LaunchedEffect(draft.previewVersion, draft.preview) {
        showingPreview = draft.preview != null
        if (showingPreview) scroll.animateScrollToItem(0)
    }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("bubble-editor"), state = scroll, contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (showingPreview) "Translation preview" else "Clean the original lettering", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (draft.preview != null) TextButton(onClick = { showingPreview = !showingPreview }) { Text(if (showingPreview) "Show mask" else "Preview") }
                    else IconButton(onClick = model::resetMask, enabled = !model.busy) { Icon(AppIcons.Undo, "Reset cleanup mask", Modifier.size(20.dp)) }
                }
                EditorCanvas(draft, showingPreview, tool, radius, !model.busy) { points -> model.brush(points, radius, tool == "Erase text") }
                if (!showingPreview) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Erase text", "Restore", "Move").forEach { label -> FilterChip(selected = tool == label, onClick = { tool = label }, enabled = !model.busy, label = { Text(label) }, shape = RoundedCornerShape(6.dp)) }
                    }
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Brush", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(value = radius, onValueChange = { radius = it }, valueRange = 2f..32f, enabled = tool != "Move" && !model.busy, modifier = Modifier.weight(1f))
                        Text(radius.roundToInt().toString(), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Red pixels will be removed. Brush only over letters; use Restore to protect artwork.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(AppIcons.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (fittedPercent < 99) "Auto-shrunk to $fittedPercent% · Pinch to zoom" else if (draft.edit.matchSourceSize) "Whole text fits · Estimated source size" else "Whole text fits · Normal lettering size", modifier = Modifier.testTag("preview-visible"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (model.areaSelection != null) TextButton(onClick = model::editAreas, enabled = !model.busy) { Text("Adjust joined bubble areas") }
                OutlinedTextField(value = draft.edit.japanese, onValueChange = model::changeText, enabled = !model.busy,
                    label = { Text("Translation text") }, placeholder = { Text("Enter or paste the translated dialogue") }, minLines = 2, maxLines = 4,
                    supportingText = { Text("${draft.edit.japanese.length} / 512 · Entered manually in this build") }, modifier = Modifier.fillMaxWidth().testTag("japanese-input"))
                LetteringControls(draft.edit, model)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Text size", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = fontScale, onValueChange = { fontScale = it },
                        onValueChangeFinished = { model.changeFontScale(fontScale); if (draft.edit.japanese.isNotBlank()) { keyboard?.hide(); model.preview() } },
                        valueRange = 0.6f..1.6f, enabled = !model.busy, modifier = Modifier.weight(1f).testTag("text-size"))
                    Text("${(fontScale * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Text padding", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = padding, onValueChange = { padding = it },
                        onValueChangeFinished = { model.changeMargin(padding.roundToInt()); if (draft.edit.japanese.isNotBlank()) { keyboard?.hide(); model.preview() } },
                        valueRange = 2f..maxOf(8f, minOf(draft.edit.region.width, draft.edit.region.height) / 4f), enabled = !model.busy,
                        modifier = Modifier.weight(1f).testTag("text-padding"))
                }
                Text("Text shrinks to fit automatically. Padding controls the empty space around it. Both sliders update the preview when released.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                draft.previewError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("preview-error"))
                }
                Button(onClick = { keyboard?.hide(); model.preview() }, enabled = draft.edit.japanese.isNotBlank() && !model.busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("preview-edit")) { Text(if (model.busy) "Rendering…" else "Preview replacement") }
                if (draft.existing) TextButton(onClick = model::removeEdit, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Restore original bubble") }
            }
        }
    }
}

@Composable
private fun EditorCanvas(draft: EditorDraft, preview: Boolean, tool: String, radius: Float, enabled: Boolean, onStroke: (List<PointF>) -> Unit) {
    var zoom by remember(draft.edit.id) { mutableFloatStateOf(1f) }
    var pan by remember(draft.edit.id) { mutableStateOf(Offset.Zero) }
    val stroke = remember { mutableStateListOf<Offset>() }
    val mask = remember(draft.edit.region.eraseMask) {
        val region = draft.edit.region
        val colors = IntArray(region.width * region.height) { if (region.eraseMask[it]) 0xAADD492E.toInt() else 0 }
        Bitmap.createBitmap(colors, region.width, region.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val bitmap = if (preview) draft.preview?.crop ?: draft.crop else draft.crop
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val currentStroke by rememberUpdatedState(onStroke)
    Box(Modifier.fillMaxWidth().height(230.dp).background(Color(0xFF24282C)).clipToBounds()) {
        Canvas(Modifier.fillMaxSize().testTag("editor-canvas")
            .pointerInput(tool, zoom, pan, enabled, preview) {
                if (tool == "Move" && !preview && enabled) detectTransformGestures { _, movement, factor, _ ->
                    zoom = (zoom * factor).coerceIn(1f, 4f)
                    pan = Offset((pan.x + movement.x).coerceIn(-size.width.toFloat(), size.width.toFloat()), (pan.y + movement.y).coerceIn(-size.height.toFloat(), size.height.toFloat()))
                }
                else if (enabled && !preview) {
                    fun convert(point: Offset): PointF {
                        val scale = minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height) * 0.88f * zoom
                        val origin = Offset((size.width - image.width * scale) / 2 + pan.x, (size.height - image.height * scale) / 2 + pan.y)
                        return PointF((point.x - origin.x) / scale, (point.y - origin.y) / scale)
                    }
                    detectDragGestures(onDragStart = { stroke.clear(); stroke += it }, onDragCancel = { stroke.clear() },
                        onDragEnd = { currentStroke(stroke.map(::convert)); stroke.clear() }) { change, _ -> change.consume(); stroke += change.position }
                }
            }
            .pointerInput(tool, zoom, pan, enabled, preview) {
                if (enabled && !preview && tool != "Move") detectTapGestures { point ->
                    val scale = minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height) * 0.88f * zoom
                    val origin = Offset((size.width - image.width * scale) / 2 + pan.x, (size.height - image.height * scale) / 2 + pan.y)
                    currentStroke(listOf(PointF((point.x - origin.x) / scale, (point.y - origin.y) / scale)))
                }
            }) {
            val scale = minOf(size.width / image.width, size.height / image.height) * 0.88f * zoom
            val origin = IntOffset(((size.width - image.width * scale) / 2 + pan.x).roundToInt(), ((size.height - image.height * scale) / 2 + pan.y).roundToInt())
            val target = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt())
            drawImage(image, dstOffset = origin, dstSize = target, filterQuality = FilterQuality.High)
            if (!preview) drawImage(mask, dstOffset = origin, dstSize = target, filterQuality = FilterQuality.High)
            if (!preview && tool != "Move") {
                val color = if (tool == "Erase text") Color(0xAADD492E) else Color(0xAA5AABB8)
                stroke.forEach { drawCircle(color, radius * scale, it) }
                for (i in 1 until stroke.size) drawLine(color, stroke[i - 1], stroke[i], radius * scale * 2, StrokeCap.Round)
            }
        }
        FilledTonalButton(onClick = { zoom = if (zoom < 1.5f) 2f else 1f; pan = Offset.Zero }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Icon(AppIcons.Zoom, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall) }
    }
}
