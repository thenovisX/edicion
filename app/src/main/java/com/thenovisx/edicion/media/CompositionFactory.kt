package com.thenovisx.edicion.media

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import com.thenovisx.edicion.core.AudioClip
import com.thenovisx.edicion.core.Clip
import com.thenovisx.edicion.core.ColorMath
import com.thenovisx.edicion.core.MediaKind
import com.thenovisx.edicion.core.Project
import com.thenovisx.edicion.core.TextLayer

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
        val sequences = mutableListOf(EditedMediaItemSequence(videoItems))

        audioSequence(project)?.let { sequences += it }

        val compositionEffects = textEffects(project.texts, h)

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
        val color = ColorMath.combined(clip.filter, clip.filterIntensity, clip.adjustments)
        if (!color.isIdentity) {
            val m = color.toColumnMajor4x4()
            video += RgbMatrix { _, _ -> m }
        }
        // Encaja el clip en el lienzo del proyecto (con bandas negras si la proporción no coincide).
        video += Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT)

        if (clip.kind == MediaKind.VIDEO && clip.effectiveVolume != 1f) {
            audio += volumeProcessor(clip.effectiveVolume)
        }

        return editedBuilder.setEffects(Effects(audio, video)).build()
    }

    // ---------- Audio ----------

    private fun audioSequence(project: Project): EditedMediaItemSequence? {
        val total = project.durationMs
        val clips = project.audio.sortedBy { it.startMs }.filter { it.startMs < total }
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
        val audio = if (a.volume != 1f) listOf<AudioProcessor>(volumeProcessor(a.volume)) else emptyList()
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
            for (channels in 1..2) {
                putChannelMixingMatrix(ChannelMixingMatrix.create(channels, channels).scaleBy(volume))
            }
        }

    // ---------- Texto ----------

    private fun textEffects(texts: List<TextLayer>, outH: Int): List<Effect> {
        if (texts.isEmpty()) return emptyList()
        val overlays = texts.map<TextLayer, TextureOverlay> { TimedTextOverlay(it, outH) }
        return listOf(OverlayEffect(ImmutableList.copyOf(overlays)))
    }

    private class TimedTextOverlay(private val layer: TextLayer, outH: Int) : TextOverlay() {
        private val text: SpannableString = SpannableString(layer.text.ifEmpty { " " }).apply {
            val end = length
            val px = (outH * 0.045f * layer.scale).toInt().coerceAtLeast(8)
            setSpan(AbsoluteSizeSpan(px), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(layer.colorArgb.toInt()), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (layer.backgroundArgb ushr 24 != 0L) {
                setSpan(BackgroundColorSpan(layer.backgroundArgb.toInt()), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (layer.bold) setSpan(StyleSpan(Typeface.BOLD), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        private val visible = OverlaySettings.Builder()
            // y positiva hacia arriba en coordenadas NDC; en el modelo y positiva es hacia abajo.
            .setBackgroundFrameAnchor(layer.x, -layer.y)
            .build()
        private val hidden = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(layer.x, -layer.y)
            .setAlphaScale(0f)
            .build()

        override fun getText(presentationTimeUs: Long): SpannableString = text

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
            if (layer.isVisibleAt(presentationTimeUs / 1000)) visible else hidden
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    companion object {
        const val PREVIEW_SHORT_SIDE = 720
    }
}
