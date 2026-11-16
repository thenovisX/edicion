# Edición

App Android de edición multimedia (vídeo, foto y audio) pensada para el móvil.
"Edición" es un nombre provisional.

## Qué hace ya

- **Proyectos:** crear (9:16, 16:9, 1:1, 4:5, 3:4), abrir, renombrar, duplicar y borrar. Guardado automático.
- **Editor de vídeo** con vista previa y línea de tiempo de cuatro pistas (vídeo/fotos, capas, música y voz):
  - Importar vídeos y fotos desde la galería, y música desde archivos.
  - Dividir en el cabezal, recortar, reordenar, duplicar y borrar.
  - Velocidad 0,25×–4×, volumen y silencio por clip.
  - Transiciones por fundido a negro o blanco, con duración ajustable.
  - 11 filtros propios con intensidad y ajustes de brillo, contraste, saturación, temperatura, matiz y tono.
  - Girar y espejo.
  - Textos con 6 tipos de letra, color, fondo, contorno, sombra, opacidad, rotación y
    animaciones de entrada y salida (fundido, deslizar, zoom, máquina de escribir).
  - Stickers (emojis) e imágenes superpuestas (logo, foto) con tamaño, opacidad y rotación.
  - Música con recorte, volumen y posición de inicio.
  - Voz en off grabada desde la app, en su propia pista.
  - Deshacer/rehacer, zoom con pellizco en la línea de tiempo.
  - Exportación a MP4 (720p, 1080p o 4K) en la galería, con progreso, cancelación y compartir.
- **Editor de fotos:** filtros, ajustes, girar, voltear, recortar a proporción y guardar en la galería.

El inventario completo de funciones (basado en lo que ofrecen los editores móviles
más usados) y la hoja de ruta por etapas está en [docs/FUNCIONES.md](docs/FUNCIONES.md).

## Cómo conseguir el APK

Cada push compila la app en GitHub Actions y publica el APK en
[Releases](https://github.com/thenovisX/edicion/releases/tag/ultima). Descarga directa:
<https://github.com/thenovisX/edicion/releases/download/ultima/edicion.apk>.
Para instalarlo hay que permitir "instalar apps desconocidas" en el móvil.

## Estructura

- `core/`: lógica del editor en Kotlin puro (modelo del proyecto, operaciones de línea de tiempo,
  historial, matrices de color). Tiene pruebas unitarias: `./gradlew :core:test`.
- `app/`: app Android con Jetpack Compose y Media3 (CompositionPlayer para la vista previa
  y Transformer para exportar).

Requisitos para compilar en local: JDK 17 y Android SDK 35.


## Limitaciones conocidas

- Requiere Android 9 o superior.
- Las transiciones son fundidos por color; la disolución cruzada entre clips está pendiente.
- Aún no hay superposición de vídeos (PiP); sí de imágenes.
