# ExtMusic

Reproductor de música libre para Android que reproduce el catálogo de **YouTube Music sin iniciar sesión** y también la música guardada en el teléfono.

## Funciones

- Búsqueda en YouTube Music con canciones, artistas, álbumes y filtros
- Páginas de artista y de álbum con su catálogo
- Reproducción automática con la radio de YouTube Music
- Letras sincronizadas (LRCLIB, con KuGou de respaldo) y archivos `.lrc` locales
- Descargas para escuchar sin conexión
- Música local del teléfono, con filtro por carpeta
- Me gusta, playlists propias e importación de playlists de YouTube por enlace
- Cola editable, temporizador de sueño y normalización de volumen
- Copia de seguridad y restauración de la biblioteca
- Diseño con colores dinámicos y efecto de vidrio líquido

## Privacidad

- Sin cuenta de Google y sin anuncios.
- Sin rastreo ni analíticas.
- La biblioteca (me gusta, playlists, descargas) se guarda solo en el teléfono.

## Requisitos

- Android 8.0 (API 26) o superior.
- El efecto de refracción del vidrio líquido necesita Android 13 o superior. En versiones anteriores se ve un desenfoque normal.

## Instalación

- **F-Droid**: próximamente.
- **GitHub**: descarga el APK desde la sección [Releases](../../releases).

## Compilar

Hace falta JDK 17 y el Android SDK 37.

```sh
./gradlew assembleDebug     # APK de debug
./gradlew assembleRelease   # APK de release con R8
```

La firma de release se lee de `local.properties`, que no se versiona:

```properties
RELEASE_STORE_FILE=/ruta/a/tu-clave.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

Sin esas propiedades, para obtener un APK de release sin firmar hay que quitar la línea `signingConfig = signingConfigs.getByName("release")` de `app/build.gradle.kts`.

## Créditos

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0): obtención del audio de YouTube
- [Backdrop y Shapes](https://github.com/Kyant0/AndroidLiquidGlass) de Kyant0 (Apache-2.0): efecto de vidrio líquido
- [LRCLIB](https://lrclib.net) y KuGou: letras
- [Media3 / ExoPlayer](https://github.com/androidx/media), [Coil](https://github.com/coil-kt/coil) y [Reorderable](https://github.com/Calvin-LL/Reorderable)

## Aviso

ExtMusic no está afiliada a YouTube ni a Google, ni cuenta con su aprobación. YouTube y YouTube Music son marcas de Google LLC.

## Licencia

ExtMusic es software libre bajo la licencia [GPL-3.0-or-later](LICENSE).
