package com.thenovisx.edicion.media

import android.graphics.Bitmap
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import com.thenovisx.edicion.core.ColorPipeline

/** Viñeta y grano para vídeo, dibujados como superposiciones a baja resolución y ampliados. */
object ImageFx {
    private const val VIGNETTE_DOWNSCALE = 6
    private const val GRAIN_DOWNSCALE = 2
    private const val GRAIN_FRAMES = 3

    fun vignette(amount: Float, outW: Int, outH: Int): BitmapOverlay {
        val w = (outW / VIGNETTE_DOWNSCALE).coerceAtLeast(2)
        val h = (outH / VIGNETTE_DOWNSCALE).coerceAtLeast(2)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val maxD2 = cx * cx + cy * cy
        val px = IntArray(w * h) { i ->
            val x = i % w - cx
            val y = i / w - cy
            val a = (amount * ColorPipeline.vignetteAmount((x * x + y * y) / maxD2) * 255f).toInt().coerceIn(0, 255)
            a shl 24 // Negro con alfa variable.
        }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val settings = OverlaySettings.Builder().setScale(outW.toFloat() / w, outH.toFloat() / h).build()
        return BitmapOverlay.createStaticBitmapOverlay(bmp, settings)
    }

    fun grain(amount: Float, outW: Int, outH: Int): BitmapOverlay {
        val w = (outW / GRAIN_DOWNSCALE).coerceAtLeast(2)
        val h = (outH / GRAIN_DOWNSCALE).coerceAtLeast(2)
        val frames = List(GRAIN_FRAMES) { f ->
            val px = IntArray(w * h) { i ->
                val n = ColorPipeline.noise(i + f * 7_919_113) - 0.5f
                val a = (kotlin.math.abs(n) * 2f * amount * 90f).toInt().coerceIn(0, 255)
                (a shl 24) or (if (n > 0) 0xFFFFFF else 0)
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
        val settings = OverlaySettings.Builder().setScale(outW.toFloat() / w, outH.toFloat() / h).build()
        return object : BitmapOverlay() {
            // El grano cambia unas 12 veces por segundo para que parezca vivo.
            override fun getBitmap(presentationTimeUs: Long): Bitmap =
                frames[((presentationTimeUs / 80_000) % GRAIN_FRAMES).toInt().coerceIn(0, GRAIN_FRAMES - 1)]

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
        }
    }

    /** Radio de desenfoque en píxeles para una imagen cuyo lado corto mide [shortSide]. */
    fun blurRadius(amount: Float, shortSide: Int): Float = amount.coerceIn(0f, 1f) * shortSide * 0.03f

    /**
     * Desenfoque de una imagen en memoria: tres pasadas de media móvil en cada dirección,
     * que se parecen mucho a un desenfoque gaussiano y cuestan lo mismo con cualquier radio.
     */
    fun blurPixels(pixels: IntArray, w: Int, h: Int, radius: Int) {
        if (radius < 1 || w < 2 || h < 2) return
        val tmp = IntArray(pixels.size)
        repeat(3) {
            boxPass(pixels, tmp, w, h, radius, horizontal = true)
            boxPass(tmp, pixels, w, h, radius, horizontal = false)
        }
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val stride = if (horizontal) 1 else w
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var r = 0
            var g = 0
            var b = 0
            // Ventana inicial, repitiendo el borde.
            for (k in -radius..radius) {
                val p = src[base + k.coerceIn(0, len - 1) * stride]
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until len) {
                val idx = base + i * stride
                dst[idx] = (src[idx] and 0xFF000000.toInt()) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val out = src[base + (i - radius).coerceAtLeast(0) * stride]
                val inn = src[base + (i + radius + 1).coerceAtMost(len - 1) * stride]
                r += (inn shr 16 and 0xFF) - (out shr 16 and 0xFF)
                g += (inn shr 8 and 0xFF) - (out shr 8 and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
