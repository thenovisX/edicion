package com.thenovisx.edicion.media

import android.content.Context
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer
import com.thenovisx.edicion.core.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reproductor de vista previa del proyecto basado en CompositionPlayer de Media3.
 * CompositionPlayer solo acepta una composición, así que se crea uno nuevo en cada cambio.
 * Debe usarse desde el hilo principal.
 */
class PreviewEngine(private val context: Context) {
    private val factory = CompositionFactory(context)

    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var onEnded: (() -> Unit)? = null

    fun setOnEnded(block: () -> Unit) {
        onEnded = block
    }

    /** Reconstruye el reproductor para [project] y lo coloca en [positionMs]. */
    fun load(project: Project, positionMs: Long) {
        val wasPlaying = _isPlaying.value
        releasePlayer()
        val composition = runCatching {
            factory.build(project, CompositionFactory.PREVIEW_SHORT_SIDE, forPreview = true)
        }.onFailure { Log.e(TAG, "No se pudo crear la composición", it) }.getOrNull() ?: return

        val p = CompositionPlayer.Builder(context).build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _isPlaying.value = false
                    onEnded?.invoke()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Error de reproducción", error)
                _error.value = error.message ?: "Error de reproducción"
            }
        })
        try {
            p.setComposition(composition)
            p.prepare()
            p.seekTo(positionMs.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0)))
            p.playWhenReady = wasPlaying
            _error.value = null
            _player.value = p
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo preparar la vista previa", e)
            _error.value = e.message
            runCatching { p.release() }
        }
    }

    val positionMs: Long get() = _player.value?.currentPosition ?: 0L

    fun play() {
        _player.value?.let {
            if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
            it.play()
        }
    }

    fun pause() {
        _player.value?.pause()
    }

    fun seekTo(ms: Long) {
        _player.value?.seekTo(ms.coerceAtLeast(0))
    }

    fun clear() {
        releasePlayer()
    }

    fun release() {
        releasePlayer()
        onEnded = null
    }

    private fun releasePlayer() {
        val old = _player.value ?: return
        _player.value = null
        _isPlaying.value = false
        runCatching { old.release() }
    }

    companion object {
        private const val TAG = "PreviewEngine"
    }
}
