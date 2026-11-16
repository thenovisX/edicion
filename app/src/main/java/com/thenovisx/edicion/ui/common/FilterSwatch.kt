package com.thenovisx.edicion.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenovisx.edicion.core.Adjustments
import com.thenovisx.edicion.core.ColorPipeline
import com.thenovisx.edicion.core.FilterPreset
import com.thenovisx.edicion.ui.theme.Palette

/**
 * Convierte la parte lineal de un filtro + ajustes en un ColorFilter de Compose.
 * Devuelve null si no hay cambio. Los pasos no lineales necesitan [ColorPipeline].
 */
fun colorFilterFor(pipeline: ColorPipeline): ColorFilter? {
    if (pipeline.matrix.isIdentity) return null
    return ColorFilter.colorMatrix(ColorMatrix(pipeline.matrix.toAndroid4x5()))
}

private fun ColorPipeline.color(r: Int, g: Int, b: Int): Color {
    val rgb = floatArrayOf(r / 255f, g / 255f, b / 255f)
    apply(rgb)
    return Color(rgb[0], rgb[1], rgb[2])
}

/** Muestra de un filtro: una pequeña escena de colores con el filtro aplicado. */
@Composable
fun FilterSwatch(preset: FilterPreset, selected: Boolean, onClick: () -> Unit) {
    // Cielo, sol, piel y suelo: suficiente para apreciar el carácter de cada filtro.
    val colors = remember(preset) {
        val p = ColorPipeline(preset, 1f, Adjustments())
        listOf(p.color(0x4F, 0xA3, 0xE0), p.color(0xF5, 0xB9, 0x71), p.color(0xFF, 0xE0, 0x8A), p.color(0x3E, 0x8E, 0x4E), p.color(0xE8, 0xA6, 0x8A))
    }
    Column(
        Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(if (selected) 3.dp else 1.dp, if (selected) Palette.Coral else Palette.Outline, RoundedCornerShape(10.dp))
        ) {
            drawRect(Brush.verticalGradient(listOf(colors[0], colors[1])))
            drawCircle(colors[2], radius = size.minDimension * 0.16f, center = Offset(size.width * 0.7f, size.height * 0.35f))
            drawRect(colors[3], topLeft = Offset(0f, size.height * 0.68f))
            drawCircle(colors[4], radius = size.minDimension * 0.14f, center = Offset(size.width * 0.3f, size.height * 0.62f))
        }
        Text(
            preset.label,
            fontSize = 11.sp,
            color = if (selected) Palette.Coral else Palette.OnSurface,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
