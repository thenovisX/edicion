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
import com.thenovisx.edicion.core.ColorMath
import com.thenovisx.edicion.core.FilterPreset
import com.thenovisx.edicion.ui.theme.Palette

/** Convierte un filtro + ajustes en un ColorFilter de Compose. */
fun colorFilterFor(filter: FilterPreset, intensity: Float, adjustments: Adjustments): ColorFilter? {
    val m = ColorMath.combined(filter, intensity, adjustments)
    if (m.isIdentity) return null
    return ColorFilter.colorMatrix(ColorMatrix(m.toAndroid4x5()))
}

/** Muestra de un filtro: una pequeña escena de colores con el filtro aplicado. */
@Composable
fun FilterSwatch(preset: FilterPreset, selected: Boolean, onClick: () -> Unit) {
    val filter = remember(preset) { colorFilterFor(preset, 1f, Adjustments()) }
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
            // Cielo, sol y suelo: suficiente para apreciar el carácter de cada filtro.
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFF4FA3E0), Color(0xFFF5B971))),
                colorFilter = filter,
            )
            drawCircle(Color(0xFFFFE08A), radius = size.minDimension * 0.16f, center = Offset(size.width * 0.7f, size.height * 0.35f), colorFilter = filter)
            drawRect(
                Color(0xFF3E8E4E),
                topLeft = Offset(0f, size.height * 0.68f),
                colorFilter = filter,
            )
        }
        Text(
            preset.label,
            fontSize = 11.sp,
            color = if (selected) Palette.Coral else Palette.OnSurface,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
