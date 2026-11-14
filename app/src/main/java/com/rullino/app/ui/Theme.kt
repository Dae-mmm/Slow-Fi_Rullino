package com.rullino.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

object Palette {
    val Body = Color(0xFF141312)
    val Plate = Color(0xFF1E1C1B)
    val PlateHi = Color(0xFF2A2725)
    val Line = Color(0xFF3A3533)
    val Ink = Color(0xFFEBE6E0)
    val Muted = Color(0xFF9C958D)
    val Safelight = Color(0xFFFF6A3D)
    val Chrome = Color(0xFFC9C4BE)
}

val Mono = FontFamily.Monospace

@Composable
fun RullinoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Safelight,
            onPrimary = Palette.Body,
            secondary = Palette.Chrome,
            background = Palette.Body,
            onBackground = Palette.Ink,
            surface = Palette.Plate,
            onSurface = Palette.Ink,
            surfaceVariant = Palette.PlateHi,
            onSurfaceVariant = Palette.Muted,
            surfaceContainer = Palette.Plate,
            outline = Palette.Line,
        ),
        content = content,
    )
}
