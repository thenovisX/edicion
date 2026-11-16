package com.thenovisx.edicion.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thenovisx.edicion.R
import com.thenovisx.edicion.core.AspectRatio
import com.thenovisx.edicion.core.Project
import com.thenovisx.edicion.core.formatTime
import com.thenovisx.edicion.data.ProjectRepository
import com.thenovisx.edicion.media.Thumbnails
import com.thenovisx.edicion.ui.editor.TextInputDialog
import com.thenovisx.edicion.ui.theme.Palette
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProjectRepository.get(app)
    val projects = repo.projects

    init {
        viewModelScope.launch { repo.refresh() }
    }

    suspend fun create(name: String, ratio: AspectRatio): Project = repo.create(name, ratio)
    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun rename(id: String, name: String) = viewModelScope.launch { repo.rename(id, name) }
    fun duplicate(id: String) = viewModelScope.launch { repo.duplicate(id) }
}

@Composable
fun HomeScreen(
    onOpenProject: (String) -> Unit,
    onEditPhoto: (Uri) -> Unit,
    onCollage: (List<Uri>) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showNew by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onEditPhoto(uri)
    }

    val pickCollage = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.size >= 2) onCollage(uris)
        else if (uris.isNotEmpty()) Toast.makeText(context, "Elige al menos dos fotos para el collage", Toast.LENGTH_SHORT).show()
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(
                    context.getString(R.string.app_name),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                )
                Text("Edita vídeo, foto y audio desde el móvil", color = Palette.OnSurfaceMuted, fontSize = 15.sp)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigAction(
                    title = "Nuevo vídeo",
                    subtitle = "Línea de tiempo, texto, música",
                    icon = Icons.Default.Movie,
                    colors = listOf(Palette.Coral, Color(0xFFFF8F5A)),
                    modifier = Modifier.weight(1f),
                    onClick = { showNew = true },
                )
                BigAction(
                    title = "Editar foto",
                    subtitle = "Filtros, ajustes, recorte",
                    icon = Icons.Default.PhotoCamera,
                    colors = listOf(Palette.Violet, Color(0xFF9D7BFF)),
                    modifier = Modifier.weight(1f),
                    onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row {
                BigAction(
                    title = "Collage",
                    subtitle = "Une de 2 a 9 fotos en una imagen",
                    icon = Icons.Default.GridView,
                    colors = listOf(Palette.Teal, Color(0xFF6EE7D8)),
                    modifier = Modifier.weight(1f),
                    onClick = { pickCollage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                if (projects.isEmpty()) "Aún no tienes proyectos" else "Proyectos",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(projects, key = { it.id }) { p ->
            ProjectCard(
                project = p,
                onClick = { onOpenProject(p.id) },
                onRename = { renaming = p },
                onDuplicate = { vm.duplicate(p.id) },
                onDelete = { deleting = p },
            )
        }
    }

    if (showNew) {
        NewProjectDialog(
            defaultName = "Proyecto ${projects.size + 1}",
            onDismiss = { showNew = false },
            onCreate = { name, ratio ->
                showNew = false
                scope.launch { onOpenProject(vm.create(name, ratio).id) }
            },
        )
    }
    renaming?.let { p ->
        TextInputDialog(
            title = "Renombrar",
            initial = p.name,
            singleLine = true,
            onDismiss = { renaming = null },
            onConfirm = { vm.rename(p.id, it); renaming = null },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("¿Borrar «${p.name}»?") },
            text = { Text("Se borra el proyecto, no los vídeos ni fotos originales.") },
            confirmButton = { TextButton(onClick = { vm.delete(p.id); deleting = null }) { Text("Borrar", color = Palette.Coral) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun BigAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    colors: List<Color>,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(colors))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Icon(icon, null, tint = Color.Black, modifier = Modifier.size(30.dp))
        Text(title, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 18.dp))
        Text(subtitle, color = Color.Black.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var thumb by remember(project.thumbnailUri) { mutableStateOf<Bitmap?>(null) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(project.thumbnailUri) {
        val first = project.clips.firstOrNull()
        thumb = first?.let { Thumbnails.load(context, it.uri, it.kind, it.trimStartMs, 320) }
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
            val bmp = thumb
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.VideoLibrary, null, tint = Palette.OnSurfaceMuted, modifier = Modifier.size(36.dp))
            }
            Text(
                formatTime(project.durationMs, tenths = false),
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
            )
        }
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(project.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${project.aspectRatio.label} · " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(project.updatedAt)),
                    fontSize = 11.sp,
                    color = Palette.OnSurfaceMuted,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opciones") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicar") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Borrar") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun NewProjectDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onCreate: (String, AspectRatio) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var ratio by remember { mutableStateOf(AspectRatio.VERTICAL_9_16) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo vídeo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nombre") })
                Text("Formato", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AspectRatio.entries.take(3).forEach { ar -> RatioChip(ar, ratio == ar) { ratio = ar } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AspectRatio.entries.drop(3).forEach { ar -> RatioChip(ar, ratio == ar) { ratio = ar } }
                }
                Text(ratioHint(ratio), fontSize = 12.sp, color = Palette.OnSurfaceMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim().ifEmpty { defaultName }, ratio) }) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun RatioChip(ratio: AspectRatio, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(ratio.label) })
}

private fun ratioHint(r: AspectRatio) = when (r) {
    AspectRatio.VERTICAL_9_16 -> "Vertical: reels, historias y vídeos cortos"
    AspectRatio.HORIZONTAL_16_9 -> "Horizontal: vídeos largos y pantallas grandes"
    AspectRatio.SQUARE_1_1 -> "Cuadrado: publicaciones del feed"
    AspectRatio.PORTRAIT_4_5 -> "Retrato: publicaciones verticales del feed"
    AspectRatio.PORTRAIT_3_4 -> "Retrato clásico"
}
