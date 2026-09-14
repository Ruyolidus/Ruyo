package com.ruyo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** A single 24-unit stroke grid keeps controls visually consistent. */
object AppIcons {
    private fun icon(name: String, data: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString(data).toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }.build()
    val Up = icon("Up", "M5,15 L12,8 L19,15")
    val Down = icon("Down", "M5,9 L12,16 L19,9")
    val Web = icon("Web", "M21,12 A9,9 0,1 1,3,12 A9,9 0,1 1,21,12 M3,12 L21,12 M12,3 C6,8 6,16 12,21 C18,16 18,8 12,3")
    val Pages = icon("Pages", "M7,3 L21,3 L21,17 M3,7 L17,7 L17,21 L3,21 Z")
    val Refresh = icon("Refresh", "M20,10 A8,8 0,1 0,20,16 M20,4 L20,10 L14,10")
    val Library = icon("Library", "M4,4 L4,20 M9,4 L9,20 M14,4 L14,20 M18,5 L21,19")
    val Bookmark = icon("Bookmark", "M6,4 L18,4 L18,21 L12,17 L6,21 Z")
    val Settings = icon("Settings", "M4,7 L20,7 M4,17 L20,17 M9,4 L9,10 M15,14 L15,20")
    val Plus = icon("Add", "M12,5 L12,19 M5,12 L19,12")
    val Back = icon("Back", "M15,5 L8,12 L15,19")
    val Close = icon("Close", "M6,6 L18,18 M18,6 L6,18")
    val Search = icon("Search", "M17,10 A7,7 0,1 1,3,10 A7,7 0,1 1,17,10 M15,15 L21,21")
    val Edit = icon("Edit", "M4,20 L5,14 L16,3 L21,8 L10,19 Z M13,6 L18,11")
    val More = icon("More", "M12,4 L12,5 M12,11 L12,12 M12,18 L12,19")
    val Image = icon("Image", "M3,4 L21,4 L21,20 L3,20 Z M3,16 L9,10 L15,16 L18,13 L21,16 M16,8 L16,8.1")
    val Check = icon("Check", "M5,12 L10,17 L20,6")
    val Trash = icon("Delete", "M4,6 L20,6 M9,6 L9,3 L15,3 L15,6 M6,6 L7,21 L17,21 L18,6 M10,10 L10,17 M14,10 L14,17")
    val Undo = icon("Undo", "M8,4 L3,9 L8,14 M3,9 L14,9 A6,6 0,0 1,14,21")
    val Zoom = icon("Zoom", "M17,10 A7,7 0,1 1,3,10 A7,7 0,1 1,17,10 M15,15 L21,21 M7,10 L13,10 M10,7 L10,13")
}
