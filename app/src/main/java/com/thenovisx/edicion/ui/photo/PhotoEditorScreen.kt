package com.thenovisx.edicion.ui.photo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import com.thenovisx.edicion.ui.common.ColorChoice
import com.thenovisx.edicion.media.LayerRenderer
import com.thenovisx.edicion.core.TextLayer
import com.thenovisx.edicion.core.TextFont
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import android.graphics.Paint
import android.graphics.Canvas
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenovisx.edicion.core.Adjustments
import com.thenovisx.edicion.core.ColorPipeline
import com.thenovisx.edicion.core.FilterPreset
import com.thenovisx.edicion.media.Gallery
import com.thenovisx.edicion.ui.common.FilterSwatch
import com.thenovisx.edicion.ui.common.colorFilterFor
import com.thenovisx.edicion.ui.common.AdjustmentSliders
import com.thenovisx.edicion.ui.common.LabeledSlider
import com.thenovisx.edicion.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Proporciones de recorte disponibles. null = sin recorte. */
private val CropRatios: List<Pair<String, Float?>> = listOf(
    "Original" to null,
    "1:1" to 1f,
    "4:5" to 4f / 5f,
    "3:4" to 3f / 4f,
    "9:16" to 9f / 16f,
    "16:9" to 16f / 9f,
    "3:2" to 3f / 2f,
)

private enum class PhotoTool(val label: String, val icon: ImageVector) {
    FILTERS("Filtros", Icons.Default.PhotoFilter),
    ADJUST("Ajustes", Icons.Default.Tune),
    CROP("Recorte", Icons.Default.Crop),
    FRAME("Marco", Icons.Default.CropSquare),
    TEXT("Texto", Icons.Default.TextFields),
}

/** Parámetros de edición de una foto (no destructivos hasta guardar). */
private data class PhotoEdit(
    val filter: FilterPreset = FilterPreset.NONE,
    val intensity: Float = 1f,
    val adjustments: Adjustments = Adjustments(),
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Enderezar: giro fino en grados; la imagen se amplía lo justo para no dejar esquinas vacías. */
    val straighten: Float = 0f,
    val crop: Float? = null,
    /** Marco: color (alfa 0 = sin marco) y grosor como fracción del lado corto. */
    val frameArgb: Long = 0x00000000,
    val frameWidth: Float = 0.04f,
    /** Textos y stickers colocados sobre la foto. */
    val texts: List<TextLayer> = emptyList(),
)

private val FrameColors = listOf<Long>(0x00000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFE9D6, 0xFFFF6B5E, 0xFFFFC14D, 0xFF7A5CFF, 0xFF2EC4B6, 0xFFFF7EB6)
private val PhotoTextColors = listOf<Long>(0xFFFFFFFF, 0xFF000000, 0xFFFF6B5E, 0xFFFFE14D, 0xFF7A5CFF, 0xFF2EC4B6, 0xFFFF7EB6, 0xFF4DA3FF)
private val PhotoStickers = listOf("😀", "😍", "😎", "🥳", "🔥", "✨", "❤️", "⭐", "🎉", "👍", "🌈", "☀️", "🌸", "📍", "💯", "🎂")

@Composable
fun PhotoEditorScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var edit by remember { mutableStateOf(PhotoEdit()) }
    // Historial para deshacer: se guarda un paso cada vez que termina un cambio.
    var undo by remember { mutableStateOf(listOf<PhotoEdit>()) }
    var committed by remember { mutableStateOf(PhotoEdit()) }
    fun commit() {
        if (edit != committed) {
            undo = (undo + committed).takeLast(40)
            committed = edit
        }
    }
    fun change(e: PhotoEdit) {
        edit = e
        commit()
    }
    var tool by remember { mutableStateOf(PhotoTool.FILTERS) }
    var saving by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var selectedText by remember { mutableStateOf<String?>(null) }
    var textDialog by remember { mutableStateOf(false) }

    val source by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { decodePhoto(context, uri, 1600) }.getOrNull() }
    }
    // Geometría (girar, voltear, enderezar, recortar) aplicada a la imagen de vista previa.
    val preview by produceState<Bitmap?>(null, source, edit.rotation, edit.flipH, edit.flipV, edit.straighten, edit.crop) {
        val src = source ?: return@produceState
        value = withContext(Dispatchers.Default) { applyGeometry(src, edit) }
    }
    val pipeline = remember(edit.filter, edit.intensity, edit.adjustments) {
        ColorPipeline(edit.filter, edit.intensity, edit.adjustments)
    }
    // Si solo hay cambios lineales basta un filtro de color (instantáneo); si no, se procesa la imagen.
    val needsPixels = !pipeline.isMatrixOnly || edit.adjustments.vignette > 0f || edit.adjustments.grain > 0f
    val colorFilter = if (needsPixels || showOriginal) null else colorFilterFor(pipeline)
    val graded by produceState<Bitmap?>(null, preview, pipeline, needsPixels) {
        val src = preview
        if (src == null || !needsPixels) {
            value = null
        } else {
            value = withContext(Dispatchers.Default) { applyPipeline(downscale(src, 900), pipeline) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Text("Editar foto", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    val last = undo.lastOrNull()
                    if (last != null) {
                        undo = undo.dropLast(1)
                        edit = last
                        committed = last
                    }
                },
                enabled = undo.isNotEmpty(),
            ) { Icon(Icons.AutoMirrored.Filled.Undo, "Deshacer") }
            IconButton(onClick = { change(edit.copy(rotation = (edit.rotation + 90) % 360)) }) {
                Icon(Icons.Default.RotateRight, "Girar")
            }
            IconButton(onClick = { change(edit.copy(flipH = !edit.flipH)) }) { Icon(Icons.Default.Flip, "Voltear") }
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        val result = runCatching { save(context, uri, edit) }
                        saving = false
                        result.onSuccess { saved ->
                            Toast.makeText(context, "Foto guardada en Pictures/Edicion", Toast.LENGTH_SHORT).show()
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, saved)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Compartir foto"))
                        }.onFailure {
                            Toast.makeText(context, "No se pudo guardar: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = source != null && !saving,
                modifier = Modifier.padding(end = 8.dp),
            ) { Text("Guardar") }
        }

        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).background(Color.Black).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = if (showOriginal) preview else if (needsPixels) graded ?: preview else preview
            if (bmp != null) {
                // Tamaño exacto de la foto en pantalla, para colocar encima el marco y los textos.
                val fit = minOf(constraints.maxWidth.toFloat() / bmp.width, constraints.maxHeight.toFloat() / bmp.height)
                val wPx = (bmp.width * fit).roundToInt().coerceAtLeast(1)
                val hPx = (bmp.height * fit).roundToInt().coerceAtLeast(1)
                Box(
                    Modifier
                        .requiredSize(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { selectedText = null },
                                // Mantener pulsado enseña la foto original para comparar.
                                onLongPress = { showOriginal = true },
                                onPress = {
                                    tryAwaitRelease()
                                    showOriginal = false
                                },
                            )
                        }
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        colorFilter = colorFilter,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (!showOriginal) {
                        if (edit.frameArgb ushr 24 != 0L) {
                            val fw = with(density) { (edit.frameWidth * minOf(wPx, hPx)).toDp() }
                            Box(Modifier.fillMaxSize().border(fw, Color(edit.frameArgb.toInt())))
                        }
                        edit.texts.forEach { layer ->
                            key(layer.id) {
                                val textBmp = remember(layer.copy(x = 0f, y = 0f, rotation = 0f), wPx, hPx) {
                                    LayerRenderer.renderText(layer, layer.text, wPx, hPx)
                                }
                                val selected = layer.id == selectedText
                                Image(
                                    bitmap = textBmp.asImageBitmap(),
                                    contentDescription = layer.text,
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .requiredSize(with(density) { textBmp.width.toDp() }, with(density) { textBmp.height.toDp() })
                                        .graphicsLayer {
                                            translationX = layer.x * wPx / 2f
                                            translationY = layer.y * hPx / 2f
                                            rotationZ = layer.rotation
                                        }
                                        .then(if (selected) Modifier.border(2.dp, Palette.Coral) else Modifier)
                                        .pointerInput(layer.id) {
                                            detectTapGestures { selectedText = layer.id; tool = PhotoTool.TEXT }
                                        }
                                        .pointerInput(layer.id, wPx, hPx) {
                                            detectTransformGestures { _, pan, zoom, rotation ->
                                                selectedText = layer.id
                                                // El gesto llega en el sistema girado de la capa: se pasa al de la foto.
                                                val current = edit.texts.firstOrNull { it.id == layer.id } ?: return@detectTransformGestures
                                                val a = Math.toRadians(current.rotation.toDouble())
                                                val dx = (pan.x * Math.cos(a) - pan.y * Math.sin(a)).toFloat()
                                                val dy = (pan.x * Math.sin(a) + pan.y * Math.cos(a)).toFloat()
                                                edit = edit.copy(texts = edit.texts.map {
                                                    if (it.id != layer.id) it else it.copy(
                                                        x = (it.x + dx / (wPx / 2f)).coerceIn(-1f, 1f),
                                                        y = (it.y + dy / (hPx / 2f)).coerceIn(-1f, 1f),
                                                        scale = (it.scale * zoom).coerceIn(0.3f, 8f),
                                                        rotation = it.rotation + rotation,
                                                    )
                                                })
                                            }
                                        },
                                )
                            }
                        }
                    }
                }
            } else {
                CircularProgressIndicator()
            }
            if (saving) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().background(Palette.Surface).padding(12.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(150.dp).verticalScroll(rememberScrollState())) {
                when (tool) {
                    PhotoTool.FILTERS -> Column {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterPreset.entries.forEach { f ->
                                FilterSwatch(f, edit.filter == f) { change(edit.copy(filter = f)) }
                            }
                        }
                        if (edit.filter != FilterPreset.NONE) {
                            LabeledSlider(
                                label = "Intensidad",
                                valueText = "${(edit.intensity * 100).roundToInt()} %",
                                value = edit.intensity,
                                range = 0f..1f,
                                onChange = { edit = edit.copy(intensity = it) },
                                onDone = ::commit,
                            )
                        }
                    }
                    PhotoTool.ADJUST -> AdjustmentSliders(
                        adjustments = edit.adjustments,
                        onChange = { edit = edit.copy(adjustments = it) },
                        onDone = ::commit,
                        onReset = { change(edit.copy(adjustments = Adjustments())) },
                    )
                    PhotoTool.CROP -> Column {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CropRatios.forEach { (label, r) ->
                                FilterChip(selected = edit.crop == r, onClick = { change(edit.copy(crop = r)) }, label = { Text(label) })
                            }
                            FilterChip(selected = edit.flipV, onClick = { change(edit.copy(flipV = !edit.flipV)) }, label = { Text("Voltear vertical") })
                        }
                        LabeledSlider(
                            label = "Enderezar",
                            valueText = "%.1f°".format(edit.straighten),
                            value = edit.straighten,
                            range = -30f..30f,
                            onChange = { edit = edit.copy(straighten = it) },
                            onDone = ::commit,
                        )
                    }
                    PhotoTool.FRAME -> Column {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FrameColors.forEach { c ->
                                if (c == 0L) {
                                    FilterChip(selected = edit.frameArgb == 0L, onClick = { change(edit.copy(frameArgb = 0)) }, label = { Text("Sin marco") })
                                } else {
                                    ColorChoice(c, selected = edit.frameArgb == c) { change(edit.copy(frameArgb = c)) }
                                }
                            }
                        }
                        if (edit.frameArgb != 0L) {
                            LabeledSlider(
                                label = "Grosor",
                                valueText = "${(edit.frameWidth * 1000).roundToInt()}",
                                value = edit.frameWidth,
                                range = 0.005f..0.15f,
                                onChange = { edit = edit.copy(frameWidth = it) },
                                onDone = ::commit,
                            )
                        }
                    }
                    PhotoTool.TEXT -> Column {
                        val sel = edit.texts.firstOrNull { it.id == selectedText }
                        fun update(f: (TextLayer) -> TextLayer) {
                            change(edit.copy(texts = edit.texts.map { if (it.id == selectedText) f(it) else it }))
                        }
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(selected = false, onClick = { textDialog = true }, label = { Text("+ Texto") })
                            PhotoStickers.forEach { e ->
                                Text(
                                    e,
                                    fontSize = 24.sp,
                                    modifier = Modifier.clickable {
                                        val id = "t${System.nanoTime()}"
                                        change(edit.copy(texts = edit.texts + TextLayer(id = id, text = e, startMs = 0, endMs = 1, scale = 3f, bold = false, isSticker = true)))
                                        selectedText = id
                                    }.padding(4.dp),
                                )
                            }
                        }
                        if (sel != null) {
                            if (!sel.isSticker) {
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PhotoTextColors.forEach { c -> ColorChoice(c, selected = sel.colorArgb == c) { update { it.copy(colorArgb = c) } } }
                                }
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    TextFont.entries.forEach { f ->
                                        FilterChip(selected = sel.font == f, onClick = { update { it.copy(font = f) } }, label = { Text(f.label) })
                                    }
                                    FilterChip(selected = sel.backgroundArgb != 0L, onClick = {
                                        update { it.copy(backgroundArgb = if (it.backgroundArgb != 0L) 0L else 0xB3000000L) }
                                    }, label = { Text("Fondo") })
                                    FilterChip(selected = sel.outlineArgb != 0L, onClick = {
                                        update { it.copy(outlineArgb = if (it.outlineArgb != 0L) 0L else 0xFF000000L) }
                                    }, label = { Text("Contorno") })
                                    FilterChip(selected = sel.shadow, onClick = { update { it.copy(shadow = !it.shadow) } }, label = { Text("Sombra") })
                                }
                            }
                            Text(
                                "Quitar",
                                color = Palette.Coral,
                                fontSize = 13.sp,
                                modifier = Modifier.clickable {
                                    change(edit.copy(texts = edit.texts.filterNot { it.id == selectedText }))
                                    selectedText = null
                                }.padding(vertical = 8.dp),
                            )
                        } else {
                            Text(
                                "Arrastra un texto para moverlo y pellizca para cambiar su tamaño o girarlo.",
                                fontSize = 12.sp, color = Palette.OnSurfaceMuted,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PhotoTool.entries.forEach { t ->
                    Column(
                        Modifier.width(64.dp).clickable { tool = t }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(t.icon, null, tint = if (tool == t) Palette.Coral else Palette.OnSurface)
                        Spacer(Modifier.height(2.dp))
                        Text(t.label, fontSize = 11.sp, color = if (tool == t) Palette.Coral else Palette.OnSurface)
                    }
                }
            }
        }
    }

    // Los gestos sobre los textos cambian el estado en vivo: se confirma el paso al soltar.
    LaunchedEffect(edit.texts) {
        kotlinx.coroutines.delay(600)
        commit()
    }

    if (textDialog) {
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textDialog = false },
            title = { Text("Añadir texto") },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = "t${System.nanoTime()}"
                        change(edit.copy(texts = edit.texts + TextLayer(id = id, text = value.trim(), startMs = 0, endMs = 1, scale = 1.6f, shadow = true)))
                        selectedText = id
                        textDialog = false
                    },
                    enabled = value.isNotBlank(),
                ) { Text("Añadir") }
            },
            dismissButton = { TextButton(onClick = { textDialog = false }) { Text("Cancelar") } },
        )
    }
}

/** Gira, voltea, endereza y recorta al centro según la proporción pedida. */
private fun applyGeometry(src: Bitmap, edit: PhotoEdit): Bitmap {
    val m = Matrix()
    if (edit.flipH || edit.flipV) m.postScale(if (edit.flipH) -1f else 1f, if (edit.flipV) -1f else 1f)
    if (edit.rotation != 0) m.postRotate(edit.rotation.toFloat())
    var out = if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    if (edit.straighten != 0f) {
        // Gira dentro del mismo tamaño y amplía lo necesario para tapar las esquinas vacías.
        val w = out.width
        val h = out.height
        val rad = Math.toRadians(kotlin.math.abs(edit.straighten).toDouble())
        val zoom = maxOf(
            (w * Math.cos(rad) + h * Math.sin(rad)) / w,
            (w * Math.sin(rad) + h * Math.cos(rad)) / h,
        ).toFloat()
        val straight = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(straight)
        canvas.translate(w / 2f, h / 2f)
        canvas.rotate(edit.straighten)
        canvas.scale(zoom, zoom)
        canvas.drawBitmap(out, -w / 2f, -h / 2f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        out = straight
    }
    val crop = edit.crop
    if (crop != null) {
        val current = out.width.toFloat() / out.height
        val (w, h) = if (current > crop) {
            (out.height * crop).roundToInt() to out.height
        } else {
            out.width to (out.width / crop).roundToInt()
        }
        val x = (out.width - w) / 2
        val y = (out.height - h) / 2
        out = Bitmap.createBitmap(out, x, y, w.coerceAtLeast(1), h.coerceAtLeast(1))
    }
    return out
}

private fun downscale(src: Bitmap, maxSide: Int): Bitmap {
    val side = maxOf(src.width, src.height)
    if (side <= maxSide) return src
    val f = maxSide.toFloat() / side
    return Bitmap.createScaledBitmap(src, (src.width * f).roundToInt().coerceAtLeast(1), (src.height * f).roundToInt().coerceAtLeast(1), true)
}

/** Aplica color, viñeta y grano píxel a píxel. Devuelve una imagen nueva y modificable. */
private fun applyPipeline(src: Bitmap, pipeline: ColorPipeline): Bitmap {
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    pipeline.applyToPixels(pixels, w, h)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

/** Aplica todo a la imagen a alta resolución y la guarda en la galería. */
private suspend fun save(context: Context, uri: Uri, edit: PhotoEdit): Uri = withContext(Dispatchers.Default) {
    val src = decodePhoto(context, uri, 4096)
    val geo = applyGeometry(src, edit)
    val out = applyPipeline(geo, ColorPipeline(edit.filter, edit.intensity, edit.adjustments))
    val canvas = Canvas(out)
    val w = out.width
    val h = out.height
    if (edit.frameArgb ushr 24 != 0L) {
        val fw = edit.frameWidth * minOf(w, h)
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = fw
            color = edit.frameArgb.toInt()
        }
        canvas.drawRect(fw / 2f, fw / 2f, w - fw / 2f, h - fw / 2f, paint)
    }
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    edit.texts.forEach { layer ->
        val bmp = LayerRenderer.renderText(layer, layer.text, w, h)
        canvas.save()
        canvas.translate(w / 2f + layer.x * w / 2f, h / 2f + layer.y * h / 2f)
        canvas.rotate(layer.rotation)
        canvas.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, paint)
        canvas.restore()
    }
    withContext(Dispatchers.IO) {
        Gallery.saveImage(context, out, "foto_${System.currentTimeMillis()}.jpg")
    }
}
