package com.ruyolidus.ruyo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF6654A4), onPrimary = Color.White,
    primaryContainer = Color(0xFFECE6F8), onPrimaryContainer = Color(0xFF30234E),
    secondary = Color(0xFF526E60), secondaryContainer = Color(0xFFE4EDE4),
    background = Color(0xFFF7F5F0), surface = Color(0xFFFFFEFB),
    onBackground = Color(0xFF292D35), onSurface = Color(0xFF292D35),
    surfaceVariant = Color(0xFFEFEBE4), onSurfaceVariant = Color(0xFF66656C),
    outlineVariant = Color(0xFFE0DCD6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BFFF), primaryContainer = Color(0xFF453566),
    secondary = Color(0xFFB3CCB9), secondaryContainer = Color(0xFF314B3E),
    background = Color(0xFF17191D), surface = Color(0xFF202227),
    onBackground = Color(0xFFE9E5E1), onSurface = Color(0xFFE9E5E1),
    surfaceVariant = Color(0xFF303036), onSurfaceVariant = Color(0xFFC4BDC9),
)

@Composable
fun RuyoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 38.sp, lineHeight = 44.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 35.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 29.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        ),
        content = content,
    )
}

