package com.thenovisx.edicion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Paleta propia de la app. El fondo es casi negro y las superficies (barras, tarjetas,
 * botones) son claramente más claras y llevan borde, para que cada zona se distinga bien.
 */
object Palette {
    val Background = Color(0xFF08080C)
    val Surface = Color(0xFF1D1F2C)
    val SurfaceHigh = Color(0xFF30334A)
    val Outline = Color(0xFF565A7A)
    val Coral = Color(0xFFFF7A59)
    val Amber = Color(0xFFFFD166)
    val Violet = Color(0xFF9B8CFF)
    val Teal = Color(0xFF3FE0CF)
    val Pink = Color(0xFFFF6FA8)
    val Green = Color(0xFF6BE58A)
    val Blue = Color(0xFF5CB8FF)
    val OnSurface = Color(0xFFFFFFFF)
    val OnSurfaceMuted = Color(0xFFCDD0E3)
    val Playhead = Color(0xFFFFFFFF)
}

private val scheme = darkColorScheme(
    primary = Palette.Coral,
    onPrimary = Color.Black,
    primaryContainer = Palette.Coral,
    onPrimaryContainer = Color.Black,
    secondary = Palette.Amber,
    onSecondary = Color.Black,
    secondaryContainer = Palette.Coral,
    onSecondaryContainer = Color.Black,
    tertiary = Palette.Violet,
    onTertiary = Color.Black,
    background = Palette.Background,
    onBackground = Palette.OnSurface,
    surface = Palette.Surface,
    onSurface = Palette.OnSurface,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.OnSurfaceMuted,
    surfaceContainerLowest = Palette.Background,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHigh,
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
    error = Color(0xFFFF8A80),
)

@Composable
fun EdicionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
