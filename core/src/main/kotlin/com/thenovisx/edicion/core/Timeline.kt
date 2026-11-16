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
        val first = clip.copy(trimEndMs = cut)
        val second = if (clip.kind == MediaKind.IMAGE) {
            clip.copy(id = newId, trimStartMs = 0, trimEndMs = clip.trimEndMs - cut)
        } else {
            clip.copy(id = newId, trimStartMs = cut)
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
                a.copy(trimStartMs = start, trimEndMs = end, startMs = a.startMs.coerceAtLeast(0), volume = a.volume.coerceIn(0f, 2f))
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
        val first = a.copy(trimEndMs = cut)
        val second = a.copy(id = newId, trimStartMs = cut, startMs = timeMs)
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

    // ---------- Transiciones ----------

    /** Fundido activo en un instante: color (negro o blanco) y cantidad 0..1. */
    data class Fade(val white: Boolean, val amount: Float)

    /**
     * Calcula el fundido de transición en [timeMs]. La transición de un clip se reparte a
     * partes iguales entre el final del clip anterior y el inicio del propio clip; en el
     * primer clip solo hay fundido de entrada.
     */
    fun fadeAt(project: Project, timeMs: Long): Fade? = fadeAt(transitionCuts(project), timeMs)

    data class Cut(val timeMs: Long, val beforeMs: Long, val afterMs: Long, val white: Boolean)

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
                cuts += Cut(t, before, after, c.transition == TransitionType.FADE_WHITE)
            }
            t += c.durationMs
        }
        return cuts
    }

    fun fadeAt(cuts: List<Cut>, timeMs: Long): Fade? {
        for (cut in cuts) {
            val d = timeMs - cut.timeMs
            if (d < 0 && -d < cut.beforeMs) return Fade(cut.white, 1f - (-d).toFloat() / cut.beforeMs)
            if (d >= 0 && d < cut.afterMs) return Fade(cut.white, 1f - d.toFloat() / cut.afterMs)
        }
        return null
    }

    // ---------- Texto ----------

    fun addText(project: Project, layer: TextLayer): Project =
        project.copy(texts = project.texts + layer).clampTexts()

    fun updateText(project: Project, id: String, transform: (TextLayer) -> TextLayer): Project =
        project.copy(texts = project.texts.map { if (it.id == id) transform(it) else it }).clampTexts()

    fun removeText(project: Project, id: String): Project =
        project.copy(texts = project.texts.filterNot { it.id == id })

    /** Mantiene los textos dentro de la duración del vídeo y con una duración mínima. */
    private fun Project.clampTexts(): Project {
        val total = durationMs
        if (total <= 0) return this
        return copy(texts = texts.map { t ->
            val start = t.startMs.coerceIn(0, (total - MIN_CLIP_DURATION_MS).coerceAtLeast(0))
            val end = t.endMs.coerceIn(start + MIN_CLIP_DURATION_MS, maxOf(total, start + MIN_CLIP_DURATION_MS))
            t.copy(
                startMs = start,
                endMs = end,
                x = t.x.coerceIn(-1f, 1f),
                y = t.y.coerceIn(-1f, 1f),
                scale = t.scale.coerceIn(0.2f, 5f),
                opacity = t.opacity.coerceIn(0f, 1f),
            )
        })
    }
}
