package com.thenovisx.edicion.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * Matriz de color 3x4 (filas R, G, B; columnas R, G, B y desplazamiento) con valores
 * normalizados (0..1). La comparten el editor de vídeo (Media3) y el de fotos (Android ColorMatrix),
 * así un filtro se ve igual en ambos.
 */
class ColorMatrix3x4(val m: FloatArray = identity()) {
    init {
        require(m.size == 12)
    }

    operator fun get(row: Int, col: Int): Float = m[row * 4 + col]

    /** Devuelve this ∘ other: primero se aplica [other] y después this. */
    fun after(other: ColorMatrix3x4): ColorMatrix3x4 {
        val r = FloatArray(12)
        for (row in 0 until 3) {
            for (col in 0 until 4) {
                var v = 0f
                for (k in 0 until 3) v += this[row, k] * other[k, col]
                if (col == 3) v += this[row, 3]
                r[row * 4 + col] = v
            }
        }
        return ColorMatrix3x4(r)
    }

    /** Mezcla lineal con la identidad: 0 = sin efecto, 1 = efecto completo. */
    fun withIntensity(t: Float): ColorMatrix3x4 {
        val id = identity()
        return ColorMatrix3x4(FloatArray(12) { i -> id[i] + (m[i] - id[i]) * t })
    }

    val isIdentity: Boolean get() = m.contentEquals(identity())

    /** Matriz 4x4 en orden por columnas, el formato que usa `RgbMatrix` de Media3. */
    fun toColumnMajor4x4(): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 3) out[col * 4 + row] = this[row, col]
            out[col * 4 + 3] = if (col == 3) 1f else 0f
        }
        return out
    }

    /** Matriz 4x5 por filas con desplazamientos en 0..255, el formato de `android.graphics.ColorMatrix`. */
    fun toAndroid4x5(): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 3) {
            for (col in 0 until 3) out[row * 5 + col] = this[row, col]
            out[row * 5 + 4] = this[row, 3] * 255f
        }
        out[18] = 1f
        return out
    }

    companion object {
        fun identity() = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
        )

        fun of(vararg v: Float) = ColorMatrix3x4(floatArrayOf(*v))
    }
}

object ColorMath {
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    fun saturation(s: Float): ColorMatrix3x4 {
        val i = 1 - s
        return ColorMatrix3x4.of(
            LR * i + s, LG * i, LB * i, 0f,
            LR * i, LG * i + s, LB * i, 0f,
            LR * i, LG * i, LB * i + s, 0f,
        )
    }

    fun contrast(c: Float): ColorMatrix3x4 {
        val o = 0.5f * (1 - c)
        return ColorMatrix3x4.of(c, 0f, 0f, o, 0f, c, 0f, o, 0f, 0f, c, o)
    }

    fun brightness(b: Float) = ColorMatrix3x4.of(1f, 0f, 0f, b, 0f, 1f, 0f, b, 0f, 0f, 1f, b)

    fun scale(r: Float, g: Float, b: Float) = ColorMatrix3x4.of(r, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, b, 0f)

    /** Rotación de tono en grados alrededor del eje de luminancia. */
    fun hue(degrees: Float): ColorMatrix3x4 {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        // Fórmula estándar de rotación de tono (W3C feColorMatrix hueRotate).
        return ColorMatrix3x4.of(
            0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f,
            0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f,
            0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f,
        )
    }

    fun filter(preset: FilterPreset): ColorMatrix3x4 = when (preset) {
        FilterPreset.NONE -> ColorMatrix3x4()
        FilterPreset.MONO -> saturation(0f)
        FilterPreset.SEPIA -> ColorMatrix3x4.of(
            0.393f, 0.769f, 0.189f, 0f,
            0.349f, 0.686f, 0.168f, 0f,
            0.272f, 0.534f, 0.131f, 0f,
        )
        FilterPreset.WARM -> scale(1.12f, 1.02f, 0.86f).after(saturation(1.1f))
        FilterPreset.COOL -> scale(0.88f, 1.0f, 1.14f)
        FilterPreset.VIVID -> contrast(1.12f).after(saturation(1.45f))
        FilterPreset.FADE -> ColorMatrix3x4.of(
            0.82f, 0f, 0f, 0.1f,
            0f, 0.82f, 0f, 0.1f,
            0f, 0f, 0.82f, 0.12f,
        ).after(saturation(0.8f))
        FilterPreset.VINTAGE -> scale(1.08f, 0.98f, 0.82f)
            .after(contrast(0.9f))
            .after(saturation(0.7f))
            .after(brightness(0.03f))
        FilterPreset.NIGHT -> scale(0.82f, 0.92f, 1.18f).after(contrast(1.1f)).after(brightness(-0.06f))
        FilterPreset.DRAMA -> contrast(1.35f).after(saturation(0.75f))
        FilterPreset.INVERT -> ColorMatrix3x4.of(-1f, 0f, 0f, 1f, 0f, -1f, 0f, 1f, 0f, 0f, -1f, 1f)
        FilterPreset.CINE -> saturation(0.9f)
        FilterPreset.FILM -> scale(1.04f, 1.0f, 0.94f).after(saturation(0.88f))
        FilterPreset.GOLDEN -> scale(1.1f, 1.02f, 0.82f)
        FilterPreset.PASTEL -> saturation(0.75f)
        FilterPreset.NOIR -> saturation(0f)
        FilterPreset.SUMMER -> scale(1.06f, 1.03f, 0.92f).after(saturation(1.15f))
        FilterPreset.WINTER -> scale(0.92f, 0.99f, 1.1f).after(saturation(0.85f))
        FilterPreset.NEON -> saturation(1.5f)
        FilterPreset.NOSTALGIA -> scale(1.06f, 0.98f, 0.9f).after(saturation(0.7f))
        FilterPreset.FOREST -> scale(0.96f, 1.03f, 0.95f)
        FilterPreset.SUNSET -> scale(1.12f, 0.98f, 0.9f)
        FilterPreset.CLEAN -> ColorMatrix3x4()
    }

    private fun hslOf(vararg pairs: Pair<HslBand, Float>): List<Float> =
        List(HslBand.entries.size) { i -> pairs.firstOrNull { it.first.ordinal == i }?.second ?: 0f }

    /** Parte no lineal del aspecto de algunos filtros (curvas, luces, HSL). null si el filtro es solo una matriz. */
    fun filterLook(preset: FilterPreset): Adjustments? = when (preset) {
        FilterPreset.CINE -> Adjustments(
            curve = CurvePreset.SOFT_S, shadows = -0.15f,
            hslHue = hslOf(HslBand.BLUE to -0.3f, HslBand.GREEN to 0.5f, HslBand.YELLOW to -0.3f),
            hslSat = hslOf(HslBand.ORANGE to 0.3f, HslBand.CYAN to 0.3f),
        )
        FilterPreset.FILM -> Adjustments(curve = CurvePreset.MATTE, highlights = -0.25f, vibrance = 0.15f)
        FilterPreset.GOLDEN -> Adjustments(highlights = 0.2f, vibrance = 0.25f, curve = CurvePreset.SOFT_S)
        FilterPreset.PASTEL -> Adjustments(fade = 0.7f, shadows = 0.5f, exposure = 0.1f)
        FilterPreset.NOIR -> Adjustments(curve = CurvePreset.STRONG_S, shadows = -0.3f)
        FilterPreset.SUMMER -> Adjustments(vibrance = 0.3f, shadows = 0.2f, hslSat = hslOf(HslBand.BLUE to 0.3f, HslBand.CYAN to 0.3f))
        FilterPreset.WINTER -> Adjustments(highlights = 0.25f, fade = 0.2f)
        FilterPreset.NEON -> Adjustments(
            curve = CurvePreset.STRONG_S,
            hslSat = hslOf(HslBand.MAGENTA to 0.5f, HslBand.CYAN to 0.5f, HslBand.PURPLE to 0.5f),
        )
        FilterPreset.NOSTALGIA -> Adjustments(fade = 0.6f, curve = CurvePreset.CROSS, highlights = -0.2f)
        FilterPreset.FOREST -> Adjustments(
            shadows = -0.1f, curve = CurvePreset.SOFT_S,
            hslSat = hslOf(HslBand.GREEN to 0.4f, HslBand.YELLOW to -0.2f),
            hslHue = hslOf(HslBand.YELLOW to 0.4f),
        )
        FilterPreset.SUNSET -> Adjustments(
            vibrance = 0.3f, curve = CurvePreset.SOFT_S,
            hslSat = hslOf(HslBand.ORANGE to 0.4f, HslBand.RED to 0.3f),
            hslHue = hslOf(HslBand.BLUE to 0.4f),
        )
        FilterPreset.CLEAN -> Adjustments(exposure = 0.12f, shadows = 0.3f, highlights = -0.2f, vibrance = 0.25f)
        else -> null
    }

    fun adjustments(a: Adjustments): ColorMatrix3x4 {
        var m = ColorMatrix3x4()
        if (a.hue != 0f) m = hue(a.hue * 180f).after(m)
        if (a.saturation != 0f) m = saturation(1f + a.saturation).after(m)
        if (a.warmth != 0f || a.tint != 0f) {
            m = scale(1f + 0.2f * a.warmth, 1f - 0.15f * a.tint, 1f - 0.2f * a.warmth).after(m)
        }
        if (a.contrast != 0f) m = contrast(1f + a.contrast * (if (a.contrast > 0) 1f else 0.8f)).after(m)
        if (a.brightness != 0f) m = brightness(a.brightness * 0.4f).after(m)
        return m
    }

    /** Matriz final de un clip o foto: primero el filtro (con intensidad) y luego los ajustes. */
    fun combined(filter: FilterPreset, intensity: Float, adjustments: Adjustments): ColorMatrix3x4 =
        adjustments(adjustments).after(filter(filter).withIntensity(intensity))
}
