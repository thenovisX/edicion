package com.thenovisx.edicion.core

/**
 * Operaciones puras sobre un [Project]. Cada función devuelve un proyecto nuevo y nunca
 * modifica el original, lo que permite deshacer/rehacer guardando instantáneas.
 */
object Timeline {

    /** Posición dentro de la pista principal: índice de clip y desplazamiento en la línea de tiempo. */
    data class ClipPosition(val index: Int, val clipStartMs: Long, val offsetMs: Long)

    /** Inicio de cada clip en la línea de tiempo. */
    fun clipStarts(project: Project): List<Long> {
        val starts = ArrayList<Long>(project.clips.size)
        var t = 0L
        for (c in project.clips) {
            starts += t
            t += c.durationMs
        }
        return starts
    }

    fun clipStartOf(project: Project, clipId: String): Long? {
        var t = 0L
        for (c in project.clips) {
            if (c.id == clipId) return t
            t += c.durationMs
        }
        return null
    }

    /** Clip que se reproduce en [timeMs]. Devuelve null si la pista está vacía. */
    fun clipAt(project: Project, timeMs: Long): ClipPosition? {
        if (project.clips.isEmpty()) return null
        var t = 0L
        project.clips.forEachIndexed { i, c ->
            val end = t + c.durationMs
            if (timeMs < end || i == project.clips.lastIndex) {
                return ClipPosition(i, t, (timeMs - t).coerceIn(0, c.durationMs))
            }
            t = end
        }
        return null
    }

    // ---------- Pista principal ----------

    fun addClips(project: Project, newClips: List<Clip>, atIndex: Int = project.clips.size): Project {
        val idx = atIndex.coerceIn(0, project.clips.size)
        val list = project.clips.toMutableList()
        list.addAll(idx, newClips)
        return project.copy(clips = list).clampTexts()
    }

    fun removeClip(project: Project, clipId: String): Project =
        project.copy(clips = project.clips.filterNot { it.id == clipId }).clampTexts()

    fun moveClip(project: Project, clipId: String, toIndex: Int): Project {
        val from = project.clips.indexOfFirst { it.id == clipId }
        if (from < 0) return project
        val list = project.clips.toMutableList()
        val clip = list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), clip)
        return project.copy(clips = list)
    }

    fun duplicateClip(project: Project, clipId: String, newId: String): Project {
        val idx = project.clips.indexOfFirst { it.id == clipId }
        if (idx < 0) return project
        val list = project.clips.toMutableList()
        list.add(idx + 1, list[idx].copy(id = newId))
        return project.copy(clips = list)
    }

    fun updateClip(project: Project, clipId: String, transform: (Clip) -> Clip): Project =
        project.copy(clips = project.clips.map { if (it.id == clipId) normalize(transform(it)) else it })
            .clampTexts()

    /**
     * Divide el clip que está en [timeMs] en dos. No hace nada si el corte queda demasiado
     * cerca de un borde. Devuelve el proyecto y el id de la segunda mitad.
     */
    fun splitAt(project: Project, timeMs: Long, newId: String): Pair<Project, String?> {
        val pos = clipAt(project, timeMs) ?: return project to null
        val clip = project.clips[pos.index]
        if (pos.offsetMs < MIN_CLIP_DURATION_MS || clip.durationMs - pos.offsetMs < MIN_CLIP_DURATION_MS) {
            return project to null
        }
        val sourceOffset = if (clip.kind == MediaKind.IMAGE) pos.offsetMs
        else (pos.offsetMs * clip.speed.toDouble()).toLong()
        val cut = clip.trimStartMs + sourceOffset
        val first = clip.copy(trimEndMs = cut, audioFadeOutMs = 0)
        val second = if (clip.kind == MediaKind.IMAGE) {
            clip.copy(id = newId, trimStartMs = 0, trimEndMs = clip.trimEndMs - cut, audioFadeInMs = 0)
        } else {
            clip.copy(id = newId, trimStartMs = cut, audioFadeInMs = 0)
        }
        val list = project.clips.toMutableList()
        list[pos.index] = first
        list.add(pos.index + 1, second)
        return project.copy(clips = list) to newId
    }

    /** Fija el recorte de un clip respetando los límites del archivo y la duración mínima. */
    fun trimClip(project: Project, clipId: String, startMs: Long, endMs: Long): Project =
        updateClip(project, clipId) { it.copy(trimStartMs = startMs, trimEndMs = endMs) }

    fun setImageDuration(project: Project, clipId: String, durationMs: Long): Project =
        updateClip(project, clipId) {
            if (it.kind != MediaKind.IMAGE) it else it.copy(trimStartMs = 0, trimEndMs = durationMs)
        }

    private fun normalize(c: Clip): Clip {
        val speed = c.speed.coerceIn(0.25f, 4f)
        val start = c.trimStartMs.coerceIn(0, (c.sourceDurationMs - MIN_CLIP_DURATION_MS).coerceAtLeast(0))
        val end = c.trimEndMs.coerceIn(start + MIN_CLIP_DURATION_MS, c.sourceDurationMs.coerceAtLeast(start + MIN_CLIP_DURATION_MS))
        return c.copy(
            speed = speed,
            trimStartMs = start,
            trimEndMs = end,
            volume = c.volume.coerceIn(0f, 2f),
            filterIntensity = c.filterIntensity.coerceIn(0f, 1f),
            rotation = ((c.rotation % 360) + 360) % 360,
            transitionMs = c.transitionMs.coerceIn(200, MAX_TRANSITION_MS),
            zoom = c.zoom.coerceIn(0.2f, 5f),
            panX = c.panX.coerceIn(-1f, 1f),
            panY = c.panY.coerceIn(-1f, 1f),
            audioFadeInMs = c.audioFadeInMs.coerceIn(0, MAX_AUDIO_FADE_MS),
            audioFadeOutMs = c.audioFadeOutMs.coerceIn(0, MAX_AUDIO_FADE_MS),
        )
    }

    // ---------- Audio ----------

    /** Añade un audio en [startMs] (por defecto al final de la pista de audio). */
    fun addAudio(project: Project, clip: AudioClip, startMs: Long? = null): Project {
        val start = startMs ?: (project.audio.filter { it.lane == clip.lane }.maxOfOrNull { it.endMs } ?: 0L)
        return project.copy(audio = resolveAudioOverlaps(project.audio + clip.copy(startMs = start), clip.id))
    }

    fun updateAudio(project: Project, id: String, transform: (AudioClip) -> AudioClip): Project {
        val updated = project.audio.map {
            if (it.id != id) it else {
                val a = transform(it)
                val start = a.trimStartMs.coerceIn(0, (a.sourceDurationMs - MIN_CLIP_DURATION_MS).coerceAtLeast(0))
                val end = a.trimEndMs.coerceIn(start + MIN_CLIP_DURATION_MS, a.sourceDurationMs.coerceAtLeast(start + MIN_CLIP_DURATION_MS))
                a.copy(
                    trimStartMs = start, trimEndMs = end, startMs = a.startMs.coerceAtLeast(0), volume = a.volume.coerceIn(0f, 2f),
                    fadeInMs = a.fadeInMs.coerceIn(0, MAX_AUDIO_FADE_MS), fadeOutMs = a.fadeOutMs.coerceIn(0, MAX_AUDIO_FADE_MS),
                    pitch = a.pitch.coerceIn(0.5f, 2f),
                )
            }
        }
        return project.copy(audio = resolveAudioOverlaps(updated, id))
    }

    fun removeAudio(project: Project, id: String): Project =
        project.copy(audio = project.audio.filterNot { it.id == id })

    fun splitAudioAt(project: Project, timeMs: Long, newId: String, preferId: String? = null): Pair<Project, String?> {
        val a = project.audio.sortedBy { if (it.id == preferId) 0 else 1 }.firstOrNull { timeMs > it.startMs + MIN_CLIP_DURATION_MS && timeMs < it.endMs - MIN_CLIP_DURATION_MS }
            ?: return project to null
        val cut = a.trimStartMs + (timeMs - a.startMs)
        val first = a.copy(trimEndMs = cut, fadeOutMs = 0)
        val second = a.copy(id = newId, trimStartMs = cut, startMs = timeMs, fadeInMs = 0)
        val list = project.audio.flatMap { if (it.id == a.id) listOf(first, second) else listOf(it) }
        return project.copy(audio = list) to newId
    }

    /**
     * La pista de audio es secuencial: los clips no se solapan. El clip [priorityId] conserva su
     * posición y los demás se desplazan hacia la derecha si hace falta.
     */
    fun resolveAudioOverlaps(list: List<AudioClip>, priorityId: String? = null): List<AudioClip> =
        list.groupBy { it.lane }.toSortedMap().values.flatMap { lane ->
            val sorted = lane.sortedWith(compareBy<AudioClip> { it.startMs }.thenBy { if (it.id == priorityId) 0 else 1 })
            val out = ArrayList<AudioClip>(sorted.size)
            var cursor = 0L
            for (a in sorted) {
                val placed = a.copy(startMs = maxOf(a.startMs, cursor))
                out += placed
                cursor = placed.endMs
            }
            out
        }

    // ---------- Marcadores y utilidades de clip ----------

    /** Pone un marcador en [timeMs], o lo quita si ya había uno cerca. */
    fun toggleMarker(project: Project, timeMs: Long, toleranceMs: Long = 150): Project {
        val near = project.markers.firstOrNull { kotlin.math.abs(it - timeMs) <= toleranceMs }
        return project.copy(markers = if (near != null) project.markers - near else (project.markers + timeMs).sorted())
    }

    /**
     * Separa el sonido de un clip de vídeo: lo silencia y coloca su audio en la pista de música,
     * en el mismo punto. Devuelve el proyecto y el id del audio nuevo (null si no se pudo).
     */
    fun extractAudio(project: Project, clipId: String, newId: String): Pair<Project, String?> {
        val i = project.clips.indexOfFirst { it.id == clipId }
        val c = project.clips.getOrNull(i) ?: return project to null
        if (c.kind != MediaKind.VIDEO || !c.hasAudio || c.muted || c.speed != 1f) return project to null
        val audio = AudioClip(
            id = newId, uri = c.uri, name = c.name, sourceDurationMs = c.sourceDurationMs,
            trimStartMs = c.trimStartMs, trimEndMs = c.trimEndMs, volume = c.volume,
            fadeInMs = c.audioFadeInMs, fadeOutMs = c.audioFadeOutMs,
        )
        val muted = updateClip(project, clipId) { it.copy(muted = true) }
        return addAudio(muted, audio, clipStarts(project)[i]) to newId
    }

    /** Posición en el archivo original que se ve en [timeMs], si hay un clip de vídeo ahí. */
    fun sourcePositionAt(project: Project, timeMs: Long): Pair<Clip, Long>? {
        val pos = clipAt(project, timeMs) ?: return null
        val c = project.clips[pos.index]
        if (c.kind != MediaKind.VIDEO) return null
        return c to (c.trimStartMs + ((timeMs - clipStarts(project)[pos.index]) * c.speed).toLong()).coerceIn(c.trimStartMs, c.trimEndMs)
    }

    // ---------- Transiciones ----------

    /** Fundido activo en un instante: color (negro o blanco) y cantidad 0..1. */
    data class Fade(val white: Boolean, val amount: Float)

    /**
     * Calcula el fundido de transición en [timeMs]. La transición de un clip se reparte a
     * partes iguales entre el final del clip anterior y el inicio del propio clip; en el
     * primer clip solo hay fundido de entrada.
     */
    fun fadeAt(project: Project, timeMs: Long): Fade? = fadeAt(transitionCuts(project), timeMs)

    data class Cut(val timeMs: Long, val beforeMs: Long, val afterMs: Long, val white: Boolean, val type: TransitionType = TransitionType.FADE_BLACK)

    fun transitionCuts(project: Project): List<Cut> {
        val cuts = ArrayList<Cut>()
        var t = 0L
        project.clips.forEachIndexed { i, c ->
            if (c.transition != TransitionType.NONE) {
                val half = c.transitionMs / 2
                val prev = project.clips.getOrNull(i - 1)
                // Ningún fundido ocupa más de la mitad de un clip.
                val before = if (prev == null) 0L else minOf(half, prev.durationMs / 2)
                val after = minOf(if (prev == null) c.transitionMs else half, c.durationMs / 2)
                cuts += Cut(t, before, after, c.transition.white, c.transition)
            }
            t += c.durationMs
        }
        return cuts
    }

    /**
     * Estado de una transición en un instante: [amount] va de 0 (lejos del corte) a 1 (en el
     * corte) e [incoming] indica si ya se ve el clip que entra.
     */
    data class TransitionState(val type: TransitionType, val amount: Float, val incoming: Boolean)

    fun transitionAt(cuts: List<Cut>, timeMs: Long): TransitionState? {
        for (cut in cuts) {
            val d = timeMs - cut.timeMs
            if (d < 0 && -d < cut.beforeMs) return TransitionState(cut.type, 1f - (-d).toFloat() / cut.beforeMs, false)
            if (d >= 0 && d < cut.afterMs) return TransitionState(cut.type, 1f - d.toFloat() / cut.afterMs, true)
        }
        return null
    }

    fun fadeAt(cuts: List<Cut>, timeMs: Long): Fade? {
        val st = transitionAt(cuts, timeMs) ?: return null
        val amount = when (st.type) {
            TransitionType.FADE_BLACK, TransitionType.FADE_WHITE -> st.amount
            // El destello es un fundido a blanco muy rápido cerca del corte.
            TransitionType.FLASH -> st.amount * st.amount
            // Las transiciones de movimiento se oscurecen un poco para suavizar el corte.
            TransitionType.ZOOM_IN, TransitionType.ZOOM_OUT, TransitionType.SPIN -> st.amount * st.amount * 0.85f
            else -> 0f
        }
        return if (amount <= 0f) null else Fade(st.type.white, amount)
    }

    /** Movimiento del fotograma completo: escala, desplazamiento (-2..2 = un lienzo entero) y giro. */
    data class Motion(val scale: Float = 1f, val dx: Float = 0f, val dy: Float = 0f, val rotation: Float = 0f) {
        val isIdentity: Boolean get() = scale == 1f && dx == 0f && dy == 0f && rotation == 0f
    }

    /** Movimiento de la transición activa en [timeMs], o null si no hay ninguno. */
    fun transitionMotion(cuts: List<Cut>, timeMs: Long): Motion? {
        val st = transitionAt(cuts, timeMs) ?: return null
        val e = st.amount * st.amount
        // El clip que sale se va en un sentido y el que entra llega desde el contrario.
        val side = if (st.incoming) -1f else 1f
        return when (st.type) {
            TransitionType.ZOOM_IN -> Motion(scale = if (st.incoming) 1f - 0.4f * e else 1f + 0.8f * e)
            TransitionType.ZOOM_OUT -> Motion(scale = if (st.incoming) 1f + 0.8f * e else 1f - 0.4f * e)
            TransitionType.SLIDE_LEFT -> Motion(dx = -2f * e * side)
            TransitionType.SLIDE_RIGHT -> Motion(dx = 2f * e * side)
            TransitionType.SLIDE_UP -> Motion(dy = -2f * e * side)
            TransitionType.SLIDE_DOWN -> Motion(dy = 2f * e * side)
            TransitionType.SPIN -> Motion(scale = 1f + 0.5f * e, rotation = 120f * e * side)
            else -> null
        }
    }

    /**
     * Movimiento y destello de un efecto de clip. [tMs] es el tiempo dentro del clip y
     * [durationMs] su duración. El segundo valor es la luz añadida (0..1).
     */
    fun clipEffectAt(effect: ClipEffect, tMs: Long, durationMs: Long): Pair<Motion, Float> {
        val p = if (durationMs <= 0) 0f else (tMs.toFloat() / durationMs).coerceIn(0f, 1f)
        val t = tMs / 1000.0
        fun sin(hz: Double, phase: Double = 0.0) = kotlin.math.sin(2 * Math.PI * hz * t + phase).toFloat()
        return when (effect) {
            ClipEffect.NONE -> Motion() to 0f
            ClipEffect.ZOOM_IN_SLOW -> Motion(scale = 1f + 0.25f * p) to 0f
            ClipEffect.ZOOM_OUT_SLOW -> Motion(scale = 1.25f - 0.25f * p) to 0f
            ClipEffect.PAN_LEFT -> Motion(scale = 1.2f, dx = 0.2f - 0.4f * p) to 0f
            ClipEffect.PAN_RIGHT -> Motion(scale = 1.2f, dx = -0.2f + 0.4f * p) to 0f
            ClipEffect.SHAKE -> Motion(scale = 1.08f, dx = 0.035f * sin(7.0) + 0.02f * sin(13.0, 1.0), dy = 0.035f * sin(9.0, 2.0) + 0.02f * sin(15.0, 0.5)) to 0f
            ClipEffect.PULSE -> {
                // Un golpe de zoom dos veces por segundo que se relaja enseguida.
                val beat = (t * 2) % 1.0
                Motion(scale = 1f + 0.08f * kotlin.math.exp(-6.0 * beat).toFloat()) to 0f
            }
            ClipEffect.SWAY -> Motion(scale = 1.12f, rotation = 3f * sin(0.5)) to 0f
            ClipEffect.STROBE -> Motion() to (if ((tMs / 80) % 4 == 0L) 0.55f else 0f)
            ClipEffect.GLITCH -> {
                // Saltos bruscos e irregulares, repetibles para que la exportación coincida con la vista previa.
                val step = tMs / 90
                val h = ((step * 2654435761L) ushr 8) % 1000 / 1000f
                if (h > 0.25f) Motion(scale = 1.06f) to 0f
                else Motion(scale = 1.06f, dx = (h - 0.125f) * 0.6f, dy = (0.125f - h) * 0.2f) to (if (h < 0.08f) 0.25f else 0f)
            }
        }
    }

    // ---------- Texto ----------

    fun addText(project: Project, layer: TextLayer): Project =
        project.copy(texts = project.texts + layer).clampTexts()

    fun updateText(project: Project, id: String, transform: (TextLayer) -> TextLayer): Project =
        project.copy(texts = project.texts.map { if (it.id == id) transform(it) else it }).clampTexts()

    /**
     * Cambia posición, tamaño, giro u opacidad de una capa en el instante [timeMs]. Si la capa
     * tiene fotogramas clave se crea o actualiza el del cabezal; si no, cambian sus valores fijos.
     */
    fun setLayerPose(project: Project, id: String, timeMs: Long, transform: (Keyframe) -> Keyframe): Project =
        updateText(project, id) { l ->
            if (l.keyframes.isEmpty()) {
                val k = clampPose(transform(l.basePose))
                l.copy(x = k.x, y = k.y, scale = k.scale, rotation = k.rotation, opacity = k.opacity)
            } else {
                val rel = (timeMs - l.startMs).coerceIn(0, l.durationMs)
                val k = clampPose(transform(LayerMath.poseAt(l, timeMs))).copy(tMs = rel)
                val near = LayerMath.keyframeNear(l, timeMs)
                l.copy(keyframes = (l.keyframes.filterNot { it === near } + k.copy(tMs = near?.tMs ?: rel)).sortedBy { it.tMs })
            }
        }

    /** Añade un fotograma clave en [timeMs] con el estado actual, o lo quita si ya había uno. */
    fun toggleKeyframe(project: Project, id: String, timeMs: Long): Project =
        updateText(project, id) { l ->
            val near = LayerMath.keyframeNear(l, timeMs)
            if (near != null) {
                val rest = l.keyframes.filterNot { it === near }
                // Al quitar el último, la capa se queda fija donde estaba.
                if (rest.isEmpty()) l.copy(keyframes = rest, x = near.x, y = near.y, scale = near.scale, rotation = near.rotation, opacity = near.opacity)
                else l.copy(keyframes = rest)
            } else {
                val rel = (timeMs - l.startMs).coerceIn(0, l.durationMs)
                l.copy(keyframes = (l.keyframes + LayerMath.poseAt(l, timeMs).copy(tMs = rel)).sortedBy { it.tMs })
            }
        }

    private fun clampPose(k: Keyframe) = k.copy(
        x = k.x.coerceIn(-1f, 1f),
        y = k.y.coerceIn(-1f, 1f),
        scale = k.scale.coerceIn(0.05f, 8f),
        opacity = k.opacity.coerceIn(0f, 1f),
    )

    /** Divide una capa en dos en [timeMs], repartiendo sus fotogramas clave. */
    fun splitLayerAt(project: Project, id: String, timeMs: Long, newId: String): Project {
        val l = project.texts.firstOrNull { it.id == id } ?: return project
        if (timeMs <= l.startMs || timeMs >= l.endMs) return project
        val rel = timeMs - l.startMs
        val cut = LayerMath.poseAt(l, timeMs)
        val hasKeys = l.keyframes.isNotEmpty()
        val first = l.copy(
            endMs = timeMs,
            keyframes = if (hasKeys) l.keyframes.filter { it.tMs < rel } + cut.copy(tMs = rel) else emptyList(),
        )
        val second = l.copy(
            id = newId,
            startMs = timeMs,
            trimStartMs = if (l.isVideo) l.trimStartMs + rel else l.trimStartMs,
            keyframes = if (hasKeys) listOf(cut.copy(tMs = 0)) + l.keyframes.filter { it.tMs > rel }.map { it.copy(tMs = it.tMs - rel) } else emptyList(),
        )
        return project.copy(texts = project.texts.flatMap { if (it.id == id) listOf(first, second) else listOf(it) }).clampTexts()
    }

    fun removeText(project: Project, id: String): Project =
        project.copy(texts = project.texts.filterNot { it.id == id })

    /** Cambia el orden de apilado: al frente del todo o al fondo de las capas. */
    fun moveLayerZ(project: Project, id: String, toFront: Boolean): Project {
        val layer = project.texts.firstOrNull { it.id == id } ?: return project
        val rest = project.texts.filterNot { it.id == id }
        return project.copy(texts = if (toFront) rest + layer else listOf(layer) + rest)
    }

    /**
     * Reparte las capas en filas de la línea de tiempo de forma que no se solapen.
     * Devuelve la fila de cada capa (en el mismo orden que [layers]); si hacen falta más de
     * [maxRows] filas, las sobrantes comparten la última.
     */
    fun layerRows(layers: List<TextLayer>, maxRows: Int): List<Int> {
        val rowEnds = ArrayList<Long>()
        val result = IntArray(layers.size)
        for (i in layers.indices.sortedBy { layers[it].startMs }) {
            val l = layers[i]
            var row = rowEnds.indexOfFirst { it <= l.startMs }
            if (row < 0) {
                if (rowEnds.size < maxRows) {
                    rowEnds += 0L
                    row = rowEnds.lastIndex
                } else {
                    row = maxRows - 1
                }
            }
            rowEnds[row] = maxOf(rowEnds[row], l.endMs)
            result[i] = row
        }
        return result.toList()
    }

    /** Mantiene las capas dentro de la duración del vídeo y con una duración mínima. */
    private fun Project.clampTexts(): Project {
        val total = durationMs
        if (total <= 0) return this
        return copy(texts = texts.map { t ->
            val start = t.startMs.coerceIn(0, (total - MIN_CLIP_DURATION_MS).coerceAtLeast(0))
            var end = t.endMs.coerceIn(start + MIN_CLIP_DURATION_MS, maxOf(total, start + MIN_CLIP_DURATION_MS))
            var trim = t.trimStartMs
            if (t.isVideo) {
                // Un vídeo no puede durar más que el material que queda desde su punto de inicio.
                trim = trim.coerceIn(0, (t.sourceDurationMs - MIN_CLIP_DURATION_MS).coerceAtLeast(0))
                end = minOf(end, start + (t.sourceDurationMs - trim).coerceAtLeast(MIN_CLIP_DURATION_MS))
            }
            t.copy(
                startMs = start,
                endMs = end,
                trimStartMs = trim,
                x = t.x.coerceIn(-1f, 1f),
                y = t.y.coerceIn(-1f, 1f),
                scale = t.scale.coerceIn(0.1f, 6f),
                opacity = t.opacity.coerceIn(0f, 1f),
                volume = t.volume.coerceIn(0f, 2f),
                rotation = ((t.rotation + 180f) % 360f + 360f) % 360f - 180f,
            )
        })
    }
}
