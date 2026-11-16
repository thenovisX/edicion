package com.thenovisx.edicion.ui.photo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
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
import com.thenovisx.edicion.core.ColorMath
import com.thenovisx.edicion.core.FilterPreset
import com.thenovisx.edicion.media.Gallery
import com.thenovisx.edicion.ui.common.FilterSwatch
import com.thenovisx.edicion.ui.common.colorFilterFor
import com.thenovisx.edicion.ui.editor.AdjustmentSliders
import com.thenovisx.edicion.ui.editor.LabeledSlider
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
}

/** Parámetros de edición de una foto (no destructivos hasta guardar). */
private data class PhotoEdit(
    val filter: FilterPreset = FilterPreset.NONE,
    val intensity: Float = 1f,
    val adjustments: Adjustments = Adjustments(),
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val crop: Float? = null,
)

@Composable
fun PhotoEditorScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf(PhotoEdit()) }
    var tool by remember { mutableStateOf(PhotoTool.FILTERS) }
    var saving by remember { mutableStateOf(false) }

    val source by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { decode(context, uri, 1600) }.getOrNull() }
    }
    // Geometría (girar, voltear, recortar) aplicada a la imagen de vista previa.
    val preview by produceState<Bitmap?>(null, source, edit.rotation, edit.flipH, edit.crop) {
        val src = source ?: return@produceState
        value = withContext(Dispatchers.Default) { applyGeometry(src, edit.rotation, edit.flipH, edit.crop) }
    }
    val colorFilter = remember(edit.filter, edit.intensity, edit.adjustments) {
        colorFilterFor(edit.filter, edit.intensity, edit.adjustments)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
            Text("Editar foto", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { edit = edit.copy(rotation = (edit.rotation + 90) % 360) }) {
                Icon(Icons.Default.RotateRight, "Girar")
            }
            IconButton(onClick = { edit = edit.copy(flipH = !edit.flipH) }) { Icon(Icons.Default.Flip, "Voltear") }
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

        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = preview
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = colorFilter,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
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
            Box(Modifier.fillMaxWidth().height(130.dp)) {
                when (tool) {
                    PhotoTool.FILTERS -> Column {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterPreset.entries.forEach { f ->
                                FilterSwatch(f, edit.filter == f) { edit = edit.copy(filter = f) }
                            }
                        }
                        if (edit.filter != FilterPreset.NONE) {
                            LabeledSlider(
                                label = "Intensidad",
                                valueText = "${(edit.intensity * 100).roundToInt()} %",
                                value = edit.intensity,
                                range = 0f..1f,
                                onChange = { edit = edit.copy(intensity = it) },
                                onDone = {},
                            )
                        }
                    }
                    PhotoTool.ADJUST -> AdjustmentSliders(
                        adjustments = edit.adjustments,
                        onChange = { edit = edit.copy(adjustments = it) },
                        onDone = {},
                        onReset = { edit = edit.copy(adjustments = Adjustments()) },
                    )
                    PhotoTool.CROP -> Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CropRatios.forEach { (label, r) ->
                            FilterChip(selected = edit.crop == r, onClick = { edit = edit.copy(crop = r) }, label = { Text(label) })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PhotoTool.entries.forEach { t ->
                    Column(
                        Modifier.width(80.dp).clickable { tool = t }.padding(6.dp),
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
}

private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
    val src = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        val s = info.size
        val scale = maxSide.toFloat() / maxOf(s.width, s.height)
        if (scale < 1f) decoder.setTargetSize((s.width * scale).roundToInt().coerceAtLeast(1), (s.height * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
    }
}

/** Gira, voltea y recorta al centro según la proporción pedida. */
private fun applyGeometry(src: Bitmap, rotation: Int, flipH: Boolean, crop: Float?): Bitmap {
    val m = Matrix()
    if (flipH) m.postScale(-1f, 1f)
    if (rotation != 0) m.postRotate(rotation.toFloat())
    var out = if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
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

/** Aplica todo a la imagen a alta resolución y la guarda en la galería. */
private suspend fun save(context: Context, uri: Uri, edit: PhotoEdit): Uri = withContext(Dispatchers.Default) {
    val src = decode(context, uri, 4096)
    val geo = applyGeometry(src, edit.rotation, edit.flipH, edit.crop)
    val out = Bitmap.createBitmap(geo.width, geo.height, Bitmap.Config.ARGB_8888)
    val matrix = ColorMath.combined(edit.filter, edit.intensity, edit.adjustments)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (!matrix.isIdentity) colorFilter = ColorMatrixColorFilter(matrix.toAndroid4x5())
    }
    Canvas(out).drawBitmap(geo, 0f, 0f, paint)
    withContext(Dispatchers.IO) {
        Gallery.saveImage(context, out, "foto_${System.currentTimeMillis()}.jpg")
    }
}
