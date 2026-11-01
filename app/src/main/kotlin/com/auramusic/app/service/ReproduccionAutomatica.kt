package com.auramusic.app.service

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.auramusic.app.data.api.InnertubeClient
import com.auramusic.app.data.api.SearchItem
import com.auramusic.app.data.db.SongDao
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.data.repository.RepositorioPreferencias
import com.auramusic.app.domain.model.ID_YOUTUBE
import com.auramusic.app.domain.model.OrigenCancion
import com.auramusic.app.domain.model.claveCancion
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reproducción automática: cuando se acaba la cola (o se pulsa "siguiente" en la
 * última canción, desde la app, la notificación, los auriculares o la pantalla de
 * bloqueo), añade canciones de la RADIO de YouTube Music, como la app oficial.
 *
 * Vive en el servicio (no en la pantalla): así funciona aunque la app esté
 * cerrada y para cualquier origen del "siguiente".
 *  - Semilla: lo que suena; si su radio no aporta nada nuevo, las 2 anteriores de
 *    ESTA sesión (nunca el historial de otros días).
 *  - Respeta el orden de la radio (de más a menos parecida).
 *  - Excluye lo ya sonado y lo ya en cola, por id Y por canción (otra edición de
 *    la misma canción tiene otro videoId).
 *  - Música del teléfono: no se añade nada; es la música propia del usuario.
 *  - Último recurso: canciones del MISMO artista.
 */
@Singleton
class ReproduccionAutomatica @Inject constructor(
    private val innertube: InnertubeClient,
    private val songDao: SongDao,
    private val preferencias: RepositorioPreferencias
) {
    private val reproducidas = HashSet<String>()
    private val clavesReproducidas = HashSet<String>()
    private val historial = ArrayDeque<MediaItem>()   // esta sesión, en orden

    /** Llamar cada vez que empieza una canción. */
    fun registrar(item: MediaItem) {
        reproducidas += item.mediaId
        clavesReproducidas += clave(item)
        if (historial.lastOrNull()?.mediaId != item.mediaId) {
            historial.addLast(item)
            if (historial.size > 20) historial.removeFirst()
        }
    }

    /** Canciones para añadir tras [actual], o lista vacía si no toca (música local, sin red…). */
    suspend fun siguientes(actual: MediaItem, cola: List<MediaItem>): List<MediaItem> {
        if (esMusicaDelTelefono(actual)) return emptyList()
        val ocultarExplicito = preferencias.ajustes.first().ocultarExplicito
        val yaVistas = reproducidas + cola.map { it.mediaId }
        val clavesVistas = clavesReproducidas + cola.map { clave(it) }
        fun nuevas(lista: List<SearchItem>) = lista.filter {
            it.isSong && it.videoId.isNotBlank() && it.videoId !in yaVistas &&
                claveCancion(it.title, it.artist) !in clavesVistas &&
                !(ocultarExplicito && it.explicit)
        }.distinctBy { claveCancion(it.title, it.artist) }

        val semillas = (listOf(actual) + historial.reversed())
            .filterNot { esMusicaDelTelefono(it) }
            .distinctBy { it.mediaId }
            .take(3)
        var candidatas = emptyList<SearchItem>()
        for (semilla in semillas) {
            val videoId = semilla.mediaId.takeIf { ID_YOUTUBE.matches(it) } ?: continue
            candidatas = nuevas(runCatching { innertube.relacionadasYtm(videoId) }.getOrDefault(emptyList()))
            if (candidatas.isNotEmpty()) break
        }
        val artista = actual.mediaMetadata.artist?.toString().orEmpty()
        if (candidatas.isEmpty() && artista.isNotBlank()) {
            val claveArtista = claveCancion("", artista)
            candidatas = nuevas(runCatching { innertube.search(artista) }.getOrDefault(emptyList()))
                .filter { claveCancion("", it.artist) == claveArtista }
        }
        return candidatas.take(10).map { item ->
            val cancion = SongEntity(
                id = item.videoId, title = item.title, artist = item.artist,
                artistId = "", album = "", thumbnailUrl = item.thumbnailUrl, durationMs = 0,
                origen = OrigenCancion.YOUTUBE.name, fuente = item.videoId, explicit = item.explicit
            )
            // Se guarda para que cuente en el historial y en las recomendaciones.
            if (songDao.getById(cancion.id) == null) songDao.upsert(cancion)
            mediaItemDe(cancion)
        }
    }

    /** Una canción → pista de ExoPlayer: archivo si está descargada, si no "ytstream:ID". */
    suspend fun mediaItemDe(cancion: SongEntity): MediaItem {
        val uri = if (cancion.origen == OrigenCancion.LOCAL.name) cancion.fuente else {
            val guardada = songDao.getById(cancion.id)
            if (guardada?.isDownloaded == true && guardada.localPath != null) "file://${guardada.localPath}"
            else MusicService.uriDeVideo(cancion.fuente.ifBlank { cancion.id })
        }
        val meta = MediaMetadata.Builder().setTitle(cancion.title).setArtist(cancion.artist)
            .setDurationMs(cancion.durationMs.takeIf { it > 0 })
        if (cancion.thumbnailUrl.isNotBlank()) meta.setArtworkUri(Uri.parse(cancion.thumbnailUrl))
        return MediaItem.Builder().setUri(uri).setMediaId(cancion.id).setMediaMetadata(meta.build()).build()
    }

    private fun clave(item: MediaItem) =
        claveCancion(item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty())

    // Música del teléfono (MediaStore): su id es un content://… Las descargas de
    // YouTube también son "LOCAL" pero conservan el videoId, así que no cuentan.
    private fun esMusicaDelTelefono(item: MediaItem) = item.mediaId.startsWith("content://")
}
