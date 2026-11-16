package com.thenovisx.edicion.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.thenovisx.edicion.core.Project
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Opciones de exportación de vídeo. */
data class ExportOptions(val shortSide: Int = 1080)

/** Exporta un proyecto a MP4 (H.264 + AAC) y lo guarda en la galería. */
class VideoExporter(private val context: Context) {

    /**
     * Debe llamarse desde el hilo principal. Devuelve la Uri del vídeo guardado en la galería.
     * Si la corrutina se cancela, la exportación se detiene.
     */
    suspend fun export(project: Project, options: ExportOptions, onProgress: (Float) -> Unit): Uri {
        val composition = CompositionFactory(context).build(project, options.shortSide, forPreview = false)
            ?: error("El proyecto no tiene clips")
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "export_${System.currentTimeMillis()}.mp4")

        run(composition, out, onProgress)
        onProgress(1f)
        return withContext(Dispatchers.IO) {
            Gallery.saveVideo(context, out, "${safeName(project.name)}_${System.currentTimeMillis()}.mp4")
                .also { out.delete() }
        }
    }

    private suspend fun run(composition: Composition, out: File, onProgress: (Float) -> Unit) = coroutineScope {
        var transformer: Transformer? = null
        val poll = launch {
            val holder = ProgressHolder()
            while (isActive) {
                val t = transformer
                if (t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100f)
                }
                delay(200)
            }
        }
        try {
            suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
                val t = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer = t
                cont.invokeOnCancellation { t.cancel() }
                t.start(composition, out.absolutePath)
            }
        } finally {
            poll.cancel()
        }
    }

    private fun safeName(name: String) =
        name.replace(Regex("[^\\p{L}\\p{N}_ -]"), "").trim().ifEmpty { "video" }.take(40)
}
