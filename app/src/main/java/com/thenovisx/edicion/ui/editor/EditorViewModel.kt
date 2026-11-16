package com.thenovisx.edicion.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.thenovisx.edicion.core.AspectRatio
import com.thenovisx.edicion.core.AudioClip
import com.thenovisx.edicion.core.Clip
import com.thenovisx.edicion.core.History
import com.thenovisx.edicion.core.LANE_VOICE
import com.thenovisx.edicion.core.LayerAnim
import com.thenovisx.edicion.core.Project
import com.thenovisx.edicion.core.TextLayer
import com.thenovisx.edicion.core.Timeline
import com.thenovisx.edicion.data.ProjectRepository
import com.thenovisx.edicion.media.ExportOptions
import com.thenovisx.edicion.core.MIN_CLIP_DURATION_MS
import com.thenovisx.edicion.core.MediaKind
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.thenovisx.edicion.media.Speech
import com.thenovisx.edicion.core.Cue
import com.thenovisx.edicion.core.Subtitles
import com.thenovisx.edicion.core.Keyframe
import com.thenovisx.edicion.media.MediaImporter
import com.thenovisx.edicion.media.PreviewEngine
import com.thenovisx.edicion.media.VideoExporter
import com.thenovisx.edicion.media.VoiceRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Elemento seleccionado en la línea de tiempo. */
sealed interface Selection {
    data object None : Selection
    data class VideoClip(val id: String) : Selection
    data class Audio(val id: String) : Selection
    data class Text(val id: String) : Selection
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float) : ExportState
    data class Done(val uri: Uri, val audioOnly: Boolean = false) : ExportState
    data class Failed(val message: String) : ExportState
}

data class EditorUiState(
    val project: Project? = null,
    val playheadMs: Long = 0,
    val selection: Selection = Selection.None,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val importing: Boolean = false,
    val message: String? = null,
)

class EditorViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val projectId: String = checkNotNull(savedState["projectId"])
    private val repo = ProjectRepository.get(app)
    private val importer = MediaImporter(app)
    val engine = PreviewEngine(app)

    private var history: History<Project>? = null
    /** Estado anterior a un cambio continuo (p. ej. arrastrar un slider) aún sin confirmar. */
    private var liveBase: Project? = null

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export.asStateFlow()

    private var saveJob: Job? = null
    private var previewJob: Job? = null
    private var exportJob: Job? = null
    private var tickerJob: Job? = null

    init {
        viewModelScope.launch {
            val p = repo.load(projectId) ?: return@launch
            history = History(p)
            publish(p, reloadPreview = true)
            val missing = importer.countMissing(
                p.clips.map { it.uri } + p.audio.map { it.uri } + p.texts.mapNotNull { it.imageUri ?: it.videoUri }
            )
            if (missing > 0) toast("No se pueden abrir $missing archivos del proyecto (borrados o movidos)")
        }
        engine.setOnEnded { _state.value = _state.value.copy(playheadMs = _state.value.project?.durationMs ?: 0) }
        // Mientras se reproduce, el cabezal sigue la posición del reproductor.
        viewModelScope.launch {
            engine.isPlaying.collect { playing ->
                tickerJob?.cancel()
                if (playing) {
                    tickerJob = launch {
                        while (isActive) {
                            _state.value = _state.value.copy(playheadMs = engine.positionMs)
                            delay(33)
                        }
                    }
                }
            }
        }
    }

    // ---------- Historial y edición ----------

    /** Aplica un cambio confirmado (crea un paso de deshacer). */
    fun edit(transform: (Project) -> Project) {
        val h = history ?: return
        // Si había un cambio continuo sin confirmar, se confirma primero como su propio paso.
        liveBase?.let { base ->
            val cur = h.current
            h.replace(base)
            h.push(cur)
            liveBase = null
        }
        h.push(transform(h.current))
        publish(h.current, reloadPreview = true)
    }

    /** Cambio continuo (mientras se arrastra): se ve al instante pero aún no crea paso de deshacer. */
    fun live(transform: (Project) -> Project) {
        val h = history ?: return
        if (liveBase == null) liveBase = h.current
        val next = transform(currentProject() ?: h.current)
        h.replace(next)
        publish(next, reloadPreview = true)
    }

    /** Confirma el cambio continuo en curso. */
    fun commitLive() {
        val h = history ?: return
        val base = liveBase ?: return
        liveBase = null
        val final = h.current
        h.replace(base)
        h.push(final)
        publish(h.current, reloadPreview = false)
    }

    fun undo() {
        val h = history ?: return
        liveBase = null
        publish(h.undo(), reloadPreview = true)
    }

    fun redo() {
        val h = history ?: return
        liveBase = null
        publish(h.redo(), reloadPreview = true)
    }

    private fun currentProject(): Project? = history?.current

    private fun publish(p: Project, reloadPreview: Boolean) {
        val h = history
        val sel = _state.value.selection.takeIf { exists(p, it) } ?: Selection.None
        _state.value = _state.value.copy(
            project = p,
            selection = sel,
            playheadMs = _state.value.playheadMs.coerceIn(0, p.durationMs),
            canUndo = h?.canUndo == true,
            canRedo = h?.canRedo == true,
        )
        scheduleSave(p)
        if (reloadPreview) schedulePreview(p)
    }

    private fun exists(p: Project, s: Selection) = when (s) {
        Selection.None -> true
        is Selection.VideoClip -> p.clips.any { it.id == s.id }
        is Selection.Audio -> p.audio.any { it.id == s.id }
        is Selection.Text -> p.texts.any { it.id == s.id }
    }

    private fun scheduleSave(p: Project) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            repo.save(p)
        }
    }

    /** Parte del proyecto que afecta al reproductor de vista previa (las capas no: van aparte). */
    private fun previewKey(p: Project) = p.copy(name = "", updatedAt = 0, lastExportUri = null, texts = emptyList(), markers = emptyList())

    private var loadedPreviewKey: Project? = null

    private fun schedulePreview(p: Project) {
        val key = previewKey(p)
        if (key == loadedPreviewKey) return
        loadedPreviewKey = key
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(250)
            if (p.clips.isEmpty()) engine.clear() else engine.load(p, _state.value.playheadMs)
        }
    }

    // ---------- Reproducción ----------

    fun togglePlay() {
        if (engine.isPlaying.value) engine.pause() else engine.play()
    }

    fun scrubTo(ms: Long) {
        val total = currentProject()?.durationMs ?: 0
        val t = ms.coerceIn(0, total)
        if (engine.isPlaying.value) engine.pause()
        _state.value = _state.value.copy(playheadMs = t)
        engine.seekTo(t)
    }

    fun select(selection: Selection) {
        _state.value = _state.value.copy(selection = selection)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun toast(msg: String) {
        _state.value = _state.value.copy(message = msg)
    }

    // ---------- Importación ----------

    fun importVisual(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true)
            val clips = importer.importVisual(uris)
            _state.value = _state.value.copy(importing = false)
            if (clips.isEmpty()) {
                toast("No se pudieron leer los archivos")
                return@launch
            }
            if (clips.size < uris.size) toast("Algunos archivos no se pudieron importar")
            // Inserta después del clip en el que está el cabezal (o al final si la pista está vacía).
            edit { p ->
                val pos = Timeline.clipAt(p, _state.value.playheadMs)
                val idx = if (pos == null) p.clips.size else pos.index + 1
                Timeline.addClips(p, clips, idx)
            }
        }
    }

    fun importAudio(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true)
            val clip = importer.importAudio(uri)
            _state.value = _state.value.copy(importing = false)
            if (clip == null) {
                toast("No se pudo leer el audio")
                return@launch
            }
            edit { Timeline.addAudio(it, clip, _state.value.playheadMs) }
            _state.value = _state.value.copy(selection = Selection.Audio(clip.id))
        }
    }

    // ---------- Acciones sobre la selección ----------

    fun splitAtPlayhead() {
        val t = _state.value.playheadMs
        when (val s = _state.value.selection) {
            is Selection.Audio -> {
                var newId: String? = null
                edit { p -> Timeline.splitAudioAt(p, t, MediaImporter.newId(), s.id).also { newId = it.second }.first }
                if (newId == null) toast("Coloca el cabezal dentro del audio para dividirlo")
            }
            is Selection.Text -> {
                val layer = currentProject()?.texts?.firstOrNull { it.id == s.id } ?: return
                if (t <= layer.startMs || t >= layer.endMs) {
                    toast("Coloca el cabezal dentro de la capa para dividirla")
                    return
                }
                edit { Timeline.splitLayerAt(it, layer.id, t, MediaImporter.newId()) }
            }
            else -> {
                var newId: String? = null
                edit { p -> Timeline.splitAt(p, t, MediaImporter.newId()).also { newId = it.second }.first }
                if (newId == null) toast("Mueve el cabezal lejos del borde del clip para dividir")
            }
        }
    }

    fun deleteSelection() {
        when (val s = _state.value.selection) {
            is Selection.VideoClip -> edit { Timeline.removeClip(it, s.id) }
            is Selection.Audio -> edit { Timeline.removeAudio(it, s.id) }
            is Selection.Text -> edit { Timeline.removeText(it, s.id) }
            Selection.None -> return
        }
        select(Selection.None)
    }

    fun duplicateSelection() {
        when (val s = _state.value.selection) {
            is Selection.VideoClip -> edit { Timeline.duplicateClip(it, s.id, MediaImporter.newId()) }
            is Selection.Text -> {
                val layer = currentProject()?.texts?.firstOrNull { it.id == s.id } ?: return
                edit { Timeline.addText(it, layer.copy(id = MediaImporter.newId(), startMs = layer.endMs, endMs = layer.endMs + layer.durationMs)) }
            }
            else -> Unit
        }
    }

    /** Inserta en el cabezal una imagen fija del fotograma que se está viendo. */
    fun freezeFrame() {
        val p = currentProject() ?: return
        val t = _state.value.playheadMs
        val found = Timeline.sourcePositionAt(p, t)
        if (found == null) {
            toast("Coloca el cabezal sobre un vídeo")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true)
            val still = importer.freezeFrame(found.first, found.second)
            _state.value = _state.value.copy(importing = false)
            if (still == null) {
                toast("No se pudo capturar el fotograma")
                return@launch
            }
            edit { proj ->
                val (split, newId) = Timeline.splitAt(proj, t, MediaImporter.newId())
                val idx = if (newId != null) split.clips.indexOfFirst { it.id == newId }
                else (Timeline.clipAt(split, t)?.let { pos -> if (pos.offsetMs <= 0) pos.index else pos.index + 1 } ?: split.clips.size)
                Timeline.addClips(split, listOf(still), idx)
            }
            select(Selection.VideoClip(still.id))
        }
    }

    /** Guarda en la galería, como foto, el fotograma del vídeo que hay en el cabezal. */
    fun saveFrame() {
        val p = currentProject() ?: return
        val found = Timeline.sourcePositionAt(p, _state.value.playheadMs)
        if (found == null) {
            toast("Coloca el cabezal sobre un vídeo")
            return
        }
        viewModelScope.launch {
            val frame = importer.grabFrame(found.first, found.second)
            val saved = frame != null && withContext(Dispatchers.IO) {
                runCatching { com.thenovisx.edicion.media.Gallery.saveImage(getApplication(), frame, "captura_${System.currentTimeMillis()}.jpg") }.isSuccess
            }
            toast(if (saved) "Captura guardada en Pictures/Edicion" else "No se pudo guardar la captura")
        }
    }

    /** Pasa el sonido del clip seleccionado a la pista de música para editarlo por separado. */
    fun extractAudio() {
        val s = _state.value.selection as? Selection.VideoClip ?: return
        var newId: String? = null
        edit { p -> Timeline.extractAudio(p, s.id, MediaImporter.newId()).also { newId = it.second }.first }
        if (newId == null) toast("Este clip no tiene sonido que extraer (o tiene la velocidad cambiada)")
        else toast("Sonido movido a la pista de música")
    }

    /** Mueve un audio o una capa en el tiempo (arrastre en la línea de tiempo). */
    fun moveItem(selection: Selection, deltaMs: Long) {
        if (deltaMs == 0L) return
        when (selection) {
            is Selection.Audio -> live { Timeline.updateAudio(it, selection.id) { a -> a.copy(startMs = (a.startMs + deltaMs).coerceAtLeast(0)) } }
            is Selection.Text -> live {
                Timeline.updateText(it, selection.id) { l ->
                    val d = deltaMs.coerceIn(-l.startMs, (it.durationMs - l.endMs).coerceAtLeast(-l.startMs))
                    l.copy(startMs = l.startMs + d, endMs = l.endMs + d)
                }
            }
            else -> Unit
        }
    }

    /** Cambia el archivo de un clip conservando filtro, ajustes, encuadre, efecto y transición. */
    fun replaceClip(id: String, uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true)
            val fresh = importer.importVisual(listOf(uri)).firstOrNull()
            _state.value = _state.value.copy(importing = false)
            if (fresh == null) {
                toast("No se pudo leer el archivo")
                return@launch
            }
            updateClip(id) { old ->
                fresh.copy(
                    id = old.id,
                    // Se conserva la duración que ocupaba, si el archivo nuevo da para ello.
                    trimEndMs = minOf(fresh.sourceDurationMs, if (fresh.kind == MediaKind.IMAGE) old.durationMs else old.trimmedDurationMs).coerceAtLeast(MIN_CLIP_DURATION_MS),
                    speed = if (fresh.kind == MediaKind.VIDEO) old.speed else 1f,
                    volume = old.volume, muted = old.muted,
                    filter = old.filter, filterIntensity = old.filterIntensity, adjustments = old.adjustments,
                    transition = old.transition, transitionMs = old.transitionMs,
                    fill = old.fill, zoom = old.zoom, panX = old.panX, panY = old.panY, effect = old.effect,
                )
            }
        }
    }

    /** Silencia (o devuelve el sonido a) todos los clips de vídeo a la vez. */
    fun toggleMuteAll() {
        val p = currentProject() ?: return
        val videos = p.clips.filter { it.kind == MediaKind.VIDEO }
        if (videos.isEmpty()) return
        val mute = videos.any { !it.muted }
        edit { proj -> proj.copy(clips = proj.clips.map { if (it.kind == MediaKind.VIDEO) it.copy(muted = mute) else it }) }
        toast(if (mute) "Sonido original silenciado" else "Sonido original activado")
    }

    fun toggleMarker() = edit { Timeline.toggleMarker(it, _state.value.playheadMs) }

    fun moveSelectedClip(delta: Int) {
        val s = _state.value.selection as? Selection.VideoClip ?: return
        edit { p ->
            val idx = p.clips.indexOfFirst { it.id == s.id }
            Timeline.moveClip(p, s.id, idx + delta)
        }
    }

    fun updateClip(id: String, live: Boolean = false, transform: (Clip) -> Clip) {
        if (live) live { Timeline.updateClip(it, id, transform) } else edit { Timeline.updateClip(it, id, transform) }
    }

    fun updateAudio(id: String, live: Boolean = false, transform: (AudioClip) -> AudioClip) {
        if (live) live { Timeline.updateAudio(it, id, transform) } else edit { Timeline.updateAudio(it, id, transform) }
    }

    fun updateText(id: String, live: Boolean = false, transform: (TextLayer) -> TextLayer) {
        if (live) live { Timeline.updateText(it, id, transform) } else edit { Timeline.updateText(it, id, transform) }
    }

    fun addText(text: String) = addLayer { start, end ->
        TextLayer(id = MediaImporter.newId(), text = text, startMs = start, endMs = end, shadow = true)
    }

    fun addSticker(emoji: String) = addLayer { start, end ->
        TextLayer(
            id = MediaImporter.newId(), text = emoji, startMs = start, endMs = end,
            scale = 2.5f, bold = false, isSticker = true, animIn = LayerAnim.POP,
        )
    }

    /** Añade una imagen o un vídeo como capa superpuesta en el cabezal. */
    fun addMediaLayer(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true)
            val media = importer.prepareLayerMedia(uri)
            _state.value = _state.value.copy(importing = false)
            if (media == null) {
                toast("No se pudo leer el archivo")
                return@launch
            }
            addLayer { start, end -> mediaLayer(media, MediaImporter.newId(), start, end) }
        }
    }

    /** Sustituye el contenido de una capa de imagen o vídeo conservando posición, tamaño y tiempos. */
    fun replaceLayerMedia(id: String, uri: Uri) {
        viewModelScope.launch {
            val media = importer.prepareLayerMedia(uri)
            if (media == null) {
                toast("No se pudo leer el archivo")
                return@launch
            }
            updateText(id) { old ->
                mediaLayer(media, old.id, old.startMs, old.endMs).copy(
                    x = old.x, y = old.y, scale = old.scale, opacity = old.opacity, rotation = old.rotation,
                    animIn = old.animIn, animOut = old.animOut, keyframes = old.keyframes, shape = old.shape, flipH = old.flipH,
                )
            }
        }
    }

    private fun mediaLayer(m: MediaImporter.LayerMedia, id: String, start: Long, end: Long) = TextLayer(
        id = id,
        text = m.name,
        startMs = start,
        // Un vídeo ocupa por defecto toda su duración (se recorta al final del proyecto).
        endMs = if (m.isVideo) start + m.durationMs else end,
        imageUri = if (m.isVideo) null else m.uri,
        videoUri = if (m.isVideo) m.uri else null,
        sourceDurationMs = m.durationMs,
        hasAudio = m.hasAudio,
        aspect = m.aspect,
    )

    /** Cambia posición, tamaño, giro u opacidad en el cabezal (respeta los fotogramas clave). */
    fun updatePose(id: String, transform: (Keyframe) -> Keyframe) {
        val t = _state.value.playheadMs
        live { Timeline.setLayerPose(it, id, t, transform) }
    }

    fun toggleKeyframe(id: String) {
        val t = _state.value.playheadMs
        val layer = currentProject()?.texts?.firstOrNull { it.id == id } ?: return
        if (!layer.isVisibleAt(t) && t != layer.endMs) {
            toast("Coloca el cabezal dentro de la capa")
            return
        }
        edit { Timeline.toggleKeyframe(it, id, t) }
    }

    /** Crea subtítulos a partir de líneas escritas, seguidos desde el cabezal. */
    fun addSubtitles(text: String) {
        val p = currentProject() ?: return
        if (p.clips.isEmpty()) {
            toast("Añade primero un vídeo o una foto")
            return
        }
        val cues = Subtitles.fromLines(text, _state.value.playheadMs.coerceAtMost((p.durationMs - 500).coerceAtLeast(0)), p.durationMs)
        addCues(cues)
    }

    fun importSrt(uri: Uri) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            val cues = content?.let { Subtitles.parseSrt(it) }.orEmpty()
            if (cues.isEmpty()) toast("No se encontraron subtítulos en el archivo") else addCues(cues)
        }
    }

    private fun addCues(cues: List<Cue>) {
        val p = currentProject() ?: return
        val usable = cues.filter { it.startMs < p.durationMs }
        if (usable.isEmpty()) {
            toast("No caben subtítulos a partir de este punto")
            return
        }
        // Los nuevos heredan el estilo de los subtítulos que ya hubiera.
        val style = p.texts.lastOrNull { it.isSubtitle }
        val layers = usable.map { c ->
            (style ?: TextLayer(
                id = "", text = "", startMs = 0, endMs = 0, y = 0.72f, scale = 0.9f,
                backgroundArgb = 0x99000000, isSubtitle = true,
            )).copy(id = MediaImporter.newId(), text = c.text, startMs = c.startMs, endMs = c.endMs, keyframes = emptyList())
        }
        edit { layers.fold(it) { acc, l -> Timeline.addText(acc, l) } }
        select(Selection.Text(layers.first().id))
        toast("${layers.size} subtítulos añadidos")
    }

    /** Copia el estilo de un subtítulo a todos los demás. */
    fun applyStyleToSubtitles(id: String) {
        val src = currentProject()?.texts?.firstOrNull { it.id == id } ?: return
        edit { p ->
            p.copy(texts = p.texts.map { l ->
                if (!l.isSubtitle || l.id == id) l
                else src.copy(id = l.id, text = l.text, startMs = l.startMs, endMs = l.endMs)
            })
        }
        toast("Estilo aplicado a todos los subtítulos")
    }

    /** Lee en voz alta el texto de una capa y coloca el audio en la pista de voz. */
    fun speakLayer(id: String) {
        val layer = currentProject()?.texts?.firstOrNull { it.id == id } ?: return
        if (layer.text.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(importing = true)
            val file = Speech.synthesize(getApplication(), layer.text)
            val clip = file?.let { importer.importVoice(it, "Voz: ${layer.text.take(16)}") }
            _state.value = _state.value.copy(importing = false)
            if (clip == null) {
                toast("El teléfono no tiene un motor de texto a voz disponible")
                return@launch
            }
            edit { Timeline.addAudio(it, clip, layer.startMs) }
            toast("Voz añadida a la pista de voz")
        }
    }

    fun moveLayerZ(id: String, toFront: Boolean) = edit { Timeline.moveLayerZ(it, id, toFront) }

    private fun addLayer(create: (startMs: Long, endMs: Long) -> TextLayer) {
        val p = currentProject() ?: return
        if (p.clips.isEmpty()) {
            toast("Añade primero un vídeo o una foto")
            return
        }
        val start = _state.value.playheadMs.coerceAtMost((p.durationMs - 500).coerceAtLeast(0))
        val layer = create(start, minOf(start + 3_000, p.durationMs))
        edit { Timeline.addText(it, layer) }
        select(Selection.Text(layer.id))
    }

    // ---------- Voz en off ----------

    private val recorder = VoiceRecorder(app)
    private var voiceStartMs = 0L
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    val recordingElapsedMs: Long get() = recorder.elapsedMs

    fun startVoice() {
        val p = currentProject() ?: return
        if (p.clips.isEmpty()) {
            toast("Añade primero un vídeo o una foto")
            return
        }
        engine.pause()
        voiceStartMs = _state.value.playheadMs
        try {
            recorder.start()
            _recording.value = true
        } catch (e: Exception) {
            toast("No se pudo usar el micrófono")
        }
    }

    fun stopVoice(keep: Boolean) {
        if (!_recording.value) return
        _recording.value = false
        val file = recorder.stop()
        if (!keep) {
            file?.delete()
            return
        }
        if (file == null) {
            toast("La grabación es demasiado corta")
            return
        }
        viewModelScope.launch {
            val n = (currentProject()?.audio?.count { it.lane == LANE_VOICE } ?: 0) + 1
            val clip = importer.importVoice(file, "Voz $n")
            if (clip == null) {
                toast("No se pudo guardar la grabación")
                return@launch
            }
            edit { Timeline.addAudio(it, clip, voiceStartMs) }
            select(Selection.Audio(clip.id))
        }
    }

    fun setAspectRatio(ratio: AspectRatio) = edit { it.copy(aspectRatio = ratio) }

    fun rename(name: String) = edit { it.copy(name = name.ifBlank { it.name }) }

    // ---------- Exportación ----------

    fun startExport(options: ExportOptions) {
        val p = currentProject() ?: return
        if (p.clips.isEmpty()) {
            toast("No hay nada que exportar")
            return
        }
        engine.pause()
        exportJob?.cancel()
        _export.value = ExportState.Running(0f)
        exportJob = viewModelScope.launch {
            try {
                val uri = VideoExporter(getApplication()).export(p, options) { progress ->
                    _export.value = ExportState.Running(progress)
                }
                if (!options.audioOnly) edit { it.copy(lastExportUri = uri.toString()) }
                _export.value = ExportState.Done(uri, options.audioOnly)
            } catch (e: CancellationException) {
                _export.value = ExportState.Idle
                throw e
            } catch (e: Exception) {
                _export.value = ExportState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        _export.value = ExportState.Idle
    }

    fun dismissExport() {
        _export.value = ExportState.Idle
    }

    override fun onCleared() {
        // Guarda de inmediato por si quedaba un guardado pendiente.
        currentProject()?.let { p -> kotlinx.coroutines.runBlocking { repo.save(p) } }
        if (_recording.value) recorder.cancel()
        engine.release()
    }
}
