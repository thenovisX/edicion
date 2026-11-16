package com.thenovisx.edicion.core

import kotlinx.serialization.json.Json

/** Serialización de proyectos. Ignora campos desconocidos para que los proyectos sean compatibles entre versiones. */
object ProjectJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)

    fun decode(text: String): Project = json.decodeFromString(Project.serializer(), text)
}

/** Formatea milisegundos como m:ss.d o h:mm:ss. */
fun formatTime(ms: Long, tenths: Boolean = true): String {
    val safe = ms.coerceAtLeast(0)
    val totalSec = safe / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    return if (tenths && h == 0L) "$base.${(safe % 1000) / 100}" else base
}
