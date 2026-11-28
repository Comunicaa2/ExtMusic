# Actualizador de ExtMusic: seguridad y recuperación

Desde la 1.4.2, ExtMusic avisa de las versiones nuevas publicadas en GitHub, descarga la APK, la comprueba y abre el instalador de Android. El código está en `app/src/main/kotlin/com/auramusic/app/actualizador/`.

## Modelo de confianza

La raíz de la confianza es **la clave con la que se firman las APK de las releases**, la misma que usa Android para aceptar o rechazar una actualización. No hay una segunda clave (por ejemplo, un manifiesto firmado con Ed25519): se descartó el 25-nov-2026 porque no añade protección contra APKs falsas y obliga a custodiar otra clave secreta.

| Qué se comprueba | Dónde | Si falla |
|---|---|---|
| La copia instalada es la de GitHub (huella del certificado = `HUELLA_GITHUB`) | `FirmaPropia.esDeGithub` | El actualizador no aparece (copias de F-Droid y debug) |
| La release tiene un único `.apk`, URL de `github.com/Comunicaa2/ExtMusic/releases/download/`, tamaño de 1 B a 50 MB y `digest` sha256 | `ValidarRelease.validar` y `esValida` | No se ofrece nada |
| La versión publicada es más nueva (comparación por números) | `ValidarRelease.esMasNueva` | No se ofrece nada |
| La descarga no pasa del tamaño publicado y su SHA-256 coincide con el `digest` | `DescargaActualizacionWorker` | Se borra el `.part`: "La descarga no coincidía…" |
| Antes de decir "Lista para instalar": se recalcula el SHA-256, y la APK debe tener el mismo paquete, un versionCode mayor y la misma firma que la app instalada | `ActualizacionViewModel.buscarApkLista` e `InstaladorApk.verificar` | Se borra: "La APK no era válida…" |
| Al pulsar Instalar se comprueba todo otra vez; después Android vuelve a verificar firma y versión | `InstaladorApk.verificar` y el instalador del sistema | No se abre el instalador |

### Amenazas

**Cubiertas:**
- APK alterada en el camino.
- Release falsa en una cuenta de GitHub comprometida: sin la clave de firma no se puede hacer una APK que pase.
- Descargas cortadas o interrumpidas.
- Bajar a una versión anterior.
- Datos guardados dañados.
- Copias con otra firma.

**Aceptadas (no cubiertas):** quien controle la cuenta de GitHub podría esconder una versión nueva (ataque de congelación) o poner un texto falso en las novedades. Las novedades se muestran como texto plano, sin enlaces ni HTML.

## Pruebas de manipulación (25-nov-2026, Galaxy J7, Android 11)

Se usó una release temporal **depurable** ("1.4.0", versionCode 8) firmada con la clave real. Sirve para meter archivos en la carpeta privada con `run-as`. **No se publica nunca.**

| Caso | Resultado |
|---|---|
| APK legítima (v1.4.1 de GitHub) | Llega a "Lista para instalar" e instala; los datos se conservan |
| APK re-firmada con otra clave | Rechazada y borrada (por huella) |
| APK re-firmada con otra clave **y** con la huella publicada cambiada para que coincida | Rechazada y borrada (por firma): "La APK no era válida…" |
| APK con un byte cambiado (la firma no verifica) | Borrada al abrir la app |
| APK de una versión igual o anterior a la instalada | Borrada al abrir la app |
| SHA-256 publicado incorrecto | "La descarga no coincide": se borra el `.part` |
| Release guardada con datos basura y GitHub responde 304 | Se descarta, sin cerrar la app; la siguiente búsqueda la pide entera |
| Descarga cancelada a mitad | Trabajo cancelado, sin `.part` |
| Sin conexión | "No se pudo descargar: sin conexión", sin restos |
| Cancelar el instalador de Android | Sigue la versión de antes, sin mensaje de éxito |
| Falta el permiso de instalar | Lo explica; al volver de Ajustes (Android mata la app) se abre el instalador solo |
| Debug (Mambo) | No muestra el actualizador |

## Al publicar una versión

1. Firmar la APK con la clave de siempre y comprobarlo: `apksigner verify --print-certs ExtMusic-X.Y.Z.apk` debe dar `7492411a3df91a2128010740826791eed78f15deba2a962bea2ca6e9e2a3e2a7`.
2. En GitHub la APK se llama solo `ExtMusic.apk`, sin número de versión. Copiarla con ese nombre a una carpeta temporal y subir la copia: `gh release create vX.Y.Z <carpeta temporal>/ExtMusic.apk …`. GitHub calcula el `digest` solo.
3. Comprobar que el `digest` de GitHub coincide con el `sha256sum` local: `gh api repos/Comunicaa2/ExtMusic/releases/latest --jq '.assets[]|.digest'`.
4. Nunca sustituir la APK de una release ya publicada (`--clobber`): quien la estuviera descargando recibiría una huella distinta. Mejor sacar una versión nueva.

## Si algo se ve comprometido

**La cuenta de GitHub:**
- Cambiar la contraseña, revisar 2FA y tokens, y borrar cualquier release que no sea nuestra.
- Las apps instaladas no instalarán una APK falsa (no lleva nuestra firma), pero sí podrían ver un aviso falso. Avisar en el README.

**La clave de firma (robada):**
- Android no tiene revocación. Hay que rotar la clave con *APK Signature Scheme v3.1* (`apksigner rotate` con la clave vieja y la nueva; luego firmar con el *lineage*). Android 9+ acepta la clave nueva porque la vieja la avala.
- En esa misma versión, `HUELLA_GITHUB` tiene que aceptar también la huella nueva (convertirla en un conjunto) y la comprobación de firma tiene que aceptar el *lineage*.
- En Android 8.x (API 26–27) la rotación no se reconoce: esos usuarios tendrán que exportar el backup (`.extmusic`), desinstalar e instalar de nuevo.

**La clave de firma (perdida):** sin ella no se puede publicar ninguna actualización que Android acepte. Cada usuario tendría que exportar su backup, desinstalar e instalar una app firmada con una clave nueva. Por eso la clave y sus contraseñas tienen que tener **copias cifradas en dos sitios distintos**, comprobadas de vez en cuando. Nunca en el repositorio, en `local.properties` versionado ni en el código.
