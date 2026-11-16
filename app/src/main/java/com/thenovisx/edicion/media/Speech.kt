package com.thenovisx.edicion.media

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/** Texto a voz con el motor del propio teléfono: genera un archivo de audio a partir de un texto. */
object Speech {

    /** Devuelve el archivo WAV generado, o null si el teléfono no tiene motor de voz o falla. */
    suspend fun synthesize(context: Context, text: String): File? = suspendCancellableCoroutine { cont ->
        val dir = File(context.filesDir, "voz").apply { mkdirs() }
        val out = File(dir, "tts_${System.currentTimeMillis()}.wav")
        var tts: TextToSpeech? = null
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            runCatching { tts?.shutdown() }
            if (cont.isActive) cont.resume(if (ok && out.length() > 44) out else null.also { out.delete() })
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                finish(false)
                return@TextToSpeech
            }
            runCatching { engine.language = Locale.getDefault() }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finish(true)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(false)
                override fun onError(utteranceId: String?, errorCode: Int) = finish(false)
            })
            val result = engine.synthesizeToFile(text, null, out, "edicion_tts")
            if (result != TextToSpeech.SUCCESS) finish(false)
        }
        cont.invokeOnCancellation { runCatching { tts?.shutdown() } }
    }
}
