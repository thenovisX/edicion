package com.thenovisx.edicion.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import com.thenovisx.edicion.core.LayerMath
import com.thenovisx.edicion.core.MEDIA_BASE_WIDTH
import com.thenovisx.edicion.core.TEXT_BASE_HEIGHT
import com.thenovisx.edicion.core.TextFont
import com.thenovisx.edicion.core.TextLayer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Dibuja el contenido de las capas. Lo usan la exportación y la vista previa. */
object LayerRenderer {

    /** Texto o sticker como imagen, para un lienzo de [outW] x [outH] píxeles. */
    fun renderText(layer: TextLayer, text: String, outW: Int, outH: Int): Bitmap {
        val size = max(4f, outH * TEXT_BASE_HEIGHT * layer.scale)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = layer.colorArgb.toInt()
            typeface = typefaceFor(layer.font, layer.bold, layer.italic)
            if (layer.shadow) setShadowLayer(size * 0.12f, 0f, size * 0.06f, 0xCC000000.toInt())
        }
        val hasOutline = layer.outlineArgb ushr 24 != 0L
        val stroke = if (hasOutline) size * 0.14f else 0f
        val pad = (size * 0.3f + stroke).roundToInt()
        val content = text.ifEmpty { " " }
        val maxWidth = ((outW * 0.92f).toInt() - pad * 2).coerceAtLeast(8)
        val wanted = content.lines().maxOf { ceil(paint.measureText(it)).toInt() } + 2
        val width = minOf(wanted, maxWidth).coerceAtLeast(1)
        fun layout(p: TextPaint) = StaticLayout.Builder.obtain(content, 0, content.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
            .build()

        val main = layout(paint)
        val bmp = Bitmap.createBitmap(width + pad * 2, main.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (layer.backgroundArgb ushr 24 != 0L) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = layer.backgroundArgb.toInt() }
            val r = size * 0.25f
            canvas.drawRoundRect(RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()), r, r, bg)
        }
        canvas.translate(pad.toFloat(), pad.toFloat())
        if (hasOutline) {
            val outline = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeJoin = Paint.Join.ROUND
                color = layer.outlineArgb.toInt()
                clearShadowLayer()
            }
            layout(outline).draw(canvas)
        }
        main.draw(canvas)
        return bmp
    }

    /** Ancho en píxeles de una capa de imagen o vídeo en un lienzo de ancho [outW]. */
    fun mediaWidth(layer: TextLayer, outW: Int): Int =
        (outW * MEDIA_BASE_WIDTH * layer.scale).roundToInt().coerceIn(4, outW * 3)

    fun mediaHeight(layer: TextLayer, outW: Int): Int =
        (mediaWidth(layer, outW) / layer.aspect.coerceAtLeast(0.05f)).roundToInt().coerceAtLeast(4)

    /** Carga una imagen escalada a [targetW] píxeles de ancho. */
    fun loadImage(context: Context, uri: String, targetW: Int): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val s = info.size
            val h = (targetW.toFloat() * s.height / s.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
            decoder.setTargetSize(targetW.coerceAtLeast(1), h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    fun typefaceFor(font: TextFont, bold: Boolean, italic: Boolean): Typeface {
        val family = when (font) {
            TextFont.SANS -> "sans-serif"
            TextFont.SERIF -> "serif"
            TextFont.MONO -> "monospace"
            TextFont.CONDENSED -> "sans-serif-condensed"
            TextFont.CURSIVE -> "cursive"
            TextFont.ROUNDED -> "casual"
        }
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(family, style)
    }
}

/**
 * Capa (texto, sticker, imagen o vídeo) dibujada sobre el vídeo al exportar, con su tiempo
 * de aparición, opacidad, rotación y animaciones.
 */
class LayerOverlay(
    private val context: Context,
    private val layer: TextLayer,
    private val outW: Int,
    private val outH: Int,
) : BitmapOverlay() {

    private var fullBitmap: Bitmap? = null
    private var typedBitmap: Bitmap? = null
    private var typedChars = -1
    private val blank: Bitmap by lazy { Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888) }

    // Solo para capas de vídeo.
    private var retriever: MediaMetadataRetriever? = null
    private var frame: Bitmap? = null
    private var frameIndex = -1L

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val tMs = presentationTimeUs / 1000
        if (layer.isVideo) return if (layer.isVisibleAt(tMs)) videoFrame(tMs) else blank
        LayerMath.typewriterChars(layer, tMs)?.let { chars ->
            if (chars != typedChars || typedBitmap == null) {
                typedBitmap = LayerRenderer.renderText(layer, layer.text.substring(0, chars), outW, outH)
                typedChars = chars
            }
            return typedBitmap!!
        }
        return fullBitmap ?: runCatching {
            if (layer.isImage) LayerRenderer.loadImage(context, layer.imageUri!!, LayerRenderer.mediaWidth(layer, outW))
            else LayerRenderer.renderText(layer, layer.text, outW, outH)
        }.getOrDefault(blank).also { fullBitmap = it }
    }

    /**
     * Fotograma de la capa de vídeo para el instante [tMs]. Se extrae con MediaMetadataRetriever:
     * es más lento que un decodificador dedicado, pero funciona con cualquier vídeo.
     */
    private fun videoFrame(tMs: Long): Bitmap {
        val posMs = LayerMath.videoPositionMs(layer, tMs)
        val index = posMs / FRAME_STEP_MS
        frame?.let { if (index == frameIndex) return it }
        val bmp = runCatching {
            val r = retriever ?: MediaMetadataRetriever().also {
                it.setDataSource(context, Uri.parse(layer.videoUri))
                retriever = it
            }
            val w = LayerRenderer.mediaWidth(layer, outW)
            val h = LayerRenderer.mediaHeight(layer, outW)
            r.getScaledFrameAtTime(index * FRAME_STEP_MS * 1000, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                ?.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
        }.getOrNull() ?: return frame ?: blank
        frame = bmp
        frameIndex = index
        return bmp
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val st = LayerMath.stateAt(layer, presentationTimeUs / 1000)
        return OverlaySettings.Builder()
            // En el modelo la y positiva es hacia abajo; en NDC es hacia arriba.
            .setBackgroundFrameAnchor((layer.x + st.dx).coerceIn(-1f, 1f), (-(layer.y + st.dy)).coerceIn(-1f, 1f))
            .setScale(st.scale, st.scale)
            .setRotationDegrees(-layer.rotation)
            .setAlphaScale(st.alpha)
            .build()
    }

    override fun release() {
        super.release()
        runCatching { retriever?.release() }
        retriever = null
    }

    private companion object {
        /** Las capas de vídeo se exportan a 30 fotogramas por segundo. */
        const val FRAME_STEP_MS = 33L
    }
}
