package com.thenovisx.edicion.core

/** Estado de una capa en un instante: opacidad final, desplazamiento (normalizado) y escala extra. */
data class LayerState(val alpha: Float, val dx: Float, val dy: Float, val scale: Float)

/**
 * Cálculo de animaciones de capa. Lo comparten la vista previa y la exportación para que
 * se vean igual.
 */
object LayerMath {

    fun stateAt(layer: TextLayer, timeMs: Long): LayerState {
        if (!layer.isVisibleAt(timeMs)) return LayerState(0f, 0f, 0f, 1f)
        val animMs = minOf(LAYER_ANIM_MS, layer.durationMs / 2).coerceAtLeast(1)
        var alpha = layer.opacity
        var dx = 0f
        var dy = 0f
        var scale = 1f
        // p va de 0 (inicio de la animación) a 1 (capa en reposo).
        val pIn = ((timeMs - layer.startMs).toFloat() / animMs).coerceIn(0f, 1f)
        val pOut = ((layer.endMs - timeMs).toFloat() / animMs).coerceIn(0f, 1f)
        // dir = 1 en la entrada; en la salida el movimiento continúa en el mismo sentido.
        for ((anim, p, dir) in listOf(Triple(layer.animIn, pIn, 1f), Triple(layer.animOut, pOut, -1f))) {
            if (p >= 1f) continue
            val e = 1f - (1f - p) * (1f - p)
            val d = (1 - e) * 0.25f * dir
            when (anim) {
                LayerAnim.FADE -> alpha *= p
                LayerAnim.SLIDE_UP -> { dy += d; alpha *= p }
                LayerAnim.SLIDE_DOWN -> { dy -= d; alpha *= p }
                LayerAnim.SLIDE_LEFT -> { dx += d; alpha *= p }
                LayerAnim.SLIDE_RIGHT -> { dx -= d; alpha *= p }
                LayerAnim.POP -> { scale *= 0.4f + 0.6f * e; alpha *= p }
                LayerAnim.TYPEWRITER, LayerAnim.NONE -> Unit
            }
        }
        return LayerState(alpha.coerceIn(0f, 1f), dx, dy, scale)
    }

    /**
     * Número de caracteres visibles con la animación de máquina de escribir, o null si el
     * texto ya se muestra entero (o la capa no usa esa animación).
     */
    fun typewriterChars(layer: TextLayer, timeMs: Long): Int? {
        if (layer.isMedia || layer.animIn != LayerAnim.TYPEWRITER) return null
        val total = layer.text.length
        // La escritura ocupa como mucho la primera mitad de la duración del texto.
        val typeMs = minOf(total * 60L, layer.durationMs / 2).coerceAtLeast(1)
        val chars = (((timeMs - layer.startMs).toFloat() / typeMs) * total).toInt().coerceIn(0, total)
        return if (chars < total) chars else null
    }

    /** Posición en el archivo de una capa de vídeo para un instante de la línea de tiempo. */
    fun videoPositionMs(layer: TextLayer, timeMs: Long): Long =
        (timeMs - layer.startMs + layer.trimStartMs).coerceIn(0, (layer.sourceDurationMs - 1).coerceAtLeast(0))
}
