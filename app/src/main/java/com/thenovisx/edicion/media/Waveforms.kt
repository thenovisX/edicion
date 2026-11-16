package com.thenovisx.edicion.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** Forma de onda de un audio: el volumen máximo (0..1) de cada tramo de [BUCKET_MS]. */
object Waveforms {
    const val BUCKET_MS = 50L
    private const val MAX_BUCKETS = 40_000 // unos 33 minutos

    private val cache = ConcurrentHashMap<String, FloatArray>()
    private val mutex = Mutex()

    fun cached(uri: String): FloatArray? = cache[uri]

    /** Calcula (una sola vez por archivo) la forma de onda. Devuelve null si no se puede leer. */
    suspend fun load(context: Context, uri: String): FloatArray? {
        cache[uri]?.let { return it }
        // De uno en uno: decodificar varios audios a la vez dejaría el móvil sin decodificadores.
        return mutex.withLock {
            cache[uri] ?: withContext(Dispatchers.IO) {
                runCatching { decode(context, Uri.parse(uri)) }.getOrNull()
            }?.also { cache[uri] = it }
        }
    }

    private fun decode(context: Context, uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            val peaks = FloatArray(MAX_BUCKETS)
            var used = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone && idle < 200) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    idle = 0
                    val bucket = (info.presentationTimeUs / 1000 / BUCKET_MS).toInt()
                    if (info.size > 0 && bucket in 0 until MAX_BUCKETS) {
                        val samples = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        var peak = 0
                        // Basta con mirar una de cada ocho muestras para dibujar la onda.
                        var k = 0
                        val n = samples.remaining()
                        while (k < n) {
                            val v = samples.get(k).toInt()
                            val a = if (v < 0) -v else v
                            if (a > peak) peak = a
                            k += 8
                        }
                        val p = peak / 32768f
                        if (p > peaks[bucket]) peaks[bucket] = p
                        if (bucket + 1 > used) used = bucket + 1
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else {
                    idle++
                }
            }
            return if (used == 0) null else peaks.copyOf(used)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
