package com.dshnative.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// DSH web UI 风格深色配色
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4C8DFF),
    onPrimary = Color.White,
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    onBackground = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = Color(0xFF8B949E),
    secondaryContainer = Color(0xFF1F6FEB),
)

@Composable
fun DshNativeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}