# Edición

App Android de edición multimedia (vídeo, foto y audio) pensada para el móvil.
"Edición" es un nombre provisional.

## Qué hace ya

- **Proyectos:** crear (9:16, 16:9, 1:1, 4:5, 3:4), abrir, renombrar, duplicar y borrar. Guardado automático.
- **Editor de vídeo** con vista previa y línea de tiempo (vídeo/fotos, capas, música y voz):
  - Importar vídeos, fotos y música; reemplazar un clip conservando sus ediciones.
  - Dividir, recortar, reordenar, duplicar, borrar, congelar fotograma y velocidad 0,25×–4×.
  - Transiciones: fundidos, zoom, empuje, giro y destello. Efectos de clip: acercar lento, paneo,
    temblor, latido, balanceo, estrobo y fallo.
  - Color: 23 filtros propios, luz, color, curvas, HSL por color, viñeta, grano y desenfoque.
  - Encuadre: rellenar, zoom, desplazamiento y fondo desenfocado.
  - Capas ilimitadas de texto, stickers, imágenes y vídeos, con fotogramas clave, formas de recorte,
    animaciones y estilos; se mueven, escalan y giran con los dedos.
  - Subtítulos escritos o importados de un .srt, y texto a voz.
  - Audio: música y voz en off con formas de onda, fundidos, cambiador de tono y extracción del
    audio de un vídeo. Los audios y las capas se mueven manteniendo pulsado y arrastrando.
  - Marcadores, deshacer/rehacer y zoom con pellizco.
  - Exportación a MP4 (720p, 1080p o 4K; H.264 o HEVC; cuatro calidades) o solo audio (M4A).
- **Editor de fotos:** el mismo motor de color, recorte, enderezado, marco, texto y stickers,
  deshacer y comparación con el original.
- **Collage** de 2 a 9 fotos.

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
- La app se compila y sus pruebas automáticas pasan, pero todavía no se ha probado en un teléfono real.
- Las transiciones son fundidos y movimientos; la disolución cruzada entre clips está pendiente.
- Exportar proyectos con capas de vídeo es más lento que sin ellas.
- Tras saltar a otro punto en la vista previa, los fundidos de audio suenan desde donde se reanuda;
  en el vídeo exportado son exactos.
