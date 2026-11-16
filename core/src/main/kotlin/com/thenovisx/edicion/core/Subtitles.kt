package com.thenovisx.edicion.core

/** Una línea de subtítulo con sus tiempos. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** Creación de subtítulos a partir de texto escrito o de un archivo SRT. */
object Subtitles {

    private val timeRegex = Regex("""(\d+):(\d+):(\d+)[,.](\d+)\s*-->\s*(\d+):(\d+):(\d+)[,.](\d+)""")

    /** Lee subtítulos en formato SRT (también acepta el cuerpo de un VTT). */
    fun parseSrt(content: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val blocks = content.replace("\r", "").split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val ti = lines.indexOfFirst { timeRegex.containsMatchIn(it) }
            if (ti < 0) continue
            val m = timeRegex.find(lines[ti])!!.groupValues.drop(1)
            fun ms(o: Int) = m[o].toLong() * 3_600_000 + m[o + 1].toLong() * 60_000 + m[o + 2].toLong() * 1000 +
                m[o + 3].padEnd(3, '0').take(3).toLong()
            val text = lines.drop(ti + 1).joinToString("\n") { it.replace(Regex("<[^>]+>"), "") }.trim()
            val start = ms(0)
            val end = ms(4)
            if (text.isNotEmpty() && end > start) cues += Cue(start, end, text)
        }
        return cues.sortedBy { it.startMs }
    }

    /**
     * Reparte las líneas escritas a mano entre [startMs] y [endMs]: cada línea dura en
     * proporción a su longitud (leer una frase larga lleva más tiempo).
     */
    fun fromLines(text: String, startMs: Long, endMs: Long): List<Cue> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || endMs <= startMs) return emptyList()
        // Tiempo cómodo de lectura: una base fija más un poco por carácter.
        val wanted = lines.map { 900L + it.length * 65L }
        val available = endMs - startMs
        val factor = minOf(1.0, available.toDouble() / wanted.sum())
        var t = startMs
        return lines.mapIndexed { i, line ->
            val d = (wanted[i] * factor).toLong().coerceAtLeast(1)
            Cue(t, minOf(t + d, endMs), line).also { t += d }
        }.filter { it.endMs > it.startMs }
    }

    /** Escribe los subtítulos en formato SRT. */
    fun toSrt(cues: List<Cue>): String = cues.mapIndexed { i, c ->
        "${i + 1}\n${stamp(c.startMs)} --> ${stamp(c.endMs)}\n${c.text}\n"
    }.joinToString("\n")

    private fun stamp(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms % 1000)
    }
}
