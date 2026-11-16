package com.thenovisx.edicion.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenovisx.edicion.core.Adjustments
import com.thenovisx.edicion.core.CurvePreset
import com.thenovisx.edicion.core.HslBand
import com.thenovisx.edicion.ui.theme.Palette
import kotlin.math.roundToInt

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column {
        Row {
            Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 12.sp, color = Palette.OnSurfaceMuted)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            modifier = Modifier.height(32.dp),
        )
    }
}

private enum class AdjustGroup(val label: String) {
    LIGHT("Luz"), COLOR("Color"), CURVE("Curva"), HSL("HSL"), FX("Efectos")
}

private class Item(val label: String, val value: Float, val range: ClosedFloatingPointRange<Float>, val update: (Float) -> Adjustments)

/**
 * Panel de ajustes de imagen compartido por el editor de vídeo y el de fotos:
 * luz, color, curvas, HSL por color y efectos (viñeta, grano).
 */
@Composable
fun AdjustmentSliders(
    adjustments: Adjustments,
    onChange: (Adjustments) -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit,
) {
    val a = adjustments
    var group by rememberSaveable { mutableStateOf(AdjustGroup.LIGHT) }
    var band by rememberSaveable { mutableStateOf(HslBand.RED) }
    val bi = -1f..1f
    val uni = 0f..1f

    Column {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AdjustGroup.entries.forEach { g ->
                FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g.label) })
            }
            if (!a.isNeutral) {
                Text(
                    "Restablecer",
                    color = Palette.Coral,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onReset).padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
        }

        val items: List<Item> = when (group) {
            AdjustGroup.LIGHT -> listOf(
                Item("Exposición", a.exposure, bi) { a.copy(exposure = it) },
                Item("Brillo", a.brightness, bi) { a.copy(brightness = it) },
                Item("Contraste", a.contrast, bi) { a.copy(contrast = it) },
                Item("Luces", a.highlights, bi) { a.copy(highlights = it) },
                Item("Sombras", a.shadows, bi) { a.copy(shadows = it) },
            )
            AdjustGroup.COLOR -> listOf(
                Item("Saturación", a.saturation, bi) { a.copy(saturation = it) },
                Item("Intensidad", a.vibrance, bi) { a.copy(vibrance = it) },
                Item("Temperatura", a.warmth, bi) { a.copy(warmth = it) },
                Item("Matiz", a.tint, bi) { a.copy(tint = it) },
                Item("Tono", a.hue, bi) { a.copy(hue = it) },
            )
            AdjustGroup.FX -> listOf(
                Item("Viñeta", a.vignette, uni) { a.copy(vignette = it) },
                Item("Grano", a.grain, uni) { a.copy(grain = it) },
                Item("Mate", a.fade, uni) { a.copy(fade = it) },
                Item("Desenfoque", a.blur, uni) { a.copy(blur = it) },
            )
            AdjustGroup.HSL -> listOf(
                Item("Tono", a.hsl(a.hslHue, band), bi) { a.copy(hslHue = a.withHsl(a.hslHue, band, it)) },
                Item("Saturación", a.hsl(a.hslSat, band), bi) { a.copy(hslSat = a.withHsl(a.hslSat, band, it)) },
                Item("Luminosidad", a.hsl(a.hslLum, band), bi) { a.copy(hslLum = a.withHsl(a.hslLum, band, it)) },
            )
            AdjustGroup.CURVE -> emptyList()
        }

        if (group == AdjustGroup.HSL) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HslBand.entries.forEach { b ->
                    FilterChip(selected = band == b, onClick = { band = b }, label = { Text(b.label) })
                }
            }
        }
        if (group == AdjustGroup.CURVE) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CurvePreset.entries.forEach { c ->
                    FilterChip(
                        selected = a.curve == c,
                        onClick = { onChange(a.copy(curve = c)); onDone() },
                        label = { Text(c.label) },
                    )
                }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items.forEach { item ->
                Column(Modifier.width(150.dp)) {
                    LabeledSlider(
                        label = item.label,
                        valueText = "${(item.value * 100).roundToInt()}",
                        value = item.value,
                        range = item.range,
                        onChange = { onChange(item.update(it)) },
                        onDone = onDone,
                    )
                }
            }
        }
    }
}

/** Círculo de color para elegir entre varias opciones. */
@Composable
fun ColorChoice(argb: Long, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .padding(2.dp)
            .width(30.dp)
            .height(30.dp)
            .then(
                Modifier.background(
                    androidx.compose.ui.graphics.Color(argb.toInt()),
                    androidx.compose.foundation.shape.CircleShape,
                )
            )
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) Palette.Coral else Palette.Outline,
                androidx.compose.foundation.shape.CircleShape,
            )
            .clickable(onClick = onClick)
    )
}
