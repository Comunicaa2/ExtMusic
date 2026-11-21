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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 *  - Semillas: la primera canción de la cola (ancla, para no alejarse del estilo)
 *    y lo que suena, a medias; si no aportan nada nuevo, las 2 anteriores de ESTA
 *    sesión (nunca el historial de otros días).
 *  - Elige al azar entre las más parecidas de cada radio, máximo 2 por artista, y
 *    deja para el final lo escuchado estos días (ver [elegir]).
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
        // Las marcadas con "No recomendar" (y otras ediciones suyas) nunca entran.
        val ocultas = songDao.noRecomendadas().first()
        val yaVistas = reproducidas + cola.map { it.mediaId } + ocultas.map { it.id }
        val clavesVistas = clavesReproducidas + cola.map { clave(it) } + ocultas.map { claveCancion(it.title, it.artist) }
        fun nuevas(lista: List<SearchItem>) = lista.filter {
            it.isSong && it.videoId.isNotBlank() && it.videoId !in yaVistas &&
                claveCancion(it.title, it.artist) !in clavesVistas &&
                !(ocultarExplicito && it.explicit)
        }.distinctBy { claveCancion(it.title, it.artist) }

        // Lo escuchado en los últimos días (también en otras sesiones) va al final.
        val recientes = songDao.escuchadasDesde(System.currentTimeMillis() - RECIENTE_MS).toHashSet()
        fun frescas(lista: List<SearchItem>) = lista.filter { it.videoId !in recientes }

        suspend fun radioDe(item: MediaItem): List<SearchItem> {
            val videoId = item.mediaId.takeIf { ID_YOUTUBE.matches(it) } ?: return emptyList()
            return nuevas(runCatching { innertube.relacionadasYtm(videoId) }.getOrDefault(emptyList()))
        }

        // Ancla: la canción con la que empezó la cola (la que eligió el usuario).
        // Sacando solo de la radio de lo que suena, cada tanda se parece a la
        // anterior pero la cola se aleja: medido, de "Enter Sandman" a baladas de
        // los 80 en ~60 canciones. Con media tanda de la radio del ancla, vuelve
        // una y otra vez al estilo de partida.
        val ancla = cola.firstOrNull { ID_YOUTUBE.matches(it.mediaId) }
            ?.takeIf { it.mediaId != actual.mediaId }
        var fuentes = coroutineScope {
            val deAncla = async { ancla?.let { radioDe(it) }.orEmpty() }
            val deActual = async { radioDe(actual) }
            listOf(deAncla.await(), deActual.await())
        }.filter { it.isNotEmpty() }

        // Si no aportan nada sin escuchar estos días, se prueban las 2 anteriores de la sesión.
        if (fuentes.none { frescas(it).isNotEmpty() }) {
            val otras = historial.reversed()
                .filterNot { esMusicaDelTelefono(it) || it.mediaId == actual.mediaId || it.mediaId == ancla?.mediaId }
                .distinctBy { it.mediaId }
                .take(2)
            for (semilla in otras) {
                val radio = radioDe(semilla)
                if (frescas(radio).isNotEmpty()) { fuentes = listOf(radio); break }
                if (fuentes.isEmpty() && radio.isNotEmpty()) fuentes = listOf(radio)
            }
        }
        val artista = actual.mediaMetadata.artist?.toString().orEmpty()
        if (fuentes.isEmpty() && artista.isNotBlank()) {
            val claveArtista = claveCancion("", artista)
            fuentes = listOf(nuevas(runCatching { innertube.search(artista) }.getOrDefault(emptyList()))
                .filter { claveCancion("", it.artist) == claveArtista })
        }
        return elegir(fuentes, recientes).map { item ->
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

    /**
     * La radio de YouTube (sin cuenta) devuelve casi las mismas 10 primeras cada vez
     * (medido: 4–8 de 10 repetidas al pedirla de nuevo), y suelen ser los mismos
     * éxitos para semillas distintas: tomar siempre esas 10 sonaba como una playlist
     * fija. De cada radio se eligen al azar entre las [VENTANA] más parecidas que no
     * se hayan escuchado estos días (en el orden de la radio), detrás el resto y al
     * final las ya escuchadas. Las radios se intercalan y cada artista entra como
     * mucho [MAX_POR_ARTISTA] veces por tanda: la radio de una canción de KAROL G
     * es casi toda suya (medido: 9 de 20 seguidas). Si no se llega a [TANDA], se
     * completa sin ese límite.
     */
    private fun elegir(fuentes: List<List<SearchItem>>, recientes: Set<String>): List<SearchItem> {
        val ordenadas = fuentes.map { radio ->
            val (frescas, oidas) = radio.partition { it.videoId !in recientes }
            val orden = radio.withIndex().associate { it.value.videoId to it.index }
            val alAzar = frescas.take(VENTANA).shuffled().take(TANDA).sortedBy { orden[it.videoId] }
            alAzar + (frescas - alAzar.toSet()) + oidas
        }
        // Intercaladas: 1ª de cada radio, 2ª de cada radio…; sin la misma canción dos veces.
        val todas = (0 until (ordenadas.maxOfOrNull { it.size } ?: 0))
            .flatMap { i -> ordenadas.mapNotNull { it.getOrNull(i) } }
            .distinctBy { claveCancion(it.title, it.artist) }
        val porArtista = HashMap<String, Int>()
        val elegidas = todas.filter { item ->
            val artista = claveCancion("", item.artist)
            val n = porArtista.getOrDefault(artista, 0)
            (n < MAX_POR_ARTISTA).also { if (it) porArtista[artista] = n + 1 }
        }.take(TANDA)
        return elegidas + (todas - elegidas.toSet()).take(TANDA - elegidas.size)
    }

    private companion object {
        const val TANDA = 10
        const val VENTANA = 25
        const val MAX_POR_ARTISTA = 2
        const val RECIENTE_MS = 3L * 24 * 60 * 60 * 1000   // 3 días
    }

    private fun clave(item: MediaItem) =
        claveCancion(item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty())

    // Música del teléfono (MediaStore): su id es un content://… Las descargas de
    // YouTube también son "LOCAL" pero conservan el videoId, así que no cuentan.
    private fun esMusicaDelTelefono(item: MediaItem) = item.mediaId.startsWith("content://")
}
