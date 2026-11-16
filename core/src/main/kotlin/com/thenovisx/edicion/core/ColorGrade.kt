package com.thenovisx.edicion.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Transformación de color completa de un clip o una foto: filtro + ajustes.
 * Combina matrices (rápidas) con pasos no lineales (exposición, luces, sombras, curvas, HSL).
 * Trabaja con valores RGB en 0..1 con gamma, igual que el vídeo y las fotos.
 */
class ColorPipeline(filter: FilterPreset, intensity: Float, private val adj: Adjustments) {
    private val filterMatrix = ColorMath.filter(filter).withIntensity(intensity)
    private val filterLook: Adjustments? = ColorMath.filterLook(filter)?.let { scale(it, intensity) }?.takeUnless { it.isGradeNeutral }
    private val userMatrix = ColorMath.adjustments(adj)
    private val userGrade: Adjustments? = adj.takeUnless { it.isGradeNeutral }

    /** Matriz equivalente cuando no hay pasos no lineales. */
    val matrix: ColorMatrix3x4 = userMatrix.after(filterMatrix)

    /** true si basta con [matrix] para reproducir el color (sin tabla). */
    val isMatrixOnly: Boolean = filterLook == null && userGrade == null

    val isIdentity: Boolean get() = isMatrixOnly && matrix.isIdentity

    /** Transforma un color; [rgb] se modifica en el sitio. */
    fun apply(rgb: FloatArray) {
        mul(filterMatrix, rgb)
        filterLook?.let { grade(it, rgb) }
        mul(userMatrix, rgb)
        userGrade?.let { grade(it, rgb) }
        rgb[0] = rgb[0].coerceIn(0f, 1f)
        rgb[1] = rgb[1].coerceIn(0f, 1f)
        rgb[2] = rgb[2].coerceIn(0f, 1f)
    }

    /** Tabla de color 3D de lado [n] indexada como cube[r][g][b], en formato ARGB. */
    fun buildCube(n: Int = 33): Array<Array<IntArray>> {
        val rgb = FloatArray(3)
        return Array(n) { r ->
            Array(n) { g ->
                IntArray(n) { b ->
                    rgb[0] = r / (n - 1f); rgb[1] = g / (n - 1f); rgb[2] = b / (n - 1f)
                    apply(rgb)
                    pack(rgb)
                }
            }
        }
    }

    /**
     * Aplica el color, la viñeta y el grano a una imagen ARGB de [width] x [height].
     * Se usa en el editor de fotos.
     */
    fun applyToPixels(pixels: IntArray, width: Int, height: Int) {
        val rgb = FloatArray(3)
        val colorNeeded = !isIdentity
        val vig = adj.vignette
        val grain = adj.grain
        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        val maxD2 = cx * cx + cy * cy
        for (i in pixels.indices) {
            val p = pixels[i]
            rgb[0] = ((p shr 16) and 0xFF) / 255f
            rgb[1] = ((p shr 8) and 0xFF) / 255f
            rgb[2] = (p and 0xFF) / 255f
            if (colorNeeded) apply(rgb)
            if (vig > 0f && maxD2 > 0f) {
                val x = i % width - cx
                val y = i / width - cy
                val k = 1f - vig * vignetteAmount((x * x + y * y) / maxD2)
                rgb[0] *= k; rgb[1] *= k; rgb[2] *= k
            }
            if (grain > 0f) {
                val nz = (noise(i) - 0.5f) * 0.25f * grain
                rgb[0] = (rgb[0] + nz).coerceIn(0f, 1f)
                rgb[1] = (rgb[1] + nz).coerceIn(0f, 1f)
                rgb[2] = (rgb[2] + nz).coerceIn(0f, 1f)
            }
            pixels[i] = (p and 0xFF000000.toInt()) or (pack(rgb) and 0x00FFFFFF)
        }
    }

    private fun pack(rgb: FloatArray): Int =
        (0xFF shl 24) or ((rgb[0] * 255f + 0.5f).toInt() shl 16) or ((rgb[1] * 255f + 0.5f).toInt() shl 8) or
            (rgb[2] * 255f + 0.5f).toInt()

    private fun mul(m: ColorMatrix3x4, rgb: FloatArray) {
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        rgb[0] = m[0, 0] * r + m[0, 1] * g + m[0, 2] * b + m[0, 3]
        rgb[1] = m[1, 0] * r + m[1, 1] * g + m[1, 2] * b + m[1, 3]
        rgb[2] = m[2, 0] * r + m[2, 1] * g + m[2, 2] * b + m[2, 3]
    }

    companion object {
        /** Oscurecimiento de la viñeta según la distancia al centro (d2 = distancia² normalizada 0..1). */
        fun vignetteAmount(d2: Float): Float {
            val t = ((d2 - 0.25f) / 0.75f).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t) * 0.85f
        }

        /** Ruido determinista en 0..1 a partir de un índice. */
        fun noise(i: Int): Float {
            var x = i * 374761393 + 668265263
            x = (x xor (x ushr 13)) * 1274126177
            x = x xor (x ushr 16)
            return (x and 0xFFFF) / 65535f
        }

        fun scale(a: Adjustments, t: Float) = a.copy(
            exposure = a.exposure * t, highlights = a.highlights * t, shadows = a.shadows * t,
            vibrance = a.vibrance * t, fade = a.fade * t,
            curve = if (t < 0.5f) CurvePreset.NONE else a.curve,
            hslHue = a.hslHue.map { it * t }, hslSat = a.hslSat.map { it * t }, hslLum = a.hslLum.map { it * t },
        )

        private fun luma(rgb: FloatArray) = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]

        /** Pasos no lineales de [a] sobre [rgb]. */
        fun grade(a: Adjustments, rgb: FloatArray) {
            if (a.exposure != 0f) {
                val k = 2f.pow(a.exposure * 1.5f)
                rgb[0] *= k; rgb[1] *= k; rgb[2] *= k
            }
            if (a.highlights != 0f || a.shadows != 0f) {
                val l = luma(rgb).coerceIn(0f, 1f)
                // Las sombras afectan a los tonos oscuros y las luces a los claros, sin mover negro ni blanco puros.
                val sw = (1 - l) * (1 - l) * min(1f, l * 6f)
                val hw = l * l * min(1f, (1 - l) * 6f)
                val d = a.shadows * 0.35f * sw + a.highlights * 0.35f * hw
                rgb[0] += d; rgb[1] += d; rgb[2] += d
            }
            if (a.vibrance != 0f) {
                val mx = max(rgb[0], max(rgb[1], rgb[2]))
                val mn = min(rgb[0], min(rgb[1], rgb[2]))
                // Sube más la saturación de los colores apagados que la de los ya vivos.
                val k = 1f + a.vibrance * (1f - (mx - mn).coerceIn(0f, 1f))
                val l = luma(rgb)
                rgb[0] = l + (rgb[0] - l) * k; rgb[1] = l + (rgb[1] - l) * k; rgb[2] = l + (rgb[2] - l) * k
            }
            if (a.fade > 0f) {
                val lift = 0.12f * a.fade
                val k = 1f - 0.2f * a.fade
                rgb[0] = rgb[0] * k + lift; rgb[1] = rgb[1] * k + lift; rgb[2] = rgb[2] * k + lift
            }
            if (a.curve != CurvePreset.NONE) {
                rgb[0] = curve(a.curve, rgb[0], 0)
                rgb[1] = curve(a.curve, rgb[1], 1)
                rgb[2] = curve(a.curve, rgb[2], 2)
            }
            if (a.hslHue.any { it != 0f } || a.hslSat.any { it != 0f } || a.hslLum.any { it != 0f }) hsl(a, rgb)
        }

        private fun sCurve(x: Float, k: Float): Float {
            // Mezcla entre la recta y una curva en S suave.
            val s = x * x * (3 - 2 * x)
            return x + (s - x) * k
        }

        fun curve(preset: CurvePreset, v: Float, channel: Int): Float {
            val x = v.coerceIn(0f, 1f)
            return when (preset) {
                CurvePreset.NONE -> x
                CurvePreset.SOFT_S -> sCurve(x, 0.5f)
                CurvePreset.STRONG_S -> sCurve(sCurve(x, 1f), 0.6f)
                CurvePreset.MATTE -> 0.08f + sCurve(x, 0.4f) * 0.88f
                CurvePreset.BRIGHT_MIDS -> x.pow(0.8f)
                CurvePreset.DARK_MIDS -> x.pow(1.25f)
                CurvePreset.CROSS -> when (channel) {
                    0 -> sCurve(x, 0.8f)
                    1 -> x.pow(0.92f)
                    else -> 0.06f + x * 0.86f
                }
            }
        }

        private fun hsl(a: Adjustments, rgb: FloatArray) {
            val r = rgb[0].coerceIn(0f, 1f); val g = rgb[1].coerceIn(0f, 1f); val b = rgb[2].coerceIn(0f, 1f)
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val d = mx - mn
            if (d < 1e-4f) return // Los grises no tienen tono.
            var h = when (mx) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            if (h < 0) h += 360f
            var s = if (mx <= 0f) 0f else d / mx
            var v = mx
            var dh = 0f; var ds = 0f; var dl = 0f
            for (band in HslBand.entries) {
                var dist = abs(h - band.hueDeg)
                if (dist > 180f) dist = 360f - dist
                val w = (1f - dist / 45f).coerceAtLeast(0f)
                if (w <= 0f) continue
                dh += w * a.hsl(a.hslHue, band)
                ds += w * a.hsl(a.hslSat, band)
                dl += w * a.hsl(a.hslLum, band)
            }
            if (dh == 0f && ds == 0f && dl == 0f) return
            // El efecto se atenúa en colores poco saturados para no teñir los grises.
            val strength = (s * 3f).coerceAtMost(1f)
            h = ((h + dh * 40f * strength) % 360f + 360f) % 360f
            s = (s * (1f + ds * strength)).coerceIn(0f, 1f)
            v = (v * (1f + dl * 0.5f * strength)).coerceIn(0f, 1f)
            val c = v * s
            val x = c * (1 - abs((h / 60f) % 2f - 1f))
            val m = v - c
            val (r1, g1, b1) = when ((h / 60f).toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            rgb[0] = r1 + m; rgb[1] = g1 + m; rgb[2] = b1 + m
        }
    }
}
