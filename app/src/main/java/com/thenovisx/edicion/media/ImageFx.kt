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
}
