package com.thenovisx.edicion.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.thenovisx.edicion.core.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Genera y guarda en memoria miniaturas pequeñas para la línea de tiempo y la lista de proyectos. */
object Thumbnails {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(context: Context, uri: String, kind: MediaKind, timeMs: Long = 0, sizePx: Int = 160): Bitmap? {
        val key = "$uri@$timeMs@$sizePx"
        cache.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                if (kind == MediaKind.IMAGE) loadImage(context, Uri.parse(uri), sizePx)
                else loadFrame(context, Uri.parse(uri), timeMs, sizePx)
            }.getOrNull()
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    private fun loadImage(context: Context, uri: Uri, sizePx: Int): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val s = info.size
            val scale = sizePx.toFloat() / maxOf(1, minOf(s.width, s.height))
            if (scale < 1f) decoder.setTargetSize(maxOf(1, (s.width * scale).toInt()), maxOf(1, (s.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun loadFrame(context: Context, uri: Uri, timeMs: Long, sizePx: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx * 2, sizePx * 2)
            } else {
                r.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            return frame
        } finally {
            runCatching { r.release() }
        }
    }

}
