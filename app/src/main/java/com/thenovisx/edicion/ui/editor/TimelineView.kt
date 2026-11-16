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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenovisx.edicion.core.Clip
import com.thenovisx.edicion.core.MediaKind
import com.thenovisx.edicion.core.Project
import com.thenovisx.edicion.core.Timeline
import com.thenovisx.edicion.core.formatTime
import com.thenovisx.edicion.media.Thumbnails
import com.thenovisx.edicion.ui.theme.Palette
import kotlin.math.roundToInt

private val RulerHeight = 22.dp
private val VideoHeight = 60.dp
private val AudioHeight = 34.dp
private val TextHeight = 30.dp
private val TrackGap = 6.dp

/**
 * Línea de tiempo con el cabezal fijo en el centro. Arrastrar mueve el tiempo,
 * pellizcar cambia el zoom y tocar un elemento lo selecciona.
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

        val rowsTop = with(density) {
            listOf(
                RulerHeight.toPx(),
                (RulerHeight + VideoHeight + TrackGap).toPx(),
                (RulerHeight + VideoHeight + AudioHeight + TrackGap * 2).toPx(),
                (RulerHeight + VideoHeight + AudioHeight + TextHeight + TrackGap * 3).toPx(),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            pxPerSec = (pxPerSec * zoom).coerceIn(8f, 2000f)
                        }
                        if (pan.x != 0f) {
                            val deltaMs = (-pan.x / pxPerSec * 1000f).toLong()
                            scrub(currentPlayhead + deltaMs)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos: Offset ->
                        val p = currentProject
                        val tMs = currentPlayhead + ((pos.x - center) / pxPerSec * 1000f).toLong()
                        val sel: Selection = when {
                            pos.y < rowsTop[0] -> Selection.None
                            pos.y < rowsTop[1] -> Timeline.clipAt(p, tMs)
                                ?.takeIf { tMs in 0 until p.durationMs }
                                ?.let { Selection.VideoClip(p.clips[it.index].id) } ?: Selection.None
                            pos.y < rowsTop[2] -> p.audio.firstOrNull { tMs in it.startMs until it.endMs }
                                ?.let { Selection.Audio(it.id) } ?: Selection.None
                            pos.y < rowsTop[3] -> p.texts.lastOrNull { it.isVisibleAt(tMs) }
                                ?.let { Selection.Text(it.id) } ?: Selection.None
                            else -> Selection.None
                        }
                        selectItem(if (sel == currentSelection) Selection.None else sel)
                    }
                }
        ) {
            Column(Modifier.fillMaxWidth()) {
                Ruler(playheadMs, pxPerSec, centerPx, Modifier.fillMaxWidth().height(RulerHeight))

                // Pista de vídeo
                Box(Modifier.fillMaxWidth().height(VideoHeight)) {
                    val starts = Timeline.clipStarts(project)
                    project.clips.forEachIndexed { i, clip ->
                        val x = xOf(starts[i])
                        val w = clip.durationMs / 1000f * pxPerSec
                        if (x + w >= 0 && x <= widthPx) {
                            ClipBox(
                                clip = clip,
                                xPx = x,
                                widthPx = w,
                                selected = selection == Selection.VideoClip(clip.id),
                            )
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

                // Pista de audio
                Box(Modifier.fillMaxWidth().height(AudioHeight)) {
                    project.audio.forEach { a ->
                        val x = xOf(a.startMs)
                        val w = a.durationMs / 1000f * pxPerSec
                        if (x + w >= 0 && x <= widthPx) {
                            LaneItem(
                                label = a.name.ifEmpty { "Audio" },
                                icon = { Icon(Icons.Default.MusicNote, null, Modifier.padding(end = 2.dp).width(14.dp), tint = Color.Black) },
                                color = Palette.Teal,
                                xPx = x,
                                widthPx = w,
                                height = AudioHeight,
                                selected = selection == Selection.Audio(a.id),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(TrackGap))

                // Pista de textos
                Box(Modifier.fillMaxWidth().height(TextHeight)) {
                    project.texts.forEach { t ->
                        val x = xOf(t.startMs)
                        val w = t.durationMs / 1000f * pxPerSec
                        if (x + w >= 0 && x <= widthPx) {
                            LaneItem(
                                label = t.text,
                                icon = { Icon(Icons.Default.TextFields, null, Modifier.padding(end = 2.dp).width(14.dp), tint = Color.White) },
                                color = Palette.Violet,
                                xPx = x,
                                widthPx = w,
                                height = TextHeight,
                                selected = selection == Selection.Text(t.id),
                                textColor = Color.White,
                            )
                        }
                    }
                }
            }

            // Cabezal
            Box(
                Modifier
                    .offset { IntOffset((centerPx - with(density) { 1.dp.toPx() }).roundToInt(), 0) }
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Palette.Playhead)
            )
        }
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
                drawLine(Palette.OnSurfaceMuted, Offset(x, size.height * 0.55f), Offset(x, size.height), strokeWidth = 1.5f)
                val half = x + stepMs / 2000f * pxPerSec
                drawLine(Palette.OnSurfaceMuted.copy(alpha = 0.4f), Offset(half, size.height * 0.8f), Offset(half, size.height), strokeWidth = 1f)
                t += stepMs
            }
        }
        var t = firstMs
        while (t <= lastMs) {
            val x = centerPx + (t - playheadMs) / 1000f * pxPerSec
            Text(
                formatTime(t, tenths = stepMs < 1000),
                color = Palette.OnSurfaceMuted,
                fontSize = 9.sp,
                modifier = Modifier.offset { IntOffset((x + 3).roundToInt(), 0) },
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
    Box(
        Modifier
            .offset { IntOffset(xPx.roundToInt() + 1, 0) }
            .width(wDp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.SurfaceHigh)
            .then(if (selected) Modifier.border(2.dp, Palette.Coral, RoundedCornerShape(6.dp)) else Modifier)
    ) {
        thumb?.let { bmp ->
            // Repite la miniatura a lo largo del clip, como una tira de fotogramas.
            val tileDp = VideoHeight * (bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).coerceIn(0.5f, 1.8f)
            val tiles = (wDp / tileDp).toInt() + 1
            Row(Modifier.fillMaxSize()) {
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
                .align(Alignment.BottomStart)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(topEnd = 6.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            val speed = if (clip.kind == MediaKind.VIDEO && clip.speed != 1f) " · ${clip.speed}×" else ""
            Text(formatTime(clip.durationMs) + speed, color = Color.White, fontSize = 9.sp)
        }
    }
}

@Composable
private fun LaneItem(
    label: String,
    icon: @Composable () -> Unit,
    color: Color,
    xPx: Float,
    widthPx: Float,
    height: Dp,
    selected: Boolean,
    textColor: Color = Color.Black,
) {
    val density = LocalDensity.current
    val wDp = with(density) { (widthPx - 2f).coerceAtLeast(2f).toDp() }
    Row(
        Modifier
            .offset { IntOffset(xPx.roundToInt() + 1, 0) }
            .width(wDp)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = if (selected) 1f else 0.75f))
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(label, color = textColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Altura total recomendada de la línea de tiempo. */
val TimelineHeight: Dp = RulerHeight + VideoHeight + AudioHeight + TextHeight + TrackGap * 3 + 8.dp
