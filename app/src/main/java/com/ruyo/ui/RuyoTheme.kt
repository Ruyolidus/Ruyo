package com.ruyo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFFB84D2B), onPrimary = Color.White,
    primaryContainer = Color(0xFFF5E6DF), onPrimaryContainer = Color(0xFF7C341F),
    secondary = Color(0xFF505860), secondaryContainer = Color(0xFFEBEDF0), onSecondaryContainer = Color(0xFF20252A),
    background = Color(0xFFF8F9FA), surface = Color.White,
    onBackground = Color(0xFF191C20), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFF0F1F3), onSurfaceVariant = Color(0xFF626A73),
    outline = Color(0xFF9299A1), outlineVariant = Color(0xFFE2E5E8),
    surfaceContainer = Color(0xFFF3F4F5), surfaceContainerLow = Color.White,
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE99A76), onPrimary = Color(0xFF251A15),
    primaryContainer = Color(0xFF3C2B24), onPrimaryContainer = Color(0xFFF0B597),
    secondary = Color(0xFFBCC3CA), secondaryContainer = Color(0xFF2D3238), onSecondaryContainer = Color(0xFFE4E8EC),
    background = Color(0xFF111315), surface = Color(0xFF171A1D),
    onBackground = Color(0xFFE7E9EB), onSurface = Color(0xFFE7E9EB),
    surfaceVariant = Color(0xFF24282D), onSurfaceVariant = Color(0xFF9BA4AE),
    outline = Color(0xFF68717C), outlineVariant = Color(0xFF2C3137),
    surfaceContainer = Color(0xFF1C2024), surfaceContainerLow = Color(0xFF171A1D),
)

@Composable
fun RuyoTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(14.dp), extraLarge = RoundedCornerShape(18.dp)),
        typography = Typography(
            headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
            titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
        ), content = content,
    )
}
