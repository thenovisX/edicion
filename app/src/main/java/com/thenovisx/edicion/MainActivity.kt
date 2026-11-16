package com.thenovisx.edicion

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.thenovisx.edicion.ui.editor.EditorScreen
import com.thenovisx.edicion.ui.home.HomeScreen
import com.thenovisx.edicion.ui.photo.CollageScreen
import com.thenovisx.edicion.ui.photo.PhotoEditorScreen
import com.thenovisx.edicion.ui.theme.EdicionTheme

class MainActivity : ComponentActivity() {
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // En Android 9 o anterior hace falta permiso para guardar en la galería.
        if (Build.VERSION.SDK_INT < 29 && savedInstanceState == null) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        setContent {
            EdicionTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onOpenProject = { id -> nav.navigate("editor/$id") },
                            onEditPhoto = { uri -> nav.navigate("photo?uri=${Uri.encode(uri.toString())}") },
                            onCollage = { uris -> nav.navigate("collage?uris=${Uri.encode(uris.joinToString("\n"))}") },
                        )
                    }
                    composable(
                        "editor/{projectId}",
                        arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
                    ) {
                        EditorScreen(onBack = { nav.popBackStack() })
                    }
                    composable(
                        "collage?uris={uris}",
                        arguments = listOf(navArgument("uris") { type = NavType.StringType }),
                    ) { entry ->
                        val uris = entry.arguments?.getString("uris").orEmpty().split("\n").filter { it.isNotBlank() }.map(Uri::parse)
                        CollageScreen(uris = uris, onBack = { nav.popBackStack() })
                    }
                    composable(
                        "photo?uri={uri}",
                        arguments = listOf(navArgument("uri") { type = NavType.StringType }),
                    ) { entry ->
                        val uri = Uri.parse(entry.arguments?.getString("uri").orEmpty())
                        PhotoEditorScreen(uri = uri, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
