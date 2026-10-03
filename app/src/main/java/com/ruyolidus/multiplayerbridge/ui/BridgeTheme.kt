package com.ruyolidus.multiplayerbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
fun BridgeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFC4C0FF), onPrimary = Color(0xFF26235E),
        primaryContainer = Color(0xFF39345D), onPrimaryContainer = Color(0xFFE8E5FF),
        background = Color(0xFF14151B), surface = Color(0xFF1D1E27),
        surfaceVariant = Color(0xFF292B38), onSurfaceVariant = Color(0xFFBCBECE),
        onBackground = Color(0xFFF0EFF6), onSurface = Color(0xFFF0EFF6),
        outlineVariant = Color(0xFF414251),
    ) else lightColorScheme(
        primary = Color(0xFF4E4CCC), onPrimary = Color.White,
        primaryContainer = Color(0xFFEAE8FF), onPrimaryContainer = Color(0xFF363077),
        background = Color(0xFFF7F7FA), surface = Color.White,
        surfaceVariant = Color(0xFFF0F0F6), onSurfaceVariant = Color(0xFF666878),
        onBackground = Color(0xFF202234), onSurface = Color(0xFF202234),
        outlineVariant = Color(0xFFE3E3ED),
    )
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}
