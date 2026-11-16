package com.thenovisx.edicion.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.thenovisx.edicion.core.LayerAnim
import com.thenovisx.edicion.core.LayerMath
import com.thenovisx.edicion.core.Project
import com.thenovisx.edicion.core.TextLayer
import com.thenovisx.edicion.media.LayerRenderer
import com.thenovisx.edicion.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Dibuja las capas (texto, stickers, imágenes y vídeos) encima de la vista previa y permite
 * manipular la capa seleccionada con los dedos: arrastrar para moverla, pellizcar para
 * cambiar el tamaño y girar con dos dedos. Tocar una capa la selecciona.
 */
@Composable
fun LayerCanvas(
    project: Project,
    playheadMs: Long,
    isPlaying: Boolean,
    selection: Selection,
    onSelect: (Selection) -> Unit,
    /** Desplazamiento (en unidades normalizadas del lienzo), factor de zoom y giro en grados. */
    onTransform: (id: String, pan: Offset, zoom: Float, rotation: Float) -> Unit,
    onTransformEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val selectedId = (selection as? Selection.Text)?.id
    val sizes = remember { mutableStateMapOf<String, IntSize>() }
    val currentProject by rememberUpdatedState(project)
    val currentPlayhead by rememberUpdatedState(playheadMs)
    val currentSelectedId by rememberUpdatedState(selectedId)
    val select by rememberUpdatedState(onSelect)
    val transform by rememberUpdatedState(onTransform)
    val transformEnd by rememberUpdatedState(onTransformEnd)

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val canvasSize by rememberUpdatedState(IntSize(w.coerceAtLeast(1), h.coerceAtLeast(1)))

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val cs = canvasSize
                        // De arriba abajo: gana la capa que está más al frente.
                        val hit = currentProject.texts.lastOrNull { l ->
                            if (!l.isVisibleAt(currentPlayhead)) return@lastOrNull false
                            val s = sizes[l.id] ?: return@lastOrNull false
                            val cx = cs.width / 2f + l.x * cs.width / 2f
                            val cy = cs.height / 2f + l.y * cs.height / 2f
                            val margin = 24.dp.toPx()
                            abs(pos.x - cx) <= s.width / 2f + margin && abs(pos.y - cy) <= s.height / 2f + margin
                        }
                        select(if (hit == null || hit.id == currentSelectedId) Selection.None else Selection.Text(hit.id))
                    }
                }
                .pointerInput(selectedId) {
                    if (selectedId == null) return@pointerInput
                    detectTransformGestures { _, pan, zoom, rotation ->
                        val cs = canvasSize
                        transform(selectedId, Offset(pan.x / (cs.width / 2f), pan.y / (cs.height / 2f)), zoom, rotation)
                    }
                }
                .pointerInput(selectedId) {
                    if (selectedId == null) return@pointerInput
                    // Al levantar todos los dedos se confirma el movimiento como un paso de deshacer.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                        transformEnd()
                    }
                }
        ) {
            project.texts.forEach { layer ->
                if (layer.isVisibleAt(playheadMs)) key(layer.id) {
                    val st = LayerMath.stateAt(layer, playheadMs)
                    val selected = layer.id == selectedId
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                translationX = (layer.x + st.dx) * w / 2f
                                translationY = (layer.y + st.dy) * h / 2f
                                scaleX = st.scale
                                scaleY = st.scale
                                rotationZ = layer.rotation
                                // La capa seleccionada nunca desaparece del todo, para poder cogerla.
                                alpha = if (selected) st.alpha.coerceAtLeast(0.35f) else st.alpha
                            }
                            .onSizeChanged { sizes[layer.id] = it }
                            .then(
                                if (selected) Modifier.border(2.dp, Color.White).border(1.dp, Palette.Coral)
                                else Modifier
                            )
                    ) {
                        when {
                            layer.isVideo -> {
                                val wd = with(density) { LayerRenderer.mediaWidth(layer, w).toDp() }
                                val hd = with(density) { LayerRenderer.mediaHeight(layer, w).toDp() }
                                VideoLayerView(layer, playheadMs, isPlaying, Modifier.requiredSize(wd, hd))
                            }
                            layer.isImage -> {
                                val wd = with(density) { LayerRenderer.mediaWidth(layer, w).toDp() }
                                val hd = with(density) { LayerRenderer.mediaHeight(layer, w).toDp() }
                                ImageLayerView(layer.imageUri!!, Modifier.requiredSize(wd, hd))
                            }
                            else -> TextLayerView(layer, playheadMs, w, h)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextLayerView(layer: TextLayer, playheadMs: Long, canvasW: Int, canvasH: Int) {
    val density = LocalDensity.current
    val shown = LayerMath.typewriterChars(layer, playheadMs)?.let { layer.text.substring(0, it) } ?: layer.text
    // Solo se vuelve a dibujar cuando cambia algo que afecta al aspecto del texto.
    val styleKey = layer.copy(
        id = "", x = 0f, y = 0f, startMs = 0, endMs = 0, rotation = 0f, opacity = 1f,
        animIn = LayerAnim.NONE, animOut = LayerAnim.NONE,
    )
    val bitmap = remember(styleKey, shown, canvasW, canvasH) {
        LayerRenderer.renderText(layer, shown, canvasW, canvasH)
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = layer.text,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.requiredSize(
            with(density) { bitmap.width.toDp() },
            with(density) { bitmap.height.toDp() },
        ),
    )
}

@Composable
private fun ImageLayerView(uri: String, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { LayerRenderer.loadImage(context, uri, 720) }.getOrNull() }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bmp.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = modifier)
    } else {
        Box(modifier.background(Palette.SurfaceHigh))
    }
}

/**
 * Vídeo superpuesto en la vista previa: un reproductor propio que sigue al cabezal del proyecto.
 */
@Composable
private fun VideoLayerView(layer: TextLayer, playheadMs: Long, isPlaying: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val uri = layer.videoUri ?: return
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    LaunchedEffect(player, layer.volume, layer.muted) {
        player.volume = if (layer.muted) 0f else layer.volume.coerceIn(0f, 1f)
    }
    val target = LayerMath.videoPositionMs(layer, playheadMs)
    LaunchedEffect(player, isPlaying) {
        if (isPlaying) {
            player.seekTo(target)
            player.play()
        } else {
            player.pause()
        }
    }
    LaunchedEffect(player, target, isPlaying) {
        // En pausa sigue al cabezal; reproduciendo solo corrige si se desincroniza.
        if (!isPlaying || abs(player.currentPosition - target) > 400) player.seekTo(target)
    }
    AndroidView(
        factory = { ctx -> TextureView(ctx) },
        update = { player.setVideoTextureView(it) },
        modifier = modifier,
    )
}
