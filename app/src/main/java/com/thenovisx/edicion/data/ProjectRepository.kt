package com.thenovisx.edicion.data

import android.content.Context
import com.thenovisx.edicion.core.AspectRatio
import com.thenovisx.edicion.core.Project
import com.thenovisx.edicion.core.ProjectJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Guarda cada proyecto como un archivo JSON en el almacenamiento interno de la app. */
class ProjectRepository(context: Context) {
    private val dir = File(context.filesDir, "projects").apply { mkdirs() }
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _projects.value = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { ProjectJson.decode(f.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun create(name: String, aspectRatio: AspectRatio): Project {
        val now = System.currentTimeMillis()
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = name,
            aspectRatio = aspectRatio,
            createdAt = now,
            updatedAt = now,
        )
        save(project)
        return project
    }

    suspend fun load(id: String): Project? = withContext(Dispatchers.IO) {
        val f = file(id)
        if (!f.exists()) null else runCatching { ProjectJson.decode(f.readText()) }.getOrNull()
    }

    suspend fun save(project: Project) = withContext(Dispatchers.IO) {
        val updated = project.copy(updatedAt = System.currentTimeMillis())
        val tmp = File(dir, "${project.id}.json.tmp")
        tmp.writeText(ProjectJson.encode(updated))
        // Escritura atómica: si la app se cierra a mitad no se corrompe el proyecto.
        if (!tmp.renameTo(file(project.id))) {
            file(project.id).writeText(ProjectJson.encode(updated))
            tmp.delete()
        }
        _projects.value = (_projects.value.filterNot { it.id == project.id } + updated)
            .sortedByDescending { it.updatedAt }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        file(id).delete()
        _projects.value = _projects.value.filterNot { it.id == id }
    }

    suspend fun rename(id: String, name: String) {
        load(id)?.let { save(it.copy(name = name)) }
    }

    suspend fun duplicate(id: String): Project? {
        val p = load(id) ?: return null
        val now = System.currentTimeMillis()
        val copy = p.copy(id = UUID.randomUUID().toString(), name = "${p.name} (copia)", createdAt = now)
        save(copy)
        return copy
    }

    private fun file(id: String) = File(dir, "$id.json")

    companion object {
        @Volatile private var instance: ProjectRepository? = null
        fun get(context: Context): ProjectRepository =
            instance ?: synchronized(this) {
                instance ?: ProjectRepository(context.applicationContext).also { instance = it }
            }
    }
}
