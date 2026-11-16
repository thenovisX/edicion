package com.thenovisx.edicion.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.thenovisx.edicion.core.AudioClip
import com.thenovisx.edicion.core.Clip
import com.thenovisx.edicion.core.DEFAULT_IMAGE_DURATION_MS
import com.thenovisx.edicion.core.MAX_IMAGE_DURATION_MS
import com.thenovisx.edicion.core.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Lee la información de los archivos elegidos y los convierte en clips del proyecto. */
class MediaImporter(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun importVisual(uris: List<Uri>): List<Clip> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            persist(uri)
            val mime = resolver.getType(uri)
            val name = displayName(uri)
            if (mime?.startsWith("image/") == true) {
                val (w, h) = imageSize(uri)
                Clip(
                    id = newId(),
                    uri = uri.toString(),
                    kind = MediaKind.IMAGE,
                    mimeType = mime,
                    name = name,
                    sourceDurationMs = MAX_IMAGE_DURATION_MS,
                    trimEndMs = DEFAULT_IMAGE_DURATION_MS,
                    width = w,
                    height = h,
                )
            } else {
                val info = probe(uri) ?: return@mapNotNull null
                Clip(
                    id = newId(),
                    uri = uri.toString(),
                    kind = MediaKind.VIDEO,
                    mimeType = mime,
                    name = name,
                    sourceDurationMs = info.durationMs,
                    width = info.width,
                    height = info.height,
                    rotation = 0,
                    hasAudio = info.hasAudio,
                )
            }
        }
    }

    suspend fun importAudio(uri: Uri): AudioClip? = withContext(Dispatchers.IO) {
        persist(uri)
        val info = probe(uri) ?: return@withContext null
        AudioClip(
            id = newId(),
            uri = uri.toString(),
            name = displayName(uri),
            sourceDurationMs = info.durationMs,
        )
    }

    /** Convierte una grabación de voz en un clip de la pista de voz. */
    suspend fun importVoice(file: java.io.File, name: String): AudioClip? = withContext(Dispatchers.IO) {
        val uri = Uri.fromFile(file)
        val info = probe(uri) ?: return@withContext null
        AudioClip(
            id = newId(),
            uri = uri.toString(),
            name = name,
            sourceDurationMs = info.durationMs,
            lane = com.thenovisx.edicion.core.LANE_VOICE,
        )
    }

    /** Datos de un archivo que se va a usar como capa superpuesta. */
    data class LayerMedia(
        val uri: String,
        val name: String,
        val isVideo: Boolean,
        val aspect: Float,
        val durationMs: Long,
        val hasAudio: Boolean,
    )

    /** Prepara una imagen o un vídeo para usarlo como capa. Devuelve null si no es legible. */
    suspend fun prepareLayerMedia(uri: Uri): LayerMedia? = withContext(Dispatchers.IO) {
        persist(uri)
        val name = displayName(uri)
        if (resolver.getType(uri)?.startsWith("image/") == true) {
            val (w, h) = imageSize(uri)
            if (w <= 0 || h <= 0) null
            else LayerMedia(uri.toString(), name.ifEmpty { "Imagen" }, false, w.toFloat() / h, 0, false)
        } else {
            val info = probe(uri) ?: return@withContext null
            if (info.width <= 0 || info.height <= 0) null
            else LayerMedia(
                uri.toString(), name.ifEmpty { "Vídeo" }, true,
                info.width.toFloat() / info.height, info.durationMs, info.hasAudio,
            )
        }
    }

    /** Cuenta los archivos de [uris] que ya no se pueden abrir (borrados o sin permiso). */
    suspend fun countMissing(uris: List<String>): Int = withContext(Dispatchers.IO) {
        uris.distinct().count { u ->
            runCatching { resolver.openAssetFileDescriptor(Uri.parse(u), "r")?.use { } ?: error("nulo") }.isFailure
        }
    }

    /** Conserva el permiso de lectura para poder abrir el proyecto más tarde. */
    private fun persist(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private data class Info(val durationMs: Long, val width: Int, val height: Int, val hasAudio: Boolean)

    private fun probe(uri: Uri): Info? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (d <= 0) null else Info(d, w, h, audio)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun imageSize(uri: Uri): Pair<Int, Int> = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        opts.outWidth to opts.outHeight
    }.getOrDefault(0 to 0)

    private fun displayName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
