package com.auramusic.app.data.repository

import android.net.Uri
import androidx.room.withTransaction
import com.auramusic.app.data.db.AuraDatabase
import com.auramusic.app.data.db.PlaylistEntity
import com.auramusic.app.data.db.PlaylistSongCrossRef
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.domain.model.OrigenCancion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Importa una playlist pública o NO LISTADA de YouTube / YouTube Music a partir de
 * su enlace, sin cuenta (con NewPipe). Las privadas no se pueden leer sin cuenta. Sustituye en parte a la sincronización con Google.
 *
 * Acepta enlaces de youtube.com y music.youtube.com (playlists y álbumes
 * "OLAK5uy_…"), o el id de la playlist a secas. La playlist local se guarda con
 * id "yt_<id>", así que importar otra vez la misma AÑADE lo nuevo en vez de
 * duplicarla. Nunca borra ni pisa canciones que ya estén en la app.
 */
@Singleton
class ImportadorPlaylists @Inject constructor(
    private val db: AuraDatabase
) {
    data class Resultado(val nombre: String, val canciones: Int)

    class EnlaceNoValido : Exception("No es un enlace de playlist de YouTube")

    suspend fun importar(enlace: String): Resultado = withContext(Dispatchers.IO) {
        val listId = idDePlaylist(enlace) ?: throw EnlaceNoValido()
        val url = "https://www.youtube.com/playlist?list=$listId"

        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
        val items = info.relatedItems.toMutableList()
        var pagina = info.nextPage
        while (pagina != null && items.size < MAXIMO_CANCIONES) {
            val mas = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, pagina)
            items += mas.items
            pagina = mas.nextPage
        }

        // Se descartan los vídeos borrados/privados (sin duración) y los repetidos.
        val canciones = items.take(MAXIMO_CANCIONES)
            .filter { it.duration > 0 }
            .mapNotNull { it.aCancion() }
            .distinctBy { it.id }
        // Los álbumes de YT Music llegan como "Album – Nombre".
        val nombre = info.name.removePrefix("Album – ").ifBlank { listId }

        val songDao = db.songDao()
        val playlistDao = db.playlistDao()
        db.withTransaction {
            val existentes = songDao.todosLosIds().toHashSet()
            songDao.upsert(canciones.filter { it.id !in existentes })
            val idLocal = "yt_$listId"
            playlistDao.insertarSiNoExisten(listOf(PlaylistEntity(id = idLocal, name = nombre)))
            playlistDao.relacionarSiNoExisten(
                canciones.mapIndexed { i, c -> PlaylistSongCrossRef(idLocal, c.id, i) }
            )
        }
        Resultado(nombre, canciones.size)
    }

    private fun StreamInfoItem.aCancion(): SongEntity? {
        val videoId = runCatching { YoutubeStreamLinkHandlerFactory.getInstance().getId(url) }
            .getOrNull() ?: return null
        return SongEntity(
            id = videoId,
            title = name,
            // Los canales automáticos de YT Music se llaman "Artista - Topic".
            artist = uploaderName.orEmpty().removeSuffix(" - Topic"),
            artistId = "",
            album = "",
            thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url.orEmpty(),
            durationMs = duration * 1000,
            origen = OrigenCancion.YOUTUBE.name,
            fuente = videoId
        )
    }

    private fun idDePlaylist(enlace: String): String? {
        val texto = enlace.trim()
        // Id a secas (PL…, OLAK5uy_…, etc.).
        if (Regex("^[\\w-]{10,}$").matches(texto) && !texto.startsWith("http")) return texto
        val uri = runCatching { Uri.parse(texto) }.getOrNull() ?: return null
        // Dominio exacto o subdominio de verdad: "evilyoutube.com" no vale.
        val host = uri.host.orEmpty().lowercase()
        val deYoutube = host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
        if (!deYoutube) return null
        return uri.getQueryParameter("list")?.takeIf { Regex("^[\\w-]{10,}$").matches(it) }
    }

    private companion object {
        const val MAXIMO_CANCIONES = 1000
    }
}
