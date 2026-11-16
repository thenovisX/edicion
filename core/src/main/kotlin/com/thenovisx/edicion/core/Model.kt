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
    CINE("Cine"),
    FILM("Película"),
    GOLDEN("Dorado"),
    PASTEL("Pastel"),
    NOIR("Noir"),
    SUMMER("Verano"),
    WINTER("Invierno"),
    NEON("Neón"),
    NOSTALGIA("Nostalgia"),
    FOREST("Bosque"),
    SUNSET("Atardecer"),
    CLEAN("Limpio"),
}

/** Curvas de tono predefinidas. */
@Serializable
enum class CurvePreset(val label: String) {
    NONE("Lineal"),
    SOFT_S("Contraste suave"),
    STRONG_S("Contraste fuerte"),
    MATTE("Mate"),
    BRIGHT_MIDS("Medios claros"),
    DARK_MIDS("Medios oscuros"),
    CROSS("Proceso cruzado"),
}

/** Bandas de color para el ajuste HSL. El tono central de cada una está en grados. */
enum class HslBand(val label: String, val hueDeg: Float) {
    RED("Rojo", 0f),
    ORANGE("Naranja", 30f),
    YELLOW("Amarillo", 60f),
    GREEN("Verde", 120f),
    CYAN("Cian", 180f),
    BLUE("Azul", 230f),
    PURPLE("Morado", 275f),
    MAGENTA("Magenta", 320f),
}

/** Transición al inicio de un clip (desde el clip anterior, o desde negro si es el primero). */
@Serializable
enum class TransitionType(val label: String) {
    NONE("Ninguna"),
    FADE_BLACK("Fundido a negro"),
    FADE_WHITE("Fundido a blanco"),
}

/** Tipos de letra disponibles (familias incluidas en Android). */
@Serializable
enum class TextFont(val label: String) {
    SANS("Normal"),
    SERIF("Serif"),
    MONO("Mono"),
    CONDENSED("Condensada"),
    CURSIVE("Manuscrita"),
    ROUNDED("Casual"),
}

/** Animaciones de entrada y salida de una capa. */
@Serializable
enum class LayerAnim(val label: String) {
    NONE("Ninguna"),
    FADE("Fundido"),
    SLIDE_UP("Deslizar arriba"),
    SLIDE_DOWN("Deslizar abajo"),
    SLIDE_LEFT("Deslizar izquierda"),
    SLIDE_RIGHT("Deslizar derecha"),
    POP("Zoom"),
    TYPEWRITER("Máquina de escribir"),
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
    // Ajustes no lineales (se aplican con una tabla de color).
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val vibrance: Float = 0f,
    val fade: Float = 0f,
    val curve: CurvePreset = CurvePreset.NONE,
    /** Por banda de [HslBand]: desplazamiento de tono, saturación y luminosidad (-1..1). Vacío = sin cambios. */
    val hslHue: List<Float> = emptyList(),
    val hslSat: List<Float> = emptyList(),
    val hslLum: List<Float> = emptyList(),
    // Efectos de imagen (0..1).
    val vignette: Float = 0f,
    val grain: Float = 0f,
) {
    /** Sin cambios de color lineales (los que se resuelven con una matriz). */
    val isMatrixNeutral: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f &&
            warmth == 0f && tint == 0f && hue == 0f

    /** Sin cambios de color no lineales. */
    val isGradeNeutral: Boolean
        get() = exposure == 0f && highlights == 0f && shadows == 0f && vibrance == 0f && fade == 0f &&
            curve == CurvePreset.NONE && hslHue.all { it == 0f } && hslSat.all { it == 0f } && hslLum.all { it == 0f }

    val isNeutral: Boolean get() = isMatrixNeutral && isGradeNeutral && vignette == 0f && grain == 0f

    fun hsl(list: List<Float>, band: HslBand): Float = list.getOrElse(band.ordinal) { 0f }

    /** Devuelve una copia con el valor de una banda HSL cambiado. */
    fun withHsl(list: List<Float>, band: HslBand, value: Float): List<Float> =
        List(HslBand.entries.size) { i -> if (i == band.ordinal) value else list.getOrElse(i) { 0f } }
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
    val transition: TransitionType = TransitionType.NONE,
    val transitionMs: Long = DEFAULT_TRANSITION_MS,
    /** Encuadre dentro del lienzo: rellenar recortando, zoom y desplazamiento (-1..1). */
    val fill: Boolean = false,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val hasAudio: Boolean = true,
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
    /** Pista de audio: [LANE_MUSIC] o [LANE_VOICE]. Los clips de una misma pista no se solapan. */
    val lane: Int = LANE_MUSIC,
) {
    val durationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0)
    val endMs: Long get() = startMs + durationMs
}

/**
 * Capa superpuesta al vídeo: texto, sticker (emoji) o imagen (si [imageUri] no es nulo).
 */
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
    val italic: Boolean = false,
    val font: TextFont = TextFont.SANS,
    /** Color del contorno; alfa 0 = sin contorno. */
    val outlineArgb: Long = 0x00000000,
    val shadow: Boolean = false,
    val opacity: Float = 1f,
    val rotation: Float = 0f,
    val animIn: LayerAnim = LayerAnim.NONE,
    val animOut: LayerAnim = LayerAnim.NONE,
    /** Si no es nulo, la capa es una imagen y [text] solo sirve de etiqueta. */
    val imageUri: String? = null,
    val isSticker: Boolean = false,
    /** Si no es nulo, la capa es un vídeo superpuesto. */
    val videoUri: String? = null,
    /** Solo vídeo: duración del archivo, inicio del fragmento usado y audio. */
    val sourceDurationMs: Long = 0,
    val trimStartMs: Long = 0,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val hasAudio: Boolean = false,
    /** Imagen o vídeo: proporción ancho/alto del contenido. */
    val aspect: Float = 1f,
) {
    val isImage: Boolean get() = imageUri != null
    val isVideo: Boolean get() = videoUri != null
    /** Capa de imagen o vídeo (frente a texto o sticker). */
    val isMedia: Boolean get() = isImage || isVideo
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
const val DEFAULT_TRANSITION_MS = 600L
const val MAX_TRANSITION_MS = 2_000L
const val LAYER_ANIM_MS = 400L
const val LANE_MUSIC = 0
const val LANE_VOICE = 1
/** Las capas de vídeo con sonido usan pistas de audio internas a partir de este número. */
const val LANE_LAYER_BASE = 100
/** Tamaño base de texto (fracción del alto) y de imagen/vídeo (fracción del ancho) con escala 1. */
const val TEXT_BASE_HEIGHT = 0.045f
const val MEDIA_BASE_WIDTH = 0.4f
