package com.thenovisx.edicion.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenovisx.edicion.core.Clip
import com.thenovisx.edicion.core.LANE_MUSIC
import com.thenovisx.edicion.core.LANE_VOICE
import com.thenovisx.edicion.core.MediaKind
import com.thenovisx.edicion.core.Project
import com.thenovisx.edicion.core.Timeline
import com.thenovisx.edicion.core.TransitionType
import com.thenovisx.edicion.core.formatTime
import com.thenovisx.edicion.media.Thumbnails
import com.thenovisx.edicion.ui.theme.Palette
import kotlin.math.roundToInt

private val RulerHeight = 22.dp
private val VideoHeight = 58.dp
private val LaneHeight = 30.dp
private val TrackGap = 5.dp

/**
 * Línea de tiempo con el cabezal fijo en el centro. Arrastrar mueve el tiempo,
 * pellizcar cambia el zoom y tocar un elemento lo selecciona.
 * Pistas, de arriba abajo: vídeo, capas (texto, stickers, imágenes), música y voz.
 */
@Composable
fun TimelineView(
    project: Project,
    playheadMs: Long,
    selection: Selection,
    onScrub: (Long) -> Unit,
    onSelect: (Selection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // Píxeles por segundo (zoom).
    var pxPerSec by remember { mutableStateOf(with(density) { 64.dp.toPx() }) }
    val currentPlayhead by rememberUpdatedState(playheadMs)
    val currentProject by rememberUpdatedState(project)
    val currentSelection by rememberUpdatedState(selection)
    val scrub by rememberUpdatedState(onScrub)
    val selectItem by rememberUpdatedState(onSelect)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(Palette.Background)
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val centerPx = widthPx / 2f
        val center by rememberUpdatedState(centerPx)
        fun xOf(ms: Long): Float = centerPx + (ms - playheadMs) / 1000f * pxPerSec

        // Las capas se reparten en varias filas para que no se tapen entre sí.
        val layerRows = remember(project.texts) { Timeline.layerRows(project.texts, MAX_LAYER_ROWS) }
        val layerRowCount = layerRowCount(project)
        val currentLayerRows by rememberUpdatedState(layerRows)
        val currentRowCount by rememberUpdatedState(layerRowCount)
        val rulerPx = with(density) { RulerHeight.toPx() }
        val videoBottomPx = rulerPx + with(density) { (VideoHeight + TrackGap).toPx() }
        val lanePx = with(density) { (LaneHeight + TrackGap).toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) pxPerSec = (pxPerSec * zoom).coerceIn(8f, 2000f)
                        if (pan.x != 0f) scrub(currentPlayhead + (-pan.x / pxPerSec * 1000f).toLong())
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos: Offset ->
                        val p = currentProject
                        val tMs = currentPlayhead + ((pos.x - center) / pxPerSec * 1000f).toLong()
                        fun audioIn(lane: Int) = p.audio.firstOrNull { it.lane == lane && tMs in it.startMs until it.endMs }
                            ?.let { Selection.Audio(it.id) }
                        // Fila tocada por debajo de la pista de vídeo: primero las de capas, luego música y voz.
                        val lane = if (pos.y < videoBottomPx) -1 else ((pos.y - videoBottomPx) / lanePx).toInt()
                        val rows = currentLayerRows
                        val sel: Selection = when {
                            pos.y < rulerPx -> Selection.None
                            lane < 0 -> Timeline.clipAt(p, tMs)
                                ?.takeIf { tMs in 0 until p.durationMs }
                                ?.let { Selection.VideoClip(p.clips[it.index].id) }
                            lane < currentRowCount -> {
                                // Si varias capas coinciden en la fila, cada toque pasa a la siguiente.
                                val hits = p.texts.filterIndexed { i, l -> rows.getOrNull(i) == lane && l.isVisibleAt(tMs) }
                                val cur = hits.indexOfFirst { currentSelection == Selection.Text(it.id) }
                                hits.getOrNull(if (cur < 0) hits.lastIndex else cur - 1)?.let { Selection.Text(it.id) }
                            }
                            lane == currentRowCount -> audioIn(LANE_MUSIC)
                            lane == currentRowCount + 1 -> audioIn(LANE_VOICE)
                            else -> null
                        } ?: Selection.None
                        selectItem(if (sel == currentSelection) Selection.None else sel)
                    }
                }
        ) {
            Column(Modifier.fillMaxWidth()) {
                Ruler(playheadMs, pxPerSec, centerPx, Modifier.fillMaxWidth().height(RulerHeight))

                // Pista de vídeo
                Lane(VideoHeight) {
                    val starts = Timeline.clipStarts(project)
                    project.clips.forEachIndexed { i, clip ->
                        val x = xOf(starts[i])
                        val w = clip.durationMs / 1000f * pxPerSec
                        if (x + w >= 0 && x <= widthPx) {
                            ClipBox(clip, x, w, selection == Selection.VideoClip(clip.id))
                        }
                    }
                    // Marca de transición sobre cada corte que la tiene.
                    project.clips.forEachIndexed { i, clip ->
                        if (clip.transition != TransitionType.NONE) {
                            val x = xOf(starts[i])
                            if (x >= -40 && x <= widthPx + 40) TransitionBadge(x)
                        }
                    }
                    if (project.clips.isEmpty()) {
                        Text(
                            "Toca «Medios» para añadir vídeos o fotos",
                            color = Palette.OnSurfaceMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                Spacer(Modifier.height(TrackGap))

                // Filas de capas
                repeat(layerRowCount) { row ->
                    Lane(LaneHeight) {
                        project.texts.forEachIndexed { i, t ->
                            val x = xOf(t.startMs)
                            val w = t.durationMs / 1000f * pxPerSec
                            if (layerRows.getOrNull(i) == row && x + w >= 0 && x <= widthPx) {
                                LaneItem(
                                    label = t.text,
                                    icon = when {
                                        t.isVideo -> Icons.Default.Movie
                                        t.isImage -> Icons.Default.Image
                                        else -> Icons.Default.TextFields
                                    },
                                    color = when {
                                        t.isVideo -> Palette.Blue
                                        t.isImage -> Palette.Pink
                                        else -> Palette.Violet
                                    },
                                    xPx = x,
                                    widthPx = w,
                                    selected = selection == Selection.Text(t.id),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(TrackGap))
                }

                listOf(LANE_MUSIC to Palette.Teal, LANE_VOICE to Palette.Amber).forEach { (lane, color) ->
                    Lane(LaneHeight) {
                        project.audio.filter { it.lane == lane }.forEach { a ->
                            val x = xOf(a.startMs)
                            val w = a.durationMs / 1000f * pxPerSec
                            if (x + w >= 0 && x <= widthPx) {
                                LaneItem(
                                    label = a.name.ifEmpty { "Audio" },
                                    icon = if (lane == LANE_VOICE) Icons.Default.Mic else Icons.Default.MusicNote,
                                    color = color,
                                    xPx = x,
                                    widthPx = w,
                                    selected = selection == Selection.Audio(a.id),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(TrackGap))
                }
            }

            // Iconos fijos que indican qué es cada pista.
            Column(Modifier.padding(top = RulerHeight, start = 4.dp)) {
                LaneLabel(Icons.Default.Movie, VideoHeight)
                Spacer(Modifier.height(TrackGap))
                repeat(layerRowCount) {
                    LaneLabel(Icons.Default.Layers, LaneHeight)
                    Spacer(Modifier.height(TrackGap))
                }
                LaneLabel(Icons.Default.MusicNote, LaneHeight)
                Spacer(Modifier.height(TrackGap))
                LaneLabel(Icons.Default.Mic, LaneHeight)
            }

            // Cabezal
            Box(
                Modifier
                    .offset { IntOffset((centerPx - with(density) { 1.5.dp.toPx() }).roundToInt(), 0) }
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(Palette.Playhead, RoundedCornerShape(2.dp))
            )
        }
    }
}

/** Fila de la línea de tiempo con un fondo visible para que se distinga del resto. */
@Composable
private fun Lane(height: Dp, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(Palette.Surface),
        content = content,
    )
}

@Composable
private fun LaneLabel(icon: ImageVector, height: Dp) {
    Box(Modifier.height(height), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(22.dp).background(Palette.Background.copy(alpha = 0.75f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Palette.OnSurfaceMuted, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun TransitionBadge(xPx: Float) {
    val density = LocalDensity.current
    val half = with(density) { 11.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((xPx - half).roundToInt(), with(density) { ((VideoHeight - 22.dp) / 2).roundToPx() }) }
            .size(22.dp)
            .background(Palette.Amber, CircleShape)
            .border(1.5.dp, Color.Black, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.SwapHoriz, "Transición", tint = Color.Black, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Ruler(playheadMs: Long, pxPerSec: Float, centerPx: Float, modifier: Modifier) {
    // Elige un intervalo de marcas legible según el zoom.
    val stepMs = listOf(100L, 250L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L)
        .firstOrNull { it / 1000f * pxPerSec >= 70f } ?: 120_000L
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val widthPx = with(density) { maxWidth.toPx() }
        val firstMs = (((playheadMs - centerPx / pxPerSec * 1000).toLong() / stepMs) * stepMs).coerceAtLeast(0)
        val lastMs = playheadMs + ((widthPx - centerPx) / pxPerSec * 1000).toLong()
        Canvas(Modifier.fillMaxSize()) {
            var t = firstMs
            while (t <= lastMs) {
                val x = centerPx + (t - playheadMs) / 1000f * pxPerSec
                drawLine(Palette.OnSurfaceMuted, Offset(x, size.height * 0.5f), Offset(x, size.height), strokeWidth = 2f)
                val half = x + stepMs / 2000f * pxPerSec
                drawLine(Palette.Outline, Offset(half, size.height * 0.75f), Offset(half, size.height), strokeWidth = 2f)
                t += stepMs
            }
        }
        var t = firstMs
        while (t <= lastMs) {
            val x = centerPx + (t - playheadMs) / 1000f * pxPerSec
            Text(
                formatTime(t, tenths = stepMs < 1000),
                color = Palette.OnSurfaceMuted,
                fontSize = 10.sp,
                modifier = Modifier.offset { IntOffset((x + 4).roundToInt(), 0) },
            )
            t += stepMs
        }
    }
}

@Composable
private fun ClipBox(clip: Clip, xPx: Float, widthPx: Float, selected: Boolean) {
    val density = LocalDensity.current
    val context = LocalContext.current
    var thumb by remember(clip.uri, clip.trimStartMs) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(clip.uri, clip.trimStartMs) {
        thumb = Thumbnails.load(context, clip.uri, clip.kind, clip.trimStartMs)
    }
    val wDp: Dp = with(density) { (widthPx - 2f).coerceAtLeast(2f).toDp() }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .offset { IntOffset(xPx.roundToInt() + 1, 0) }
            .width(wDp)
            .fillMaxHeight()
            .clip(shape)
            .background(Palette.SurfaceHigh)
            .border(if (selected) 3.dp else 1.dp, if (selected) Palette.Coral else Palette.Outline, shape)
    ) {
        thumb?.let { bmp ->
            // Repite la miniatura a lo largo del clip, como una tira de fotogramas.
            val tileDp = VideoHeight * (bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).coerceIn(0.5f, 1.8f)
            val tiles = (wDp / tileDp).toInt() + 1
            Row(Modifier.fillMaxSize().padding(if (selected) 3.dp else 1.dp)) {
                repeat(tiles.coerceAtMost(40)) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(tileDp).fillMaxHeight(),
                    )
                }
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(3.dp)
                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            val speed = if (clip.kind == MediaKind.VIDEO && clip.speed != 1f) " · ${clip.speed}×" else ""
            Text(formatTime(clip.durationMs) + speed, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LaneItem(
    label: String,
    icon: ImageVector,
    color: Color,
    xPx: Float,
    widthPx: Float,
    selected: Boolean,
) {
    val density = LocalDensity.current
    val wDp = with(density) { (widthPx - 2f).coerceAtLeast(2f).toDp() }
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .offset { IntOffset(xPx.roundToInt() + 1, 0) }
            .width(wDp)
            .fillMaxHeight()
            .clip(shape)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.padding(end = 3.dp).size(14.dp), tint = Color.Black)
        Text(
            label,
            color = Color.Black,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Como mucho se muestran estas filas de capas; si hay más capas simultáneas comparten la última. */
private const val MAX_LAYER_ROWS = 4

private fun layerRowCount(project: Project): Int =
    (Timeline.layerRows(project.texts, MAX_LAYER_ROWS).maxOrNull() ?: 0) + 1

/** Altura total de la línea de tiempo: crece con el número de filas de capas. */
fun timelineHeight(project: Project): Dp {
    val lanes = layerRowCount(project) + 2
    return RulerHeight + VideoHeight + TrackGap + (LaneHeight + TrackGap) * lanes + 4.dp
}
