package com.thenovisx.edicion.ui.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenovisx.edicion.media.Gallery
import com.thenovisx.edicion.ui.common.ColorChoice
import com.thenovisx.edicion.ui.common.LabeledSlider
import com.thenovisx.edicion.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Parámetros del collage. */
private data class CollageSpec(
    /** Cuántas fotos hay en cada fila (o columna, si [columns] es true). */
    val rows: List<Int>,
    val columns: Boolean = false,
    val ratio: Float = 1f,
    val gap: Float = 0.02f,
    val radius: Float = 0f,
    val background: Long = 0xFFFFFFFF,
)

/** Distribuciones posibles para [n] fotos: cada una reparte las fotos en filas. */
private fun layoutsFor(n: Int): List<List<Int>> = when (n) {
    0, 1 -> listOf(listOf(1))
    2 -> listOf(listOf(1, 1), listOf(2))
    3 -> listOf(listOf(1, 2), listOf(2, 1), listOf(1, 1, 1), listOf(3))
    4 -> listOf(listOf(2, 2), listOf(1, 3), listOf(3, 1), listOf(1, 2, 1))
    5 -> listOf(listOf(2, 3), listOf(3, 2), listOf(1, 2, 2), listOf(2, 1, 2))
    6 -> listOf(listOf(3, 3), listOf(2, 2, 2), listOf(1, 2, 3), listOf(3, 2, 1))
    7 -> listOf(listOf(2, 3, 2), listOf(3, 4), listOf(1, 3, 3))
    8 -> listOf(listOf(3, 2, 3), listOf(4, 4), listOf(2, 3, 3))
    else -> listOf(listOf(3, 3, 3), listOf(2, 3, 4), listOf(4, 5))
}

private val CollageRatios = listOf("1:1" to 1f, "4:5" to 0.8f, "3:4" to 0.75f, "9:16" to 9f / 16f, "16:9" to 16f / 9f)
private val CollageBackgrounds = listOf<Long>(0xFFFFFFFF, 0xFF000000, 0xFF1D1F2C, 0xFFFFE9D6, 0xFFFF6B5E, 0xFF7A5CFF, 0xFF2EC4B6)

/** Collage de fotos: se eligen la distribución, el formato, la separación y el fondo. */
@Composable
fun CollageScreen(uris: List<Uri>, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layouts = remember(uris.size) { layoutsFor(uris.size) }
    var spec by remember { mutableStateOf(CollageSpec(layouts.first())) }
    var saving by remember { mutableStateOf(false) }

    val photos by produceState<List<Bitmap>?>(null, uris) {
        value = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { decodePhoto(context, it, 1400) }.getOrNull() } }
    }
    val preview by produceState<Bitmap?>(null, photos, spec) {
        val list = photos
        if (!list.isNullOrEmpty()) value = withContext(Dispatchers.Default) { renderCollage(list, spec, 900) }
    }

    Column(
        Modifier.fillMaxSize().background(Palette.Background).statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Text("Collage", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    val list = photos ?: return@Button
                    saving = true
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.Default) {
                                val out = renderCollage(list, spec, 2400)
                                withContext(Dispatchers.IO) { Gallery.saveImage(context, out, "collage_${System.currentTimeMillis()}.jpg") }
                            }
                        }
                        saving = false
                        Toast.makeText(
                            context,
                            if (result.isSuccess) "Collage guardado en Pictures/Edicion" else "No se pudo guardar el collage",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                enabled = !photos.isNullOrEmpty() && !saving,
                modifier = Modifier.padding(end = 8.dp),
            ) { Text("Guardar") }
        }

        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
            val bmp = preview
            if (bmp != null && !saving) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp))
            } else {
                CircularProgressIndicator()
            }
        }

        Column(Modifier.fillMaxWidth().background(Palette.Surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                layouts.forEach { l ->
                    FilterChip(
                        selected = spec.rows == l && !spec.columns,
                        onClick = { spec = spec.copy(rows = l, columns = false) },
                        label = { Text("Filas " + l.joinToString("+")) },
                    )
                    FilterChip(
                        selected = spec.rows == l && spec.columns,
                        onClick = { spec = spec.copy(rows = l, columns = true) },
                        label = { Text("Columnas " + l.joinToString("+")) },
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CollageRatios.forEach { (label, r) ->
                    FilterChip(selected = spec.ratio == r, onClick = { spec = spec.copy(ratio = r) }, label = { Text(label) })
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Fondo", fontSize = 12.sp)
                CollageBackgrounds.forEach { c ->
                    ColorChoice(c, selected = spec.background == c) { spec = spec.copy(background = c) }
                }
            }
            LabeledSlider(
                label = "Separación",
                valueText = "${(spec.gap * 1000).roundToInt()}",
                value = spec.gap,
                range = 0f..0.08f,
                onChange = { spec = spec.copy(gap = it) },
                onDone = {},
            )
            LabeledSlider(
                label = "Esquinas redondeadas",
                valueText = "${(spec.radius * 1000).roundToInt()}",
                value = spec.radius,
                range = 0f..0.1f,
                onChange = { spec = spec.copy(radius = it) },
                onDone = {},
            )
        }
    }
}

internal fun decodePhoto(context: Context, uri: Uri, maxSide: Int): Bitmap {
    val src = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        val s = info.size
        val scale = maxSide.toFloat() / maxOf(s.width, s.height)
        if (scale < 1f) decoder.setTargetSize((s.width * scale).roundToInt().coerceAtLeast(1), (s.height * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
    }
}

/** Dibuja el collage. [longSide] es el lado largo de la imagen resultante en píxeles. */
private fun renderCollage(photos: List<Bitmap>, spec: CollageSpec, longSide: Int): Bitmap {
    val w = if (spec.ratio >= 1f) longSide else (longSide * spec.ratio).roundToInt()
    val h = if (spec.ratio >= 1f) (longSide / spec.ratio).roundToInt() else longSide
    val out = Bitmap.createBitmap(w.coerceAtLeast(2), h.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(spec.background.toInt())
    val gap = spec.gap * minOf(w, h)
    val radius = spec.radius * minOf(w, h)
    // Las filas se reparten el alto (o el ancho, en columnas) a partes iguales.
    val groups = spec.rows
    val mainSize = if (spec.columns) w.toFloat() else h.toFloat()
    val crossSize = if (spec.columns) h.toFloat() else w.toFloat()
    val groupSize = (mainSize - gap * (groups.size + 1)) / groups.size
    var index = 0
    groups.forEachIndexed { g, count ->
        val cellSize = (crossSize - gap * (count + 1)) / count
        for (c in 0 until count) {
            val photo = photos.getOrNull(index++) ?: return@forEachIndexed
            val main = gap + g * (groupSize + gap)
            val cross = gap + c * (cellSize + gap)
            val rect = if (spec.columns) RectF(main, cross, main + groupSize, cross + cellSize)
            else RectF(cross, main, cross + cellSize, main + groupSize)
            drawCropped(canvas, photo, rect, radius)
        }
    }
    return out
}

/** Dibuja [photo] recortada al centro para llenar [rect], con esquinas redondeadas. */
private fun drawCropped(canvas: Canvas, photo: Bitmap, rect: RectF, radius: Float) {
    if (rect.width() <= 0 || rect.height() <= 0) return
    val scale = maxOf(rect.width() / photo.width, rect.height() / photo.height)
    val m = Matrix().apply {
        setScale(scale, scale)
        postTranslate(rect.centerX() - photo.width * scale / 2f, rect.centerY() - photo.height * scale / 2f)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { it.setLocalMatrix(m) }
    }
    canvas.drawRoundRect(rect, radius, radius, paint)
}
