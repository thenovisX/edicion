package com.thenovisx.edicion.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenovisx.edicion.core.Adjustments
import com.thenovisx.edicion.core.AspectRatio
import com.thenovisx.edicion.core.FilterPreset
import com.thenovisx.edicion.core.LayerAnim
import com.thenovisx.edicion.core.MAX_TRANSITION_MS
import com.thenovisx.edicion.core.TextFont
import com.thenovisx.edicion.core.TransitionType
import com.thenovisx.edicion.core.MAX_IMAGE_DURATION_MS
import com.thenovisx.edicion.core.MIN_CLIP_DURATION_MS
import com.thenovisx.edicion.core.MediaKind
import com.thenovisx.edicion.core.Project
import com.thenovisx.edicion.core.formatTime
import com.thenovisx.edicion.ui.common.FilterSwatch
import com.thenovisx.edicion.ui.theme.Palette
import kotlin.math.roundToInt

/** Paneles de herramientas que se abren sobre la barra inferior. */
enum class Panel(val title: String) {
    FORMAT("Formato"),
    TRIM("Recortar"),
    SPEED("Velocidad"),
    VOLUME("Volumen"),
    FILTERS("Filtros"),
    ADJUST("Ajustes"),
    IMAGE_DURATION("Duración"),
    AUDIO_TRIM("Recortar audio"),
    AUDIO_VOLUME("Volumen"),
    TEXT_STYLE("Estilo"),
    TEXT_POSITION("Posición"),
    TEXT_TIME("Tiempo"),
    TRANSITION("Transición"),
    LAYER_ANIM("Animación"),
    IMAGE_STYLE("Tamaño"),
    LAYER_VOLUME("Volumen"),
}

fun panelsFor(selection: Selection, project: Project?): Set<Panel> = when (selection) {
    Selection.None -> setOf(Panel.FORMAT)
    is Selection.VideoClip -> {
        val clip = project?.clips?.firstOrNull { it.id == selection.id }
        if (clip?.kind == MediaKind.IMAGE) setOf(Panel.IMAGE_DURATION, Panel.FILTERS, Panel.ADJUST, Panel.TRANSITION)
        else setOf(Panel.TRIM, Panel.SPEED, Panel.VOLUME, Panel.FILTERS, Panel.ADJUST, Panel.TRANSITION)
    }
    is Selection.Audio -> setOf(Panel.AUDIO_TRIM, Panel.AUDIO_VOLUME)
    is Selection.Text -> setOf(
        Panel.TEXT_STYLE, Panel.IMAGE_STYLE, Panel.LAYER_VOLUME, Panel.TEXT_POSITION, Panel.TEXT_TIME, Panel.LAYER_ANIM,
    )
}

@Composable
fun ToolArea(
    project: Project,
    state: EditorUiState,
    panel: Panel?,
    onPanel: (Panel?) -> Unit,
    vm: EditorViewModel,
    onAddMedia: () -> Unit,
    onAddAudio: () -> Unit,
    onAddText: () -> Unit,
    onEditText: (String) -> Unit,
    onAddSticker: () -> Unit,
    onAddMediaLayer: () -> Unit,
    onReplaceLayer: (String) -> Unit,
    onVoice: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface)
            .padding(top = 4.dp)
    ) {
        if (panel != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(panel.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onPanel(null) },
                    modifier = Modifier.size(36.dp).background(Palette.Coral, CircleShape),
                ) { Icon(Icons.Default.Check, "Listo", tint = Color.Black) }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .padding(horizontal = 12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                PanelContent(panel, project, state.selection, vm)
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when (val sel = state.selection) {
                    Selection.None -> {
                        Tool("Medios", Icons.Default.AddPhotoAlternate, Palette.Coral, onClick = onAddMedia)
                        Tool("Texto", Icons.Default.TextFields, Palette.Violet, onClick = onAddText)
                        Tool("Sticker", Icons.Default.EmojiEmotions, Palette.Amber, onClick = onAddSticker)
                        Tool("Capa", Icons.Default.Layers, Palette.Pink, onClick = onAddMediaLayer)
                        Tool("Música", Icons.Default.LibraryMusic, Palette.Teal, onClick = onAddAudio)
                        Tool("Voz", Icons.Default.Mic, Palette.Green, onClick = onVoice)
                        Tool("Dividir", Icons.Default.ContentCut, Palette.Blue, onClick = vm::splitAtPlayhead)
                        Tool("Formato", Icons.Default.AspectRatio) { onPanel(Panel.FORMAT) }
                    }
                    is Selection.VideoClip -> {
                        val clip = project.clips.firstOrNull { it.id == sel.id }
                        Tool("Dividir", Icons.Default.ContentCut, onClick = vm::splitAtPlayhead)
                        if (clip?.kind == MediaKind.IMAGE) {
                            Tool("Duración", Icons.Default.Timer) { onPanel(Panel.IMAGE_DURATION) }
                        } else {
                            Tool("Recortar", Icons.Default.Straighten) { onPanel(Panel.TRIM) }
                            Tool("Velocidad", Icons.Default.Speed) { onPanel(Panel.SPEED) }
                            Tool("Volumen", Icons.Default.VolumeUp) { onPanel(Panel.VOLUME) }
                        }
                        Tool("Filtros", Icons.Default.PhotoFilter) { onPanel(Panel.FILTERS) }
                        Tool("Ajustes", Icons.Default.Tune) { onPanel(Panel.ADJUST) }
                        Tool("Transición", Icons.Default.SwapHoriz) { onPanel(Panel.TRANSITION) }
                        Tool("Girar", Icons.Default.RotateRight) { vm.updateClip(sel.id) { it.copy(rotation = it.rotation + 90) } }
                        Tool("Espejo", Icons.Default.Flip) { vm.updateClip(sel.id) { it.copy(flipHorizontal = !it.flipHorizontal) } }
                        Tool("Duplicar", Icons.Default.ContentCopy, onClick = vm::duplicateSelection)
                        Tool("Antes", Icons.AutoMirrored.Filled.ArrowBack) { vm.moveSelectedClip(-1) }
                        Tool("Después", Icons.AutoMirrored.Filled.ArrowForward) { vm.moveSelectedClip(1) }
                        Tool("Borrar", Icons.Default.Delete, Palette.Coral, onClick = vm::deleteSelection)
                    }
                    is Selection.Audio -> {
                        Tool("Dividir", Icons.Default.ContentCut, onClick = vm::splitAtPlayhead)
                        Tool("Recortar", Icons.Default.Straighten) { onPanel(Panel.AUDIO_TRIM) }
                        Tool("Volumen", Icons.Default.VolumeUp) { onPanel(Panel.AUDIO_VOLUME) }
                        Tool("Borrar", Icons.Default.Delete, onClick = vm::deleteSelection)
                    }
                    is Selection.Text -> {
                        val layer = project.texts.firstOrNull { it.id == sel.id }
                        if (layer?.isMedia == true) {
                            Tool("Tamaño", Icons.Default.FormatSize) { onPanel(Panel.IMAGE_STYLE) }
                            Tool("Cambiar", Icons.Default.Image) { onReplaceLayer(sel.id) }
                            if (layer.isVideo && layer.hasAudio) {
                                Tool("Volumen", Icons.Default.VolumeUp) { onPanel(Panel.LAYER_VOLUME) }
                            }
                        } else {
                            Tool("Editar", Icons.Default.Edit) { onEditText(sel.id) }
                            Tool("Estilo", Icons.Default.FormatSize) { onPanel(Panel.TEXT_STYLE) }
                        }
                        Tool("Posición", Icons.Default.OpenWith) { onPanel(Panel.TEXT_POSITION) }
                        Tool("Tiempo", Icons.Default.Timer) { onPanel(Panel.TEXT_TIME) }
                        Tool("Animación", Icons.Default.Animation) { onPanel(Panel.LAYER_ANIM) }
                        Tool("Al frente", Icons.Default.FlipToFront) { vm.moveLayerZ(sel.id, toFront = true) }
                        Tool("Al fondo", Icons.Default.FlipToBack) { vm.moveLayerZ(sel.id, toFront = false) }
                        Tool("Capa", Icons.Default.Layers, Palette.Pink, onClick = onAddMediaLayer)
                        Tool("Dividir", Icons.Default.ContentCut, onClick = vm::splitAtPlayhead)
                        Tool("Duplicar", Icons.Default.ContentCopy, onClick = vm::duplicateSelection)
                        Tool("Borrar", Icons.Default.Delete, onClick = vm::deleteSelection)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tool(label: String, icon: ImageVector, tint: Color = Palette.OnSurface, onClick: () -> Unit) {
    Column(
        Modifier
            .width(70.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .background(Palette.SurfaceHigh, RoundedCornerShape(14.dp))
                .border(1.dp, Palette.Outline, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = tint)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = Palette.OnSurface)
    }
}

@Composable
private fun PanelContent(panel: Panel, project: Project, selection: Selection, vm: EditorViewModel) {
    val clip = (selection as? Selection.VideoClip)?.let { s -> project.clips.firstOrNull { it.id == s.id } }
    val audio = (selection as? Selection.Audio)?.let { s -> project.audio.firstOrNull { it.id == s.id } }
    val text = (selection as? Selection.Text)?.let { s -> project.texts.firstOrNull { it.id == s.id } }

    when (panel) {
        Panel.FORMAT -> Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AspectRatio.entries.forEach { ar ->
                FilterChip(
                    selected = project.aspectRatio == ar,
                    onClick = { vm.setAspectRatio(ar) },
                    label = { Text(ar.label) },
                )
            }
        }

        Panel.TRIM -> if (clip != null) {
            Column {
                Text(
                    "${formatTime(clip.trimStartMs)} – ${formatTime(clip.trimEndMs)}  (de ${formatTime(clip.sourceDurationMs)})",
                    fontSize = 12.sp, color = Palette.OnSurfaceMuted,
                )
                RangeSlider(
                    value = clip.trimStartMs.toFloat()..clip.trimEndMs.toFloat(),
                    onValueChange = { r ->
                        vm.updateClip(clip.id, live = true) {
                            it.copy(trimStartMs = r.start.toLong(), trimEndMs = r.endInclusive.toLong())
                        }
                    },
                    onValueChangeFinished = vm::commitLive,
                    valueRange = 0f..clip.sourceDurationMs.toFloat(),
                )
            }
        }

        Panel.SPEED -> if (clip != null) {
            Column {
                LabeledSlider(
                    label = "Velocidad",
                    valueText = "%.2f×".format(clip.speed),
                    value = speedToSlider(clip.speed),
                    range = 0f..1f,
                    onChange = { v -> vm.updateClip(clip.id, live = true) { it.copy(speed = sliderToSpeed(v)) } },
                    onDone = vm::commitLive,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 1f, 1.5f, 2f, 3f).forEach { s ->
                        FilterChip(
                            selected = clip.speed == s,
                            onClick = { vm.updateClip(clip.id) { it.copy(speed = s) } },
                            label = { Text("${s}×") },
                        )
                    }
                }
            }
        }

        Panel.VOLUME -> if (clip != null) {
            Column {
                LabeledSlider(
                    label = "Volumen",
                    valueText = "${(clip.volume * 100).roundToInt()} %",
                    value = clip.volume,
                    range = 0f..2f,
                    onChange = { v -> vm.updateClip(clip.id, live = true) { it.copy(volume = v) } },
                    onDone = vm::commitLive,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Silenciar", Modifier.weight(1f))
                    Switch(checked = clip.muted, onCheckedChange = { m -> vm.updateClip(clip.id) { it.copy(muted = m) } })
                }
            }
        }

        Panel.FILTERS -> if (clip != null) {
            Column {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterPreset.entries.forEach { f ->
                        FilterSwatch(
                            preset = f,
                            selected = clip.filter == f,
                            onClick = { vm.updateClip(clip.id) { it.copy(filter = f) } },
                        )
                    }
                }
                if (clip.filter != FilterPreset.NONE) {
                    LabeledSlider(
                        label = "Intensidad",
                        valueText = "${(clip.filterIntensity * 100).roundToInt()} %",
                        value = clip.filterIntensity,
                        range = 0f..1f,
                        onChange = { v -> vm.updateClip(clip.id, live = true) { it.copy(filterIntensity = v) } },
                        onDone = vm::commitLive,
                    )
                }
            }
        }

        Panel.ADJUST -> if (clip != null) {
            AdjustmentSliders(
                adjustments = clip.adjustments,
                onChange = { a -> vm.updateClip(clip.id, live = true) { it.copy(adjustments = a) } },
                onDone = vm::commitLive,
                onReset = { vm.updateClip(clip.id) { it.copy(adjustments = Adjustments()) } },
            )
        }

        Panel.IMAGE_DURATION -> if (clip != null) {
            LabeledSlider(
                label = "Duración de la foto",
                valueText = formatTime(clip.trimmedDurationMs),
                value = clip.trimmedDurationMs / 1000f,
                range = 0.5f..(MAX_IMAGE_DURATION_MS / 1000f).coerceAtMost(20f),
                onChange = { v ->
                    vm.updateClip(clip.id, live = true) { it.copy(trimStartMs = 0, trimEndMs = (v * 1000).toLong()) }
                },
                onDone = vm::commitLive,
            )
        }

        Panel.AUDIO_TRIM -> if (audio != null) {
            Column {
                Text(
                    "Fragmento ${formatTime(audio.trimStartMs)} – ${formatTime(audio.trimEndMs)}",
                    fontSize = 12.sp, color = Palette.OnSurfaceMuted,
                )
                RangeSlider(
                    value = audio.trimStartMs.toFloat()..audio.trimEndMs.toFloat(),
                    onValueChange = { r ->
                        vm.updateAudio(audio.id, live = true) {
                            it.copy(trimStartMs = r.start.toLong(), trimEndMs = r.endInclusive.toLong())
                        }
                    },
                    onValueChangeFinished = vm::commitLive,
                    valueRange = 0f..audio.sourceDurationMs.toFloat(),
                )
                LabeledSlider(
                    label = "Empieza en",
                    valueText = formatTime(audio.startMs),
                    value = audio.startMs.toFloat(),
                    range = 0f..maxOf(project.durationMs.toFloat(), 1f),
                    onChange = { v -> vm.updateAudio(audio.id, live = true) { it.copy(startMs = v.toLong()) } },
                    onDone = vm::commitLive,
                )
            }
        }

        Panel.AUDIO_VOLUME -> if (audio != null) {
            LabeledSlider(
                label = "Volumen",
                valueText = "${(audio.volume * 100).roundToInt()} %",
                value = audio.volume,
                range = 0f..2f,
                onChange = { v -> vm.updateAudio(audio.id, live = true) { it.copy(volume = v) } },
                onDone = vm::commitLive,
            )
        }

        Panel.TRANSITION -> if (clip != null) {
            Column {
                Text(
                    if (project.clips.firstOrNull()?.id == clip.id) "Entrada del vídeo desde un color"
                    else "Entre el clip anterior y este",
                    fontSize = 12.sp, color = Palette.OnSurfaceMuted,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TransitionType.entries.forEach { t ->
                        FilterChip(
                            selected = clip.transition == t,
                            onClick = { vm.updateClip(clip.id) { it.copy(transition = t) } },
                            label = { Text(t.label) },
                        )
                    }
                }
                if (clip.transition != TransitionType.NONE) {
                    LabeledSlider(
                        label = "Duración",
                        valueText = "%.1f s".format(clip.transitionMs / 1000f),
                        value = clip.transitionMs.toFloat(),
                        range = 200f..MAX_TRANSITION_MS.toFloat(),
                        onChange = { v -> vm.updateClip(clip.id, live = true) { it.copy(transitionMs = v.toLong()) } },
                        onDone = vm::commitLive,
                    )
                }
            }
        }

        Panel.LAYER_ANIM -> if (text != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Entrada", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LayerAnim.entries.filter { it != LayerAnim.TYPEWRITER || !text.isImage }.forEach { a ->
                        FilterChip(
                            selected = text.animIn == a,
                            onClick = { vm.updateText(text.id) { it.copy(animIn = a) } },
                            label = { Text(a.label) },
                        )
                    }
                }
                Text("Salida", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LayerAnim.entries.filter { it != LayerAnim.TYPEWRITER }.forEach { a ->
                        FilterChip(
                            selected = text.animOut == a,
                            onClick = { vm.updateText(text.id) { it.copy(animOut = a) } },
                            label = { Text(a.label) },
                        )
                    }
                }
            }
        }

        Panel.LAYER_VOLUME -> if (text != null) {
            Column {
                LabeledSlider(
                    label = "Volumen",
                    valueText = "${(text.volume * 100).roundToInt()} %",
                    value = text.volume,
                    range = 0f..2f,
                    onChange = { v -> vm.updateText(text.id, live = true) { it.copy(volume = v) } },
                    onDone = vm::commitLive,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Silenciar", Modifier.weight(1f))
                    Switch(checked = text.muted, onCheckedChange = { m -> vm.updateText(text.id) { it.copy(muted = m) } })
                }
            }
        }

        Panel.IMAGE_STYLE -> if (text != null) {
            Column {
                LabeledSlider(
                    label = "Tamaño",
                    valueText = "${(text.scale * 100).roundToInt()} %",
                    value = text.scale,
                    range = 0.1f..3f,
                    onChange = { v -> vm.updateText(text.id, live = true) { it.copy(scale = v) } },
                    onDone = vm::commitLive,
                )
                LabeledSlider(
                    label = "Opacidad",
                    valueText = "${(text.opacity * 100).roundToInt()} %",
                    value = text.opacity,
                    range = 0f..1f,
                    onChange = { v -> vm.updateText(text.id, live = true) { it.copy(opacity = v) } },
                    onDone = vm::commitLive,
                )
                LabeledSlider(
                    label = "Rotación",
                    valueText = "${text.rotation.roundToInt()}°",
                    value = text.rotation,
                    range = -180f..180f,
                    onChange = { v -> vm.updateText(text.id, live = true) { it.copy(rotation = v) } },
                    onDone = vm::commitLive,
                )
            }
        }

        Panel.TEXT_STYLE -> if (text != null) {
            Column(Modifier.padding(bottom = 4.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextFont.entries.forEach { f ->
                        FilterChip(
                            selected = text.font == f,
                            onClick = { vm.updateText(text.id) { it.copy(font = f) } },
                            label = { Text(f.label) },
                        )
                    }
                }
                LabeledSlider(
                    label = "Tamaño",
                    valueText = "${(text.scale * 100).roundToInt()} %",
                    value = text.scale,
                    range = 0.4f..3f,
                    onChange = { v -> vm.updateText(text.id, live = true) { it.copy(scale = v) } },
                    onDone = vm::commitLive,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Color", fontSize = 12.sp)
                    TextColors.forEach { c ->
                        ColorDot(c, selected = text.colorArgb == c) { vm.updateText(text.id) { it.copy(colorArgb = c) } }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Fondo", fontSize = 12.sp)
                    BackgroundColors.forEach { c ->
                        ColorDot(c, selected = text.backgroundArgb == c) { vm.updateText(text.id) { it.copy(backgroundArgb = c) } }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Contorno", fontSize = 12.sp)
                    OutlineColors.forEach { c ->
                        ColorDot(c, selected = text.outlineArgb == c) { vm.updateText(text.id) { it.copy(outlineArgb = c) } }
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = text.bold,
                        onClick = { vm.updateText(text.id) { it.copy(bold = !it.bold) } },
                        label = { Text("Negrita", fontWeight = FontWeight.Bold) },
                    )
                    FilterChip(
                        selected = text.italic,
                        onClick = { vm.updateText(text.id) { it.copy(italic = !it.italic) } },
                        label = { Text("Cursiva") },
                    )
                    FilterChip(
                        selected = text.shadow,
                        onClick = { vm.updateText(text.id) { it.copy(shadow = !it.shadow) } },
                        label = { Text("Sombra") },
                    )
                }
                LabeledSlider(
                    label = "Opacidad",
                    valueText = "${(text.opacity * 100).roundToInt()} %",
                    value = text.opacity,
                    range = 0f..1f,
                    onChange = { v -> vm.updateText(text.id, live = true) { it.copy(opacity = v) } },
                    onDone = vm::commitLive,
                )
                LabeledSlider(
                    label = "Rotación",
                    valueText = "${text.rotation.roundToInt()}°",
                    value = text.rotation,
                    range = -180f..180f,
                    onChange = { v -> vm.updateText(text.id, live = true) { it.copy(rotation = v) } },
                    onDone = vm::commitLive,
                )
            }
        }

        Panel.TEXT_POSITION -> if (text != null) {
            Column {
                LabeledSlider(
                    label = "Horizontal",
                    valueText = "${(text.x * 100).roundToInt()}",
                    value = text.x,
                    range = -1f..1f,
                    onChange = { v -> vm.updateText(text.id, live = true) { it.copy(x = v) } },
                    onDone = vm::commitLive,
                )
                LabeledSlider(
                    label = "Vertical",
                    valueText = "${(text.y * 100).roundToInt()}",
                    value = text.y,
                    range = -1f..1f,
                    onChange = { v -> vm.updateText(text.id, live = true) { it.copy(y = v) } },
                    onDone = vm::commitLive,
                )
            }
        }

        Panel.TEXT_TIME -> if (text != null) {
            Column {
                Text(
                    "${formatTime(text.startMs)} – ${formatTime(text.endMs)}",
                    fontSize = 12.sp, color = Palette.OnSurfaceMuted,
                )
                RangeSlider(
                    value = text.startMs.toFloat()..text.endMs.toFloat(),
                    onValueChange = { r ->
                        vm.updateText(text.id, live = true) {
                            it.copy(startMs = r.start.toLong(), endMs = maxOf(r.endInclusive.toLong(), r.start.toLong() + MIN_CLIP_DURATION_MS))
                        }
                    },
                    onValueChangeFinished = vm::commitLive,
                    valueRange = 0f..maxOf(project.durationMs.toFloat(), 1f),
                )
                if (text.isVideo) {
                    LabeledSlider(
                        label = "El vídeo empieza en",
                        valueText = formatTime(text.trimStartMs),
                        value = text.trimStartMs.toFloat(),
                        range = 0f..maxOf((text.sourceDurationMs - MIN_CLIP_DURATION_MS).toFloat(), 1f),
                        onChange = { v -> vm.updateText(text.id, live = true) { it.copy(trimStartMs = v.toLong()) } },
                        onDone = vm::commitLive,
                    )
                }
            }
        }
    }
}

/** Escala logarítmica: 0 → 0,25×, 0,5 → 1×, 1 → 4×. */
private fun sliderToSpeed(v: Float): Float {
    val s = Math.pow(16.0, v.toDouble()).toFloat() * 0.25f
    return (s * 20).roundToInt() / 20f
}

private fun speedToSlider(s: Float): Float = (Math.log((s / 0.25f).toDouble()) / Math.log(16.0)).toFloat().coerceIn(0f, 1f)

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

@Composable
fun AdjustmentSliders(
    adjustments: Adjustments,
    onChange: (Adjustments) -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit,
) {
    val items: List<Triple<String, Float, (Float) -> Adjustments>> = listOf(
        Triple("Brillo", adjustments.brightness) { v -> adjustments.copy(brightness = v) },
        Triple("Contraste", adjustments.contrast) { v -> adjustments.copy(contrast = v) },
        Triple("Saturación", adjustments.saturation) { v -> adjustments.copy(saturation = v) },
        Triple("Temperatura", adjustments.warmth) { v -> adjustments.copy(warmth = v) },
        Triple("Matiz", adjustments.tint) { v -> adjustments.copy(tint = v) },
        Triple("Tono", adjustments.hue) { v -> adjustments.copy(hue = v) },
    )
    Column {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items.forEach { (label, value, update) ->
                Column(Modifier.width(150.dp)) {
                    LabeledSlider(
                        label = label,
                        valueText = "${(value * 100).roundToInt()}",
                        value = value,
                        range = -1f..1f,
                        onChange = { onChange(update(it)) },
                        onDone = onDone,
                    )
                }
            }
        }
        if (!adjustments.isNeutral) {
            Text(
                "Restablecer",
                color = Palette.Coral,
                fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onReset).padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ColorDot(argb: Long, selected: Boolean, onClick: () -> Unit) {
    val color = Color(argb.toInt())
    Box(
        Modifier
            .size(28.dp)
            .border(if (selected) 3.dp else 1.5.dp, if (selected) Palette.Coral else Palette.OnSurfaceMuted, CircleShape)
            .padding(3.dp)
            .background(if (argb ushr 24 == 0L) Palette.Surface else color, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (argb ushr 24 == 0L) Text("∅", fontSize = 12.sp)
    }
}

private val TextColors = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFF6B4A, 0xFFFFC94A, 0xFF7C6CFF, 0xFF3DD6C6, 0xFFFF4D8D, 0xFF5BE37D,
)
private val OutlineColors = listOf(
    0x00000000L, 0xFF000000, 0xFFFFFFFF, 0xFFFF7A59, 0xFFFFD166, 0xFF9B8CFF,
)
private val BackgroundColors = listOf(
    0x00000000L, 0xCC000000, 0xCCFFFFFF, 0xFFFF6B4A, 0xFFFFC94A, 0xFF7C6CFF,
)

