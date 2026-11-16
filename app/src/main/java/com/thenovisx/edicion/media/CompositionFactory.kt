package com.thenovisx.edicion.media

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import com.thenovisx.edicion.core.AudioClip
import com.thenovisx.edicion.core.Clip
import com.thenovisx.edicion.core.ClipEffect
import com.thenovisx.edicion.core.ColorPipeline
import com.thenovisx.edicion.core.LANE_LAYER_BASE
import com.thenovisx.edicion.core.MediaKind
import com.thenovisx.edicion.core.Project
import com.thenovisx.edicion.core.TextLayer
import com.thenovisx.edicion.core.Timeline

/**
 * Convierte un [Project] en una [Composition] de Media3. Se usa tanto para la vista previa
 * como para la exportación, así lo que se ve es lo que se exporta.
 */
class CompositionFactory(private val context: Context) {

    /**
     * @param shortSide resolución de salida (lado corto en píxeles).
     * @param forPreview la vista previa no admite huecos ni algunos efectos de velocidad.
     */
    fun build(project: Project, shortSide: Int, forPreview: Boolean): Composition? {
        if (project.clips.isEmpty()) return null
        val (w, h) = project.aspectRatio.outputSize(shortSide)

        val videoItems = project.clips.map { videoItem(it, w, h) }
        val aspect = w.toFloat() / h
        val sequences = mutableListOf(EditedMediaItemSequence(videoItems))

        // En la vista previa las capas las dibuja la propia interfaz (así se pueden mover con el
        // dedo al instante), de modo que solo se incluyen al exportar.
        val layers = if (forPreview) emptyList() else project.texts
        val layerAudio = layers.withIndex()
            .filter { (_, l) -> l.isVideo && l.hasAudio && !l.muted && l.volume > 0f }
            .map { (i, l) ->
                AudioClip(
                    id = l.id,
                    uri = l.videoUri!!,
                    sourceDurationMs = l.sourceDurationMs,
                    trimStartMs = l.trimStartMs,
                    trimEndMs = minOf(l.trimStartMs + l.durationMs, l.sourceDurationMs),
                    startMs = l.startMs,
                    volume = l.volume,
                    lane = LANE_LAYER_BASE + i,
                )
            }
        (project.audio + layerAudio).groupBy { it.lane }.toSortedMap().values.forEach { lane ->
            audioSequence(lane, project.durationMs)?.let { sequences += it }
        }

        val cuts = Timeline.transitionCuts(project)
        // El movimiento de las transiciones va antes que las capas para que los textos no se muevan con él.
        val compositionEffects = transitionMotion(cuts, aspect) + layerEffects(layers, w, h) + transitionFades(cuts)

        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), compositionEffects))
            // Las fotos no tienen audio: generamos silencio para que la pista sea continua.
            .experimentalSetForceAudioTrack(true)
            .build()
    }

    // ---------- Vídeo ----------

    private fun videoItem(clip: Clip, outW: Int, outH: Int): EditedMediaItem {
        val uri = Uri.parse(clip.uri)
        val builder = MediaItem.Builder().setUri(uri)
        clip.mimeType?.let { builder.setMimeType(it) }

        val editedBuilder: EditedMediaItem.Builder
        if (clip.kind == MediaKind.IMAGE) {
            if (clip.mimeType == null) builder.setMimeType(MimeTypes.IMAGE_JPEG)
            builder.setImageDurationMs(clip.trimmedDurationMs)
            editedBuilder = EditedMediaItem.Builder(builder.build())
                .setDurationUs(clip.trimmedDurationMs * 1000)
                .setFrameRate(30)
        } else {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clip.trimEndMs)
                    .build()
            )
            editedBuilder = EditedMediaItem.Builder(builder.build())
                .setDurationUs(clip.sourceDurationMs * 1000)
        }

        val video = mutableListOf<Effect>()
        val audio = mutableListOf<AudioProcessor>()

        if (clip.kind == MediaKind.VIDEO && clip.speed != 1f) {
            val pair = Effects.createExperimentalSpeedChangingEffect(ConstantSpeed(clip.speed))
            audio += pair.first
            video += pair.second
        }
        if (clip.rotation != 0 || clip.flipHorizontal) {
            video += ScaleAndRotateTransformation.Builder()
                .setScale(if (clip.flipHorizontal) -1f else 1f, 1f)
                .setRotationDegrees(-clip.rotation.toFloat())
                .build()
        }
        val color = ColorPipeline(clip.filter, clip.filterIntensity, clip.adjustments)
        if (color.isMatrixOnly) {
            if (!color.matrix.isIdentity) {
                val m = color.matrix.toColumnMajor4x4()
                video += RgbMatrix { _, _ -> m }
            }
        } else {
            // Los ajustes no lineales (luces, sombras, curvas, HSL) se aplican con una tabla de color 3D.
            video += SingleColorLut.createFromCube(color.buildCube())
        }
        // Encaja el clip en el lienzo del proyecto: con bandas negras, o recortando si se pidió rellenar.
        video += Presentation.createForWidthAndHeight(
            outW, outH,
            if (clip.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT,
        )
        if (clip.zoom != 1f || clip.panX != 0f || clip.panY != 0f) {
            val matrix = Matrix().apply {
                postScale(clip.zoom, clip.zoom)
                // Coordenadas normalizadas: +y es hacia arriba, y en el modelo +y es hacia abajo.
                postTranslate(clip.panX, -clip.panY)
            }
            video += MatrixTransformation { matrix }
        }
        if (clip.effect != ClipEffect.NONE) {
            val effect = clip.effect
            val durationMs = clip.durationMs
            val aspect = outW.toFloat() / outH
            video += MatrixTransformation { tUs -> motionMatrix(Timeline.clipEffectAt(effect, tUs / 1000, durationMs).first, aspect) }
            if (effect == ClipEffect.STROBE || effect == ClipEffect.GLITCH) {
                video += RgbMatrix { tUs, _ ->
                    val add = Timeline.clipEffectAt(effect, tUs / 1000, durationMs).second
                    floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, add, add, add, 1f)
                }
            }
        }
        val fx = buildList {
            if (clip.adjustments.vignette > 0f) add(ImageFx.vignette(clip.adjustments.vignette, outW, outH))
            if (clip.adjustments.grain > 0f) add(ImageFx.grain(clip.adjustments.grain, outW, outH))
        }
        if (fx.isNotEmpty()) video += OverlayEffect(ImmutableList.copyOf<TextureOverlay>(fx))

        if (clip.kind == MediaKind.VIDEO && clip.effectiveVolume != 1f) {
            audio += volumeProcessor(clip.effectiveVolume)
        }
        if (clip.kind == MediaKind.VIDEO && (clip.audioFadeInMs > 0 || clip.audioFadeOutMs > 0)) {
            audio += FadeAudioProcessor(clip.audioFadeInMs * 1000, clip.audioFadeOutMs * 1000, clip.durationMs * 1000)
        }

        return editedBuilder.setEffects(Effects(audio, video)).build()
    }

    // ---------- Audio ----------

    private fun audioSequence(lane: List<AudioClip>, total: Long): EditedMediaItemSequence? {
        val clips = lane.sortedBy { it.startMs }.filter { it.startMs < total }
        if (clips.isEmpty()) return null
        val items = mutableListOf<EditedMediaItem>()
        var cursor = 0L
        for (a in clips) {
            if (a.startMs > cursor) items += silenceItems(a.startMs - cursor)
            val end = minOf(a.endMs, total)
            val dur = end - a.startMs
            if (dur <= 0) continue
            items += audioItem(a, dur)
            cursor = end
        }
        if (items.isEmpty()) return null
        // Rellena con silencio hasta el final: la vista previa repite en bucle las pistas más cortas.
        if (cursor < total) items += silenceItems(total - cursor)
        return EditedMediaItemSequence(items)
    }

    private fun audioItem(a: AudioClip, durationMs: Long): EditedMediaItem {
        val item = MediaItem.Builder()
            .setUri(Uri.parse(a.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(a.trimStartMs)
                    .setEndPositionMs(a.trimStartMs + durationMs)
                    .build()
            )
            .build()
        val audio = buildList<AudioProcessor> {
            if (a.pitch != 1f) add(SonicAudioProcessor().apply { setPitch(a.pitch) })
            if (a.volume != 1f) add(volumeProcessor(a.volume))
            if (a.fadeInMs > 0 || a.fadeOutMs > 0) {
                // Si el audio se corta al final del proyecto, el fundido de salida acaba ahí.
                add(FadeAudioProcessor(a.fadeInMs * 1000, a.fadeOutMs * 1000, durationMs * 1000))
            }
        }
        return EditedMediaItem.Builder(item)
            .setRemoveVideo(true)
            .setDurationUs(a.sourceDurationMs * 1000)
            .setEffects(Effects(audio, emptyList()))
            .build()
    }

    private fun silenceItems(durationMs: Long): List<EditedMediaItem> {
        val file = Silence.file(context)
        val out = mutableListOf<EditedMediaItem>()
        var remaining = durationMs
        while (remaining > 0) {
            val chunk = minOf(remaining, Silence.DURATION_MS)
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setMimeType(MimeTypes.AUDIO_WAV)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(chunk).build()
                )
                .build()
            out += EditedMediaItem.Builder(item).setDurationUs(Silence.DURATION_MS * 1000).build()
            remaining -= chunk
        }
        return out
    }

    private fun volumeProcessor(volume: Float): AudioProcessor =
        ChannelMixingAudioProcessor().apply {
            for (channels in 1..8) {
                putChannelMixingMatrix(ChannelMixingMatrix.create(channels, channels).scaleBy(volume))
            }
        }

    // ---------- Capas y transiciones ----------

    private fun layerEffects(layers: List<TextLayer>, outW: Int, outH: Int): List<Effect> {
        if (layers.isEmpty()) return emptyList()
        val overlays = layers.map<TextLayer, TextureOverlay> { LayerOverlay(context, it, outW, outH) }
        return listOf(OverlayEffect(ImmutableList.copyOf(overlays)))
    }

    /** Matriz (en coordenadas normalizadas) de un movimiento de fotograma completo. */
    private fun motionMatrix(m: Timeline.Motion, aspect: Float): Matrix = Matrix().apply {
        if (m.scale != 1f) postScale(m.scale, m.scale)
        if (m.rotation != 0f) {
            // El giro se hace en proporciones reales para no deformar la imagen.
            postScale(aspect, 1f)
            postRotate(m.rotation)
            postScale(1f / aspect, 1f)
        }
        // +y es hacia arriba en coordenadas normalizadas y hacia abajo en el modelo.
        postTranslate(m.dx, -m.dy)
    }

    /** Movimiento (zoom, empuje, giro) alrededor de los cortes que tienen transición. */
    private fun transitionMotion(cuts: List<Timeline.Cut>, aspect: Float): List<Effect> {
        if (cuts.none { Timeline.transitionMotion(listOf(it), it.timeMs) != null }) return emptyList()
        val identity = Matrix()
        return listOf(MatrixTransformation { tUs ->
            Timeline.transitionMotion(cuts, tUs / 1000)?.let { motionMatrix(it, aspect) } ?: identity
        })
    }

    /** Fundidos a negro o blanco alrededor de los cortes que tienen transición. */
    private fun transitionFades(cuts: List<Timeline.Cut>): List<Effect> {
        if (cuts.isEmpty()) return emptyList()
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        return listOf(RgbMatrix { presentationTimeUs, _ ->
            val fade = Timeline.fadeAt(cuts, presentationTimeUs / 1000) ?: return@RgbMatrix identity
            val keep = 1f - fade.amount
            val add = if (fade.white) fade.amount else 0f
            floatArrayOf(keep, 0f, 0f, 0f, 0f, keep, 0f, 0f, 0f, 0f, keep, 0f, add, add, add, 1f)
        })
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    companion object {
        const val PREVIEW_SHORT_SIDE = 720
    }
}
