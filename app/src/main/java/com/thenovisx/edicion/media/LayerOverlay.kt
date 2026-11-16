package com.thenovisx.edicion.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import com.thenovisx.edicion.core.LAYER_ANIM_MS
import com.thenovisx.edicion.core.LayerAnim
import com.thenovisx.edicion.core.TextFont
import com.thenovisx.edicion.core.TextLayer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dibuja una capa (texto, sticker o imagen) sobre el vídeo, con su tiempo de aparición,
 * opacidad, rotación y animaciones de entrada y salida.
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

    private val animMs: Long = minOf(LAYER_ANIM_MS, layer.durationMs / 2).coerceAtLeast(1)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val tMs = presentationTimeUs / 1000
        if (!layer.isImage && layer.animIn == LayerAnim.TYPEWRITER) {
            val total = layer.text.length
            // La escritura ocupa como mucho la primera mitad de la duración del texto.
            val typeMs = minOf(total * 60L, layer.durationMs / 2).coerceAtLeast(1)
            val chars = (((tMs - layer.startMs).toFloat() / typeMs) * total).toInt().coerceIn(0, total)
            if (chars < total) {
                if (chars != typedChars || typedBitmap == null) {
                    typedBitmap = renderText(layer.text.substring(0, chars))
                    typedChars = chars
                }
                return typedBitmap!!
            }
        }
        return fullBitmap ?: (if (layer.isImage) loadImage() else renderText(layer.text)).also { fullBitmap = it }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val tMs = presentationTimeUs / 1000
        var alpha = if (layer.isVisibleAt(tMs)) layer.opacity else 0f
        var dx = 0f
        var dy = 0f
        var scale = 1f
        if (alpha > 0f) {
            // p va de 0 (inicio de la animación) a 1 (capa en reposo).
            val pIn = ((tMs - layer.startMs).toFloat() / animMs).coerceIn(0f, 1f)
            val pOut = ((layer.endMs - tMs).toFloat() / animMs).coerceIn(0f, 1f)
            for ((anim, p) in listOf(layer.animIn to pIn, layer.animOut to pOut)) {
                if (p >= 1f) continue
                val e = easeOut(p)
                when (anim) {
                    LayerAnim.FADE -> alpha *= p
                    LayerAnim.SLIDE_UP -> { dy -= (1 - e) * 0.25f; alpha *= p }
                    LayerAnim.SLIDE_DOWN -> { dy += (1 - e) * 0.25f; alpha *= p }
                    LayerAnim.SLIDE_LEFT -> { dx += (1 - e) * 0.25f; alpha *= p }
                    LayerAnim.SLIDE_RIGHT -> { dx -= (1 - e) * 0.25f; alpha *= p }
                    LayerAnim.POP -> { scale *= 0.4f + 0.6f * e; alpha *= p }
                    LayerAnim.TYPEWRITER, LayerAnim.NONE -> Unit
                }
            }
        }
        return OverlaySettings.Builder()
            // En el modelo la y positiva es hacia abajo; en NDC es hacia arriba.
            .setBackgroundFrameAnchor((layer.x + dx).coerceIn(-1.5f, 1.5f), (-(layer.y + dy)).coerceIn(-1.5f, 1.5f))
            .setScale(scale, scale)
            .setRotationDegrees(-layer.rotation)
            .setAlphaScale(alpha.coerceIn(0f, 1f))
            .build()
    }

    private fun easeOut(p: Float): Float = 1f - (1f - p) * (1f - p)

    // ---------- Dibujo ----------

    private fun renderText(text: String): Bitmap {
        val size = max(8f, outH * 0.045f * layer.scale)
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
        val maxWidth = (outW * 0.92f).toInt().coerceAtLeast(16) - pad * 2
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

    private fun loadImage(): Bitmap = runCatching {
        val targetW = (outW * 0.4f * layer.scale).roundToInt().coerceIn(8, outW * 2)
        val src = ImageDecoder.createSource(context.contentResolver, Uri.parse(layer.imageUri))
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val s = info.size
            val h = (targetW.toFloat() * s.height / s.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
            decoder.setTargetSize(targetW, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrElse {
        // Si la imagen ya no existe, la capa queda invisible en lugar de romper el vídeo.
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    }

    companion object {
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
}
