package com.thenovisx.edicion.media

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/**
 * Archivo WAV de silencio que se usa para rellenar huecos en la pista de audio.
 * (El reproductor de vista previa de Media3 no admite huecos, así que los representamos
 * con silencio real recortado a la duración necesaria.)
 */
object Silence {
    const val DURATION_MS = 10 * 60 * 1000L
    private const val SAMPLE_RATE = 8_000
    private const val CHANNELS = 1
    private const val BITS = 16

    @Synchronized
    fun file(context: Context): File {
        val f = File(context.cacheDir, "silencio_v1.wav")
        val dataBytes = SAMPLE_RATE.toLong() * CHANNELS * (BITS / 8) * (DURATION_MS / 1000)
        if (f.exists() && f.length() == dataBytes + 44) return f
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header(dataBytes))
            // setLength rellena con ceros, que en PCM de 16 bits es silencio.
            raf.setLength(dataBytes + 44)
        }
        return f
    }

    private fun header(dataBytes: Long): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(CHANNELS.toShort())
        b.putInt(SAMPLE_RATE); b.putInt(byteRate); b.putShort((CHANNELS * BITS / 8).toShort()); b.putShort(BITS.toShort())
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        return b.array()
    }
}
