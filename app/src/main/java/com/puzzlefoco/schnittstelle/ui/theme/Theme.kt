package com.puzzlefoco.schnittstelle.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Dunkle Oberfläche wie in Schnittprogrammen: neutrales Grau, ein Akzent. */
object SchnittstelleColors {
    val Background = Color(0xFF0E1013)
    val Surface = Color(0xFF16191E)
    val SurfaceHigh = Color(0xFF1E2229)
    val Outline = Color(0xFF2C3139)
    val Accent = Color(0xFF5B8CFF)
    val AccentSoft = Color(0xFF2A3C63)
    val Text = Color(0xFFE9ECF2)
    val TextDim = Color(0xFF9BA3B0)
    val VideoTrack = Color(0xFF3B6FD4)
    val AudioTrack = Color(0xFF35A47C)
    val TextTrack = Color(0xFFC9873B)
    val Danger = Color(0xFFE5484D)
    /** Hinweis statt Fehler – z. B. 4K, das nicht jedes Gerät schafft. */
    val Warn = Color(0xFFE0A458)
}

private val DarkScheme = darkColorScheme(
    primary = SchnittstelleColors.Accent,
    onPrimary = Color.White,
    primaryContainer = SchnittstelleColors.AccentSoft,
    onPrimaryContainer = SchnittstelleColors.Text,
    background = SchnittstelleColors.Background,
    onBackground = SchnittstelleColors.Text,
    surface = SchnittstelleColors.Surface,
    onSurface = SchnittstelleColors.Text,
    surfaceVariant = SchnittstelleColors.SurfaceHigh,
    onSurfaceVariant = SchnittstelleColors.TextDim,
    outline = SchnittstelleColors.Outline,
    error = SchnittstelleColors.Danger,
)

private val SchnittstelleTypography = Typography(
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun SchnittstelleTheme(content: @Composable () -> Unit) {
    // Schnittstelle ist bewusst immer dunkel (Schnittplatz-Look)
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = SchnittstelleTypography,
        content = content,
    )
}
