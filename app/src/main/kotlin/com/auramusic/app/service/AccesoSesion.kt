package com.auramusic.app.service

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.auramusic.app.domain.model.ID_YOUTUBE
import com.auramusic.app.work.DescargaWorker

/**
 * Qué puede hacer cada app con la sesión de [MusicService]. El servicio está
 * exportado (los controles del sistema se conectan desde fuera), así que cualquier
 * app instalada puede intentarlo. Hasta la 1.4.2 cualquiera podía leer la cola y
 * meterle direcciones (https, content://, file://), que además quedaban guardadas.
 */
@OptIn(UnstableApi::class)
object AccesoSesion {

    /**
     * Protocolo antiguo (android.media.browse). El manifiesto lo declara desde el
     * principio y Media3 lo atiende, entregando la llave de la sesión del sistema antes
     * de preguntar quién es: con ella se leía la cola y se cambiaba de canción.
     * Rechazarlo no quita nada que funcionara: la interfaz del sistema de Android 11–12
     * lo usa para volver a mostrar los controles tras reiniciar, pero con este servicio
     * (no es un MediaLibraryService) ya fallaba en la 1.4.2 ("Cannot resume", J7).
     */
    const val ACCION_NAVEGADOR_ANTIGUO = "android.media.browse.MediaBrowserService"

    enum class Mando { PROPIO, DE_CONFIANZA, AJENO }

    /** Se decide por el uid, no por el nombre de paquete. */
    fun de(mando: MediaSession.ControllerInfo): Mando = when {
        mando.uid == Process.myUid() -> Mando.PROPIO
        // Para Android: el sistema y las apps con control multimedia o acceso a notificaciones.
        mando.isTrusted -> Mando.DE_CONFIANZA
        // Android 8.x no dice quién envía lo que llega por la sesión del sistema
        // (auriculares, pantalla de bloqueo): rechazarlo dejaría esos botones sin
        // respuesta. La llave de esa sesión solo la consiguen mandos de confianza.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P &&
            mando.packageName == MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME -> Mando.DE_CONFIANZA
        else -> Mando.AJENO
    }

    /** Un mando de confianza controla la reproducción y ve la cola, pero no le mete canciones. */
    fun comandosDeConfianza(): Player.Commands =
        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .removeAll(Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM)
            .build()

    /** ¿Es una dirección de las que la propia app pone en la cola? */
    fun uriDeLaApp(contexto: Context, uri: Uri): Boolean = when (uri.scheme) {
        MusicService.ESQUEMA_YT -> ID_YOUTUBE.matches(uri.schemeSpecificPart.orEmpty())
        "content" -> uri.authority == "media"   // música del teléfono
        // Solo descargas propias (también la forma "file://file:///…" que arma mediaItemDe).
        "file" -> DescargaWorker.archivoDescargado(contexto, uri.toString()) != null
        else -> false
    }

    class Cola(val items: List<MediaItem>, val indice: Int, val posicionMs: Long)

    /**
     * La cola guardada sin lo que la app no habría puesto (p. ej. lo que otra app
     * metió con una versión anterior). Se sigue en la misma canción; si era una de
     * las descartadas, se pasa a la siguiente que quede, desde el principio.
     */
    fun filtrarCola(contexto: Context, items: List<MediaItem>, indice: Int, posicionMs: Long): Cola {
        if (items.isEmpty()) return Cola(items, 0, 0L)
        val buenos = items.map { item -> item.localConfiguration?.let { uriDeLaApp(contexto, it.uri) } == true }
        val actual = indice.coerceIn(0, items.lastIndex)
        val quedan = items.filterIndexed { i, _ -> buenos[i] }
        val nuevo = buenos.take(actual).count { it }.coerceAtMost((quedan.size - 1).coerceAtLeast(0))
        return Cola(quedan, nuevo, if (buenos[actual]) posicionMs else 0L)
    }
}
