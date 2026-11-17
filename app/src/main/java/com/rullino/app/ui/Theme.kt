package com.rullino.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.rullino.app.R

/** Colori del design (Figma): corpo scuro, piastre, inchiostro chiaro e luce di sicurezza arancione. */
object Palette {
    val Body = Color(0xFF141312)
    val Deep = Color(0xFF0D0C0C)
    val Leather = Color(0xFF171615)
    val Plate = Color(0xFF24211F)
    val PlateHi = Color(0xFF2D2927)
    val Line = Color(0xFF3A3533)
    val Ink = Color(0xFFEBE6E0)
    val Muted = Color(0xFF9C958D)
    val Faint = Color(0xFF6E6862)
    val Safelight = Color(0xFFFF6A3D)
    val SafelightDark = Color(0xFF2D1710)
    val Chrome = Color(0xFFC9C4BE)
    val Danger = Color(0xFFFF8A80)
}

/** Barlow Condensed per i testi e le incisioni. */
val Barlow = FontFamily(
    Font(R.font.barlow_condensed_regular, FontWeight.Normal),
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

/** IBM Plex Mono per i numeri: ISO, contapose, tempi, focali. */
val Mono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

private fun Typography.withFont(f: FontFamily): Typography {
    fun TextStyle.w() = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.w(), displayMedium = displayMedium.w(), displaySmall = displaySmall.w(),
        headlineLarge = headlineLarge.w(), headlineMedium = headlineMedium.w(), headlineSmall = headlineSmall.w(),
        titleLarge = titleLarge.w(), titleMedium = titleMedium.w(), titleSmall = titleSmall.w(),
        bodyLarge = bodyLarge.w(), bodyMedium = bodyMedium.w(), bodySmall = bodySmall.w(),
        labelLarge = labelLarge.w(), labelMedium = labelMedium.w(), labelSmall = labelSmall.w(),
    )
}

@Composable
fun RullinoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Safelight,
            onPrimary = Color(0xFF1B0E09),
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
        typography = Typography().withFont(Barlow),
        content = content,
    )
}
