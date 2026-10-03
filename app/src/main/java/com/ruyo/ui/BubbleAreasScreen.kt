package com.ruyo.ui

import android.graphics.Bitmap
import android.graphics.Point
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ruyo.reader.BubbleAreas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun BubbleAreasScreen(model: RuyoModel, area: AreaSelection) {
    var selected by remember(area.region) { mutableIntStateOf(-1) }
    var overlay by remember { mutableStateOf<Bitmap?>(null) }
    var splitError by remember { mutableStateOf<String?>(null) }
    var validating by remember { mutableStateOf(true) }
    val image = remember(area.crop) { area.crop.asImageBitmap() }
    LaunchedEffect(area.region, area.centers) {
        validating = true
        val result = withContext(Dispatchers.Default) { runCatching {
            val parts = BubbleAreas.split(area.region, area.centers)
            val colors = intArrayOf(0x443E82C4, 0x44DE9057, 0x448064BB, 0x44459973)
            val pixels = IntArray(area.region.width * area.region.height)
            parts.forEachIndexed { n, part ->
                for (y in 0 until part.height) for (x in 0 until part.width) if (part.interior[x, y]) {
                    pixels[(part.top - area.region.top + y) * area.region.width + part.left - area.region.left + x] = colors[n % colors.size]
                }
            }
            Bitmap.createBitmap(pixels, area.region.width, area.region.height, Bitmap.Config.ARGB_8888)
        } }
        overlay = result.getOrNull(); splitError = result.exceptionOrNull()?.message; validating = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("bubble-areas"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Give each text block its own space", style = MaterialTheme.typography.titleMedium)
        Text("Mark the center of each part. Select an area below, then tap the image to move its center. The shared bubble outline stays intact.", style = MaterialTheme.typography.bodyMedium)
        Canvas(Modifier.fillMaxWidth().height(340.dp).background(MaterialTheme.colorScheme.surfaceVariant).testTag("area-canvas")
            .pointerInput(area.centers, selected, model.busy) {
                if (!model.busy) detectTapGestures { tap ->
                    val scale = minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height) * .94f
                    val x = ((tap.x - (size.width - image.width * scale) / 2) / scale).roundToInt()
                    val y = ((tap.y - (size.height - image.height * scale) / 2) / scale).roundToInt()
                    if (area.region.interior[x, y]) {
                        val points = area.centers.toMutableList()
                        if (selected in points.indices) points[selected] = Point(x, y)
                        else if (points.size < BubbleAreas.MAX_AREAS) { points += Point(x, y); selected = points.lastIndex }
                        model.setAreaCenters(points)
                    }
                }
            }) {
            val scale = minOf(size.width / image.width, size.height / image.height) * .94f
            val origin = IntOffset(((size.width - image.width * scale) / 2).roundToInt(), ((size.height - image.height * scale) / 2).roundToInt())
            val target = IntSize((image.width * scale).roundToInt(), (image.height * scale).roundToInt())
            drawImage(image, dstOffset = origin, dstSize = target)
            overlay?.let { drawImage(it.asImageBitmap(), dstOffset = origin, dstSize = target) }
            area.centers.forEachIndexed { n, point ->
                val center = Offset(origin.x + point.x * scale, origin.y + point.y * scale)
                drawCircle(if (n == selected) Color(0xFFAC6039) else Color(0xFF24282C), 15.dp.toPx(), center)
                drawContext.canvas.nativeCanvas.drawText((n + 1).toString(), center.x, center.y + 5.dp.toPx(), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE; textSize = 14.dp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
                })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            area.centers.forEachIndexed { n, _ -> FilterChip(selected == n, { selected = n }, enabled = !model.busy, label = { Text("Area " + (n + 1)) }) }
            FilterChip(selected == -1, { selected = -1 }, enabled = area.centers.size < BubbleAreas.MAX_AREAS && !model.busy, label = { Text("Add area") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { model.setAreaCenters(area.centers.filterIndexed { n, _ -> n != selected }); selected = -1 }, enabled = selected in area.centers.indices && !model.busy) { Text("Remove center") }
            TextButton(onClick = { model.setAreaCenters(emptyList()); selected = -1 }, enabled = !model.busy) { Text("Reset centers") }
        }
        (area.error ?: splitError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = model::applyAreas, enabled = area.centers.isNotEmpty() && splitError == null && !validating && !model.busy, modifier = Modifier.fillMaxWidth().testTag("apply-areas")) { Text("Use these areas") }
        Text("Save a translation, then tap another part in the reader to edit it independently. Colors here are editing guides only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
