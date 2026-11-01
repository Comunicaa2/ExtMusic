package com.auramusic.app.data.repository

import android.content.Context
import android.util.Log
import com.auramusic.app.data.api.InnertubeClient
import com.auramusic.app.data.api.SearchItem
import com.auramusic.app.data.db.SongDao
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.domain.model.ID_YOUTUBE
import com.auramusic.app.domain.model.claveCancion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationRepository @Inject constructor(
    private val contexto: Context,
    private val songDao: SongDao,
    private val innertube: InnertubeClient
) {
    // Últimas recomendaciones guardadas: Inicio las muestra al abrir la app (junto
    // con "Me gusta") mientras se calculan las nuevas, que tardan unos segundos.
    private val archivo get() = File(contexto.filesDir, "recomendaciones.json")
    private val serializador = ListSerializer(SearchItem.serializer())

    suspend fun guardadas(): List<SearchItem> = withContext(Dispatchers.IO) {
        runCatching { Json.decodeFromString(serializador, archivo.readText()) }.getOrDefault(emptyList())
    }

    private suspend fun guardar(recs: List<SearchItem>) = withContext(Dispatchers.IO) {
        runCatching { archivo.writeText(Json.encodeToString(serializador, recs)) }
            .onFailure { Log.w("Recomendaciones", "No se pudieron guardar: ${it.message}") }
    }

    /**
     * "Recomendado para ti" con la RADIO de YouTube Music (como la app oficial):
     *  - Semillas: tus 3 canciones más escuchadas + las 2 más recientes, solo de
     *    YouTube (la música del teléfono es tuya y no se usa).
     *  - De cada radio se toman las primeras canciones nuevas (la radio va de más a
     *    menos parecida) y se intercalan para variar sin cambiar de estilo.
     *  - Fuera lo ya escuchado y las ediciones repetidas de una misma canción.
     * Antes se buscaba "<artista> popular songs" como texto libre, que traía
     * canciones sin relación ("Lil Miner" → cualquier cosa con "miner").
     */
    suspend fun getRecommendations(): List<SearchItem> {
        val history = songDao.getAll().first().take(100)
        val deYoutube = history.filter { videoIdDe(it) != null }
        if (deYoutube.isEmpty()) return getDefaultRecommendations()

        val semillas = (deYoutube.sortedByDescending { it.playCount }.take(3) +
            deYoutube.sortedByDescending { it.lastPlayed }.take(2))
            .distinctBy { it.id }

        // Todas las radios A LA VEZ.
        val radios = coroutineScope {
            semillas.map { s ->
                async { runCatching { innertube.relacionadasYtm(videoIdDe(s)!!) }.getOrDefault(emptyList()) }
            }.awaitAll()
        }

        val escuchadas = history.map { it.id }.toHashSet()
        val clavesEscuchadas = history.map { claveCancion(it.title, it.artist) }.toHashSet()
        val elegidas = mutableListOf<SearchItem>()
        val clavesElegidas = HashSet<String>()
        val porRadio = radios.map { radio ->
            radio.filter { it.isSong && it.videoId !in escuchadas &&
                claveCancion(it.title, it.artist) !in clavesEscuchadas }.iterator()
        }
        // Intercala: la mejor de cada radio, luego la segunda de cada una…
        while (elegidas.size < MAXIMO && porRadio.any { it.hasNext() }) {
            for (it in porRadio) {
                while (it.hasNext()) {
                    val c = it.next()
                    if (clavesElegidas.add(claveCancion(c.title, c.artist))) { elegidas += c; break }
                }
                if (elegidas.size >= MAXIMO) break
            }
        }
        if (elegidas.isNotEmpty()) guardar(elegidas)
        return elegidas
    }

    private fun videoIdDe(c: SongEntity): String? =
        c.fuente.takeIf { ID_YOUTUBE.matches(it) } ?: c.id.takeIf { ID_YOUTUBE.matches(it) }

    private suspend fun getDefaultRecommendations(): List<SearchItem> {
        // Sin historial: mostrar tendencias generales
        return try {
            innertube.search("top music ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}").filter { it.isSong }.take(10)
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Extrae el artista principal de un string con múltiples artistas.
     * Ej: "Jamie Paige, 0TS, 11vein, 4M-P..." → "Jamie Paige"
     * Ej: "Bad Bunny feat. J Balvin" → "Bad Bunny"
     * Ej: "Rosalía & Ozuna" → "Rosalía"
     */
    fun extractMainArtist(rawArtist: String): String {
        return rawArtist
            .split(Regex(",|feat\\.|ft\\.|&|×|x |\\+|and ", RegexOption.IGNORE_CASE))
            .firstOrNull()
            ?.trim()
            ?.take(50)
            ?: rawArtist.take(50)
    }

    private companion object {
        const val MAXIMO = 20
    }
}
