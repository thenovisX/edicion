package com.thenovisx.edicion.core

/** Estado de una capa en un instante: opacidad final, desplazamiento (normalizado) y escala extra. */
/**
 * [x], [y] y [rotation] son los valores finales (fotogramas clave + animación); [scale] es el
 * factor respecto al tamaño base de la capa ([TextLayer.scale]).
 */
data class LayerState(
    val alpha: Float,
    val dx: Float,
    val dy: Float,
    val scale: Float,
    val x: Float = 0f,
    val y: Float = 0f,
    val rotation: Float = 0f,
)

/**
 * Cálculo de animaciones de capa. Lo comparten la vista previa y la exportación para que
 * se vean igual.
 */
object LayerMath {

    /** Posición, tamaño, giro y opacidad de la capa en un instante, interpolando sus fotogramas clave. */
    fun poseAt(layer: TextLayer, timeMs: Long): Keyframe {
        val ks = layer.keyframes
        if (ks.isEmpty()) return layer.basePose
        val t = timeMs - layer.startMs
        if (t <= ks.first().tMs) return ks.first()
        if (t >= ks.last().tMs) return ks.last()
        val i = ks.indexOfLast { it.tMs <= t }
        val a = ks[i]
        val b = ks[i + 1]
        val span = (b.tMs - a.tMs).coerceAtLeast(1)
        // Suavizado de entrada y salida para que el movimiento no sea brusco.
        val p = (t - a.tMs).toFloat() / span
        val e = p * p * (3 - 2 * p)
        fun mix(u: Float, v: Float) = u + (v - u) * e
        return Keyframe(t, mix(a.x, b.x), mix(a.y, b.y), mix(a.scale, b.scale), mix(a.rotation, b.rotation), mix(a.opacity, b.opacity))
    }

    /** Fotograma clave situado en [timeMs] (con un margen), si lo hay. */
    fun keyframeNear(layer: TextLayer, timeMs: Long, toleranceMs: Long = KEYFRAME_SNAP_MS): Keyframe? {
        val t = timeMs - layer.startMs
        return layer.keyframes.minByOrNull { kotlin.math.abs(it.tMs - t) }?.takeIf { kotlin.math.abs(it.tMs - t) <= toleranceMs }
    }

    fun stateAt(layer: TextLayer, timeMs: Long): LayerState {
        val pose = poseAt(layer, timeMs)
        if (!layer.isVisibleAt(timeMs)) return LayerState(0f, 0f, 0f, 1f, pose.x, pose.y, pose.rotation)
        val animMs = minOf(LAYER_ANIM_MS, layer.durationMs / 2).coerceAtLeast(1)
        var alpha = pose.opacity
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
        val ratio = pose.scale / layer.scale.coerceAtLeast(0.01f)
        return LayerState(alpha.coerceIn(0f, 1f), dx, dy, scale * ratio, pose.x + dx, pose.y + dy, pose.rotation)
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
