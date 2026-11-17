package com.thenovisx.edicion.ui.editor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.thenovisx.edicion.core.formatTime
import com.thenovisx.edicion.media.ExportOptions
import com.thenovisx.edicion.media.ExportQuality
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import com.thenovisx.edicion.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(onBack: () -> Unit, vm: EditorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val exportState by vm.export.collectAsStateWithLifecycle()
    val player by vm.engine.player.collectAsState()
    val isPlaying by vm.engine.isPlaying.collectAsState()
    val previewError by vm.engine.error.collectAsState()
    val context = LocalContext.current

    var panel by rememberSaveable { mutableStateOf<Panel?>(null) }
    var textDialog by remember { mutableStateOf<TextDialogMode?>(null) }
    var showExportOptions by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    val pickVisual = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        vm.importVisual(uris)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importAudio(uri)
    }
    val pickLayerMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addMediaLayer(uri)
    }
    var replacingLayerId by remember { mutableStateOf<String?>(null) }
    val pickReplacement = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = replacingLayerId
        replacingLayerId = null
        if (uri != null && id != null) vm.replaceLayerMedia(id, uri)
    }
    var replacingClipId by remember { mutableStateOf<String?>(null) }
    val pickClipReplacement = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = replacingClipId
        replacingClipId = null
        if (uri != null && id != null) vm.replaceClip(id, uri)
    }
    var showStickers by remember { mutableStateOf(false) }
    var showSubtitles by remember { mutableStateOf(false) }
    val pickSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSrt(uri)
    }
    val recording by vm.recording.collectAsState()
    var showVoice by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showVoice = true
        else Toast.makeText(context, "Se necesita permiso de micrófono para grabar la voz", Toast.LENGTH_LONG).show()
    }
    var pendingExport by remember { mutableStateOf<ExportOptions?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val opts = pendingExport
        pendingExport = null
        if (granted && opts != null) vm.startExport(opts)
        else if (!granted) Toast.makeText(context, "Se necesita permiso para guardar en la galería", Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }
    // Al cambiar la selección se cierra el panel activo si ya no corresponde.
    LaunchedEffect(state.selection) {
        if (panel != null && panel !in panelsFor(state.selection, state.project)) panel = null
    }

    BackHandler(enabled = panel != null || state.selection != Selection.None) {
        if (panel != null) panel = null else vm.select(Selection.None)
    }

    val project = state.project
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Barra superior
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Text(
                project?.name ?: "",
                modifier = Modifier.weight(1f).clickable { showRename = true },
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = vm::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Deshacer") }
            IconButton(onClick = vm::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Rehacer") }
            Button(
                onClick = { showExportOptions = true },
                enabled = project?.clips?.isNotEmpty() == true,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Icon(Icons.Default.Upload, null, Modifier.size(18.dp))
                Text("Exportar", Modifier.padding(start = 4.dp))
            }
        }

        // Vista previa
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (project != null) {
                Box(
                    Modifier
                        .aspectRatio(project.aspectRatio.ratio, matchHeightConstraintsFirst = project.aspectRatio.ratio < 1.2f)
                        .background(Color(0xFF050507))
                ) {
                    if (project.clips.isNotEmpty()) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                }
                            },
                            update = { it.player = player },
                            onRelease = { it.player = null },
                            modifier = Modifier.fillMaxSize(),
                        )
                        LayerCanvas(
                            project = project,
                            playheadMs = state.playheadMs,
                            isPlaying = isPlaying,
                            selection = state.selection,
                            onSelect = { vm.select(it); panel = null },
                            onTransform = { id, pan, zoom, rotation ->
                                vm.updatePose(id) {
                                    it.copy(x = it.x + pan.x, y = it.y + pan.y, scale = it.scale * zoom, rotation = it.rotation + rotation)
                                }
                            },
                            onTransformEnd = vm::commitLive,
                        )
                    } else {
                        Text(
                            "Proyecto vacío",
                            color = Palette.OnSurfaceMuted,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            } else {
                CircularProgressIndicator()
            }
            if (state.importing) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            previewError?.let {
                Text(
                    "Vista previa no disponible: $it",
                    color = Palette.Amber,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                )
            }
        }

        // Transporte
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${formatTime(state.playheadMs)} / ${formatTime(project?.durationMs ?: 0)}",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.OnSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = vm::togglePlay,
                enabled = player != null,
                modifier = Modifier.size(48.dp).background(if (player != null) Color.White else Palette.SurfaceHigh, CircleShape),
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (isPlaying) "Pausa" else "Reproducir",
                    tint = Color.Black,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                project?.aspectRatio?.label ?: "",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.OnSurfaceMuted,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }

        if (project != null) {
            TimelineView(
                project = project,
                playheadMs = state.playheadMs,
                selection = state.selection,
                onScrub = vm::scrubTo,
                onSelect = { vm.select(it); panel = null },
                onMoveItem = vm::moveItem,
                onMoveEnd = vm::commitLive,
                modifier = Modifier.height(timelineHeight(project)),
            )

            ToolArea(
                project = project,
                state = state,
                panel = panel,
                onPanel = { panel = it },
                vm = vm,
                onAddMedia = {
                    pickVisual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onAddAudio = { pickAudio.launch(arrayOf("audio/*")) },
                onAddText = { textDialog = TextDialogMode.Add },
                onEditText = { id -> textDialog = TextDialogMode.Edit(id) },
                onAddSticker = { showStickers = true },
                onSubtitles = { showSubtitles = true },
                onAddMediaLayer = {
                    pickLayerMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onReplaceClip = { id ->
                    replacingClipId = id
                    pickClipReplacement.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onReplaceLayer = { id ->
                    replacingLayerId = id
                    pickReplacement.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onVoice = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        showVoice = true
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
        }
    }

    // ---------- Diálogos ----------

    if (showSubtitles) {
        SubtitlesDialog(
            onDismiss = { showSubtitles = false },
            onConfirm = { vm.addSubtitles(it); showSubtitles = false },
            onImport = { showSubtitles = false; pickSrt.launch(arrayOf("*/*")) },
        )
    }

    if (showStickers) {
        StickerDialog(
            onDismiss = { showStickers = false },
            onPick = { vm.addSticker(it); showStickers = false },
        )
    }

    if (showVoice) {
        VoiceDialog(
            recording = recording,
            elapsedMs = { vm.recordingElapsedMs },
            startAt = formatTime(state.playheadMs),
            onStart = vm::startVoice,
            onStop = { vm.stopVoice(keep = true); showVoice = false },
            onCancel = { vm.stopVoice(keep = false); showVoice = false },
        )
    }

    textDialog?.let { mode ->
        val initial = (mode as? TextDialogMode.Edit)?.let { m -> project?.texts?.firstOrNull { it.id == m.id }?.text } ?: ""
        TextInputDialog(
            title = if (mode is TextDialogMode.Add) "Añadir texto" else "Editar texto",
            initial = initial,
            onDismiss = { textDialog = null },
            onConfirm = { text ->
                when (mode) {
                    TextDialogMode.Add -> vm.addText(text)
                    is TextDialogMode.Edit -> vm.updateText(mode.id) { it.copy(text = text) }
                }
                textDialog = null
            },
        )
    }

    if (showRename && project != null) {
        TextInputDialog(
            title = "Nombre del proyecto",
            initial = project.name,
            singleLine = true,
            onDismiss = { showRename = false },
            onConfirm = { vm.rename(it); showRename = false },
        )
    }

    if (showExportOptions) {
        ExportOptionsDialog(
            onDismiss = { showExportOptions = false },
            onExport = { opts ->
                showExportOptions = false
                if (Build.VERSION.SDK_INT < 29) {
                    pendingExport = opts
                    storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    vm.startExport(opts)
                }
            },
        )
    }

    when (val e = exportState) {
        is ExportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Exportando…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { e.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(e.progress * 100).toInt()} %", Modifier.padding(top = 8.dp))
                    Text("No cierres la app mientras se exporta.", fontSize = 12.sp, color = Palette.OnSurfaceMuted)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancelExport) { Text("Cancelar") } },
        )
        is ExportState.Done -> AlertDialog(
            onDismissRequest = vm::dismissExport,
            title = { Text(if (e.audioOnly) "¡Audio guardado!" else "¡Vídeo guardado!") },
            text = {
                Text(
                    if (e.audioOnly) "Lo encontrarás en la carpeta Music/Edicion."
                    else "Lo encontrarás en la galería, en la carpeta Movies/Edicion."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = if (e.audioOnly) "audio/mp4" else "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, e.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Compartir vídeo"))
                }) { Text("Compartir") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(e.uri, if (e.audioOnly) "audio/mp4" else "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(view) }
                    vm.dismissExport()
                }) { Text(if (e.audioOnly) "Escuchar" else "Ver") }
            },
        )
        is ExportState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissExport,
            title = { Text("No se pudo exportar") },
            text = { Text(e.message) },
            confirmButton = { TextButton(onClick = vm::dismissExport) { Text("Aceptar") } },
        )
        ExportState.Idle -> Unit
    }
}

private sealed interface TextDialogMode {
    data object Add : TextDialogMode
    data class Edit(val id: String) : TextDialogMode
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    singleLine: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun ExportOptionsDialog(onDismiss: () -> Unit, onExport: (ExportOptions) -> Unit) {
    val options = listOf(720 to "720p", 1080 to "1080p", 2160 to "4K")
    var selected by remember { mutableStateOf(1080) }
    var quality by remember { mutableStateOf(ExportQuality.entries[ExportQuality.entries.size / 2]) }
    var hevc by remember { mutableStateOf(false) }
    var audioOnly by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exportar vídeo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Resolución", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { (value, label) ->
                        FilterChip(selected = selected == value, onClick = { selected = value }, label = { Text(label) })
                    }
                }
                Text("Calidad", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportQuality.entries.forEach { q ->
                        FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q.label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Códec HEVC (H.265)", fontWeight = FontWeight.SemiBold)
                        Text("Archivos más pequeños. Algunos móviles antiguos no lo reproducen.", fontSize = 12.sp, color = Palette.OnSurfaceMuted)
                    }
                    Switch(checked = hevc, onCheckedChange = { hevc = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Solo audio", fontWeight = FontWeight.SemiBold)
                        Text("Guarda únicamente el sonido del proyecto como archivo M4A.", fontSize = 12.sp, color = Palette.OnSurfaceMuted)
                    }
                    Switch(checked = audioOnly, onCheckedChange = { audioOnly = it })
                }
                Text(
                    if (audioOnly) "M4A · AAC" else "MP4 · ${if (hevc) "H.265" else "H.264"} · AAC",
                    fontSize = 12.sp, color = Palette.OnSurfaceMuted,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onExport(ExportOptions(selected, hevc, quality, audioOnly)) }) { Text("Exportar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun SubtitlesDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit, onImport: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtítulos") },
        text = {
            Column {
                Text(
                    "Escribe una frase por línea. Se colocan seguidas a partir del cabezal y luego puedes ajustar cada una.",
                    fontSize = 12.sp, color = Palette.OnSurfaceMuted,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp).padding(top = 8.dp),
                )
                TextButton(onClick = onImport) { Text("Importar archivo .srt") }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Crear") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private val Stickers = listOf(
    "😀", "😂", "😍", "🥳", "😎", "🤩", "😭", "😡", "🤯", "😴", "🤔", "🙌",
    "👍", "👏", "🙏", "💪", "👀", "💯", "🔥", "✨", "⭐", "❤️", "💔", "💥",
    "🎉", "🎂", "🎁", "🎵", "🎬", "📸", "🏆", "⚽", "🌈", "☀️", "🌙", "⚡",
    "🌸", "🍕", "☕", "🐶", "🐱", "🚀", "✈️", "🏖️", "📍", "➡️", "⬇️", "✅",
    "⬆️", "⬅️", "↗️", "🔴", "🟡", "🟢", "🔵", "⚠️", "❌", "❓", "❗", "💬",
    "👑", "💎", "💰", "🎯", "📌", "🔔", "🕐", "📅", "🎤", "🎧", "🎮", "📱",
    "🥰", "😱", "🤣", "😇", "🤫", "🫶", "👋", "🤝", "💃", "🕺", "🏃", "🧘",
    "🌍", "🌊", "🏔️", "🌴", "❄️", "🍂", "🌹", "🦋", "🍔", "🍰", "🥂", "🍿",
)

@Composable
private fun StickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stickers") },
        text = {
            LazyVerticalGrid(columns = GridCells.Fixed(6), modifier = Modifier.height(280.dp)) {
                items(Stickers) { e ->
                    Box(
                        Modifier.aspectRatio(1f).clickable { onPick(e) },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 28.sp) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun VoiceDialog(
    recording: Boolean,
    elapsedMs: () -> Long,
    startAt: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = elapsedMs()
            delay(100)
        }
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Voz en off") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (recording) formatTime(elapsed) else "La grabación se colocará en $startAt",
                    fontSize = if (recording) 34.sp else 14.sp,
                    fontWeight = if (recording) FontWeight.Bold else FontWeight.Normal,
                    color = if (recording) Palette.Coral else Palette.OnSurfaceMuted,
                )
                IconButton(
                    onClick = { if (recording) onStop() else onStart() },
                    modifier = Modifier.padding(top = 16.dp).size(72.dp).background(Palette.Coral, CircleShape),
                ) {
                    Icon(
                        if (recording) Icons.Default.Stop else Icons.Default.Mic,
                        if (recording) "Detener y guardar" else "Grabar",
                        tint = Color.Black,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Text(
                    if (recording) "Toca para terminar y añadirla" else "Toca para empezar a grabar",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(if (recording) "Descartar" else "Cerrar") } },
    )
}
