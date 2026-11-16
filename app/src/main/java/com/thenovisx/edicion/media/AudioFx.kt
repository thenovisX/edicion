package com.thenovisx.edicion.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Fundido de entrada y salida de un audio. Cuenta las muestras desde el inicio del fragmento,
 * así que en la exportación es exacto; en la vista previa, tras saltar a otro punto, el fundido
 * se aplica desde donde se reanuda.
 */
class FadeAudioProcessor(
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val durationUs: Long,
) : BaseAudioProcessor() {

    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        // Solo se procesa PCM de 16 bits (lo habitual); con otros formatos el audio pasa sin cambios.
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        val out = replaceOutputBuffer(remaining)
        while (inputBuffer.remaining() >= 2 * channels) {
            val g = gain(frames * 1_000_000L / rate)
            repeat(channels) { out.putShort((inputBuffer.short * g).toInt().toShort()) }
            frames++
        }
        // Si quedara un resto incompleto, se copia tal cual.
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    private fun gain(tUs: Long): Float {
        var g = 1f
        if (fadeInUs > 0 && tUs < fadeInUs) g *= tUs.toFloat() / fadeInUs
        if (fadeOutUs > 0 && tUs > durationUs - fadeOutUs) g *= ((durationUs - tUs).toFloat() / fadeOutUs).coerceIn(0f, 1f)
        return g
    }

    override fun onFlush() {
        frames = 0
    }

    override fun onReset() {
        frames = 0
    }
}
