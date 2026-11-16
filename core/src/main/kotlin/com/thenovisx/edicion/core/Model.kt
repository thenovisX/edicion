package com.thenovisx.edicion.core

import kotlinx.serialization.Serializable

/** Relación de aspecto del lienzo del proyecto. */
@Serializable
enum class AspectRatio(val width: Int, val height: Int, val label: String) {
    VERTICAL_9_16(9, 16, "9:16"),
    HORIZONTAL_16_9(16, 9, "16:9"),
    SQUARE_1_1(1, 1, "1:1"),
    PORTRAIT_4_5(4, 5, "4:5"),
    PORTRAIT_3_4(3, 4, "3:4");

    val ratio: Float get() = width.toFloat() / height

    /** Tamaño de salida (ancho, alto) para una resolución dada por su lado corto (720, 1080, 2160). */
    fun outputSize(shortSide: Int): Pair<Int, Int> {
        val w: Int
        val h: Int
        if (width <= height) {
            w = shortSide
            h = (shortSide.toLong() * height / width).toInt()
        } else {
            h = shortSide
            w = (shortSide.toLong() * width / height).toInt()
        }
        // Los codificadores de vídeo prefieren dimensiones pares.
        return (w and 1.inv()) to (h and 1.inv())
    }
}

@Serializable
enum class MediaKind { VIDEO, IMAGE }

/** Filtros de color propios de la app. */
@Serializable
enum class FilterPreset(val label: String) {
    NONE("Original"),
    MONO("Mono"),
    SEPIA("Sepia"),
    WARM("Cálido"),
    COOL("Frío"),
    VIVID("Vívido"),
    FADE("Mate"),
    VINTAGE("Retro"),
    NIGHT("Noche"),
    DRAMA("Drama"),
    INVERT("Negativo"),
}

/**
 * Ajustes de color normalizados. Todos los valores van de -1 a 1 y 0 es neutro.
 */
@Serializable
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val hue: Float = 0f,
) {
    val isNeutral: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f &&
            warmth == 0f && tint == 0f && hue == 0f
}

/** Clip de la pista principal (vídeo o foto). Los tiempos están en milisegundos. */
@Serializable
data class Clip(
    val id: String,
    val uri: String,
    val kind: MediaKind,
    val mimeType: String? = null,
    val name: String = "",
    /** Duración del archivo original. Para fotos es la duración máxima permitida. */
    val sourceDurationMs: Long,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = sourceDurationMs,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val filter: FilterPreset = FilterPreset.NONE,
    val filterIntensity: Float = 1f,
    val adjustments: Adjustments = Adjustments(),
    /** Rotación en grados (múltiplos de 90). */
    val rotation: Int = 0,
    val flipHorizontal: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** Duración del fragmento recortado, sin aplicar velocidad. */
    val trimmedDurationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0)

    /** Duración que ocupa en la línea de tiempo (aplicando la velocidad). */
    val durationMs: Long
        get() = if (kind == MediaKind.IMAGE) trimmedDurationMs
        else (trimmedDurationMs / speed.toDouble()).toLong()

    val effectiveVolume: Float get() = if (muted) 0f else volume
}

/** Clip de la pista de audio (música, voz en off…). */
@Serializable
data class AudioClip(
    val id: String,
    val uri: String,
    val name: String = "",
    val sourceDurationMs: Long,
    val trimStartMs: Long = 0,
    val trimEndMs: Long = sourceDurationMs,
    /** Posición de inicio en la línea de tiempo. */
    val startMs: Long = 0,
    val volume: Float = 1f,
) {
    val durationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0)
    val endMs: Long get() = startMs + durationMs
}

/** Capa de texto superpuesta al vídeo. */
@Serializable
data class TextLayer(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** Posición del centro del texto en coordenadas normalizadas (-1..1, 0 = centro). */
    val x: Float = 0f,
    val y: Float = 0f,
    /** Tamaño relativo: 1 = tamaño estándar. */
    val scale: Float = 1f,
    val colorArgb: Long = 0xFFFFFFFF,
    val backgroundArgb: Long = 0x00000000,
    val bold: Boolean = true,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
    fun isVisibleAt(timeMs: Long) = timeMs in startMs until endMs
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val aspectRatio: AspectRatio = AspectRatio.VERTICAL_9_16,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val clips: List<Clip> = emptyList(),
    val audio: List<AudioClip> = emptyList(),
    val texts: List<TextLayer> = emptyList(),
    /** Ruta del último archivo exportado, si existe. */
    val lastExportUri: String? = null,
) {
    /** Duración total del vídeo (determinada por la pista principal). */
    val durationMs: Long get() = clips.sumOf { it.durationMs }

    val thumbnailUri: String? get() = clips.firstOrNull()?.uri
}

const val DEFAULT_IMAGE_DURATION_MS = 3_000L
const val MAX_IMAGE_DURATION_MS = 60_000L
const val MIN_CLIP_DURATION_MS = 100L
