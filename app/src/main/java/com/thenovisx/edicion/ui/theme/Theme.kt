package com.thenovisx.edicion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Paleta propia de la app: fondo oscuro neutro con acentos coral, ámbar y violeta. */
object Palette {
    val Background = Color(0xFF101014)
    val Surface = Color(0xFF1A1A21)
    val SurfaceHigh = Color(0xFF24242D)
    val Coral = Color(0xFFFF6B4A)
    val Amber = Color(0xFFFFC94A)
    val Violet = Color(0xFF7C6CFF)
    val Teal = Color(0xFF3DD6C6)
    val OnSurface = Color(0xFFECECF1)
    val OnSurfaceMuted = Color(0xFF9A9AA8)
    val Playhead = Color(0xFFFFFFFF)
}

private val scheme = darkColorScheme(
    primary = Palette.Coral,
    onPrimary = Color.Black,
    secondary = Palette.Amber,
    onSecondary = Color.Black,
    tertiary = Palette.Violet,
    background = Palette.Background,
    onBackground = Palette.OnSurface,
    surface = Palette.Surface,
    onSurface = Palette.OnSurface,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.OnSurfaceMuted,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerLow = Palette.Surface,
)

@Composable
fun EdicionTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
