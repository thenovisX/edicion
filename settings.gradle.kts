pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "edicion"

// Lógica del editor en Kotlin puro (modelo, línea de tiempo, historial). Se prueba sin Android.
include(":core")

// La app Android necesita el SDK de Android. Se puede omitir con -PsoloCore=true
// para compilar y probar solo :core en máquinas sin SDK.
if (providers.gradleProperty("soloCore").orNull != "true") {
    include(":app")
}
