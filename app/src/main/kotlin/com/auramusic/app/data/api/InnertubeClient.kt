package com.auramusic.app.data.api

import android.net.Uri
import android.util.Log
import com.auramusic.app.domain.model.AudioQuality
import com.auramusic.app.domain.model.CodecAudio
import com.auramusic.app.util.sinUrls
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.schabi.newpipe.extractor.MediaFormat
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InnertubeClient @Inject constructor() {

    private val TAG = "Innertube"
    private val INTENTOS_AUDIO = 3
    private val PAGINA_ARTISTA = "MUSIC_PAGE_TYPE_ARTIST"
    private val PAGINA_ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
    private val PAGINA_PLAYLIST = "MUSIC_PAGE_TYPE_PLAYLIST"
    private val DURACION = Regex("\\d+:\\d{2}(:\\d{2})?")
    private val MAX_CANCIONES_LISTA = 500
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        engine {
            config {
                followRedirects(true)
                connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    private fun webMusicContext() = buildJsonObject {
        put("client", buildJsonObject {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20240101.01.00")
            put("hl", "es"); put("gl", "US")
        })
    }

    // ── BÚSQUEDA ─────────────────────────────────────────────────────────
    suspend fun search(query: String): List<SearchItem> =
        buscar(query).getOrElse { Log.e(TAG, "search: ${it.message.sinUrls()}"); emptyList() }

    /** Como [search], pero devuelve el fallo (p. ej. sin conexión) en vez de una lista vacía. */
    suspend fun buscar(query: String): Result<List<SearchItem>> {
        return try {
            val response: JsonObject = client.post(
                "https://music.youtube.com/youtubei/v1/search"
            ) {
                contentType(ContentType.Application.Json)
                header("User-Agent", "Mozilla/5.0")
                header("Origin", "https://music.youtube.com")
                header("Referer", "https://music.youtube.com/")
                header("X-YouTube-Client-Name", "67")
                header("X-YouTube-Client-Version", "1.20240101.01.00")
                setBody(buildJsonObject {
                    put("context", webMusicContext())
                    put("query", query)
                    put("params", "EgWKAQIIAWoKEAoQAxAEEAkQBQ==")
                })
            }.body()
            Result.success(parseSearchResults(response))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── BÚSQUEDA COMPLETA: resultado principal y artistas ────────────────
    // Sin el filtro de "solo canciones", YouTube Music devuelve primero una
    // tarjeta con el resultado más probable (artista o canción) y una lista mixta
    // donde vienen los artistas. Las canciones se siguen sacando de [buscar].
    suspend fun buscarTodo(query: String): Result<ResultadoBusqueda> = try {
        val response: JsonObject = client.post("https://music.youtube.com/youtubei/v1/search") {
            contentType(ContentType.Application.Json)
            header("User-Agent", "Mozilla/5.0")
            header("Origin", "https://music.youtube.com")
            header("Referer", "https://music.youtube.com/")
            header("X-YouTube-Client-Name", "67")
            header("X-YouTube-Client-Version", "1.20240101.01.00")
            setBody(buildJsonObject {
                put("context", webMusicContext())
                put("query", query)
            })
        }.body()
        Result.success(parseBusquedaCompleta(response))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Solo artistas (chip "Artistas" de la búsqueda): el filtro de artistas de YT Music. */
    suspend fun buscarArtistas(query: String): Result<List<ArtistaItem>> = try {
        val response: JsonObject = client.post("https://music.youtube.com/youtubei/v1/search") {
            contentType(ContentType.Application.Json)
            header("User-Agent", "Mozilla/5.0")
            header("Origin", "https://music.youtube.com")
            header("Referer", "https://music.youtube.com/")
            header("X-YouTube-Client-Name", "67")
            header("X-YouTube-Client-Version", "1.20240101.01.00")
            setBody(buildJsonObject {
                put("context", webMusicContext())
                put("query", query)
                put("params", "EgWKAQIgAWoKEAoQAxAEEAkQBQ==")
            })
        }.body()
        val secciones = response["contents"]?.jsonObject
            ?.get("tabbedSearchResultsRenderer")?.jsonObject
            ?.get("tabs")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("tabRenderer")?.jsonObject?.get("content")?.jsonObject
            ?.get("sectionListRenderer")?.jsonObject?.get("contents")?.jsonArray.orEmpty()
        Result.success(secciones.flatMap { s ->
            s.jsonObject["musicShelfRenderer"]?.jsonObject?.get("contents")?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject?.let(::filaArtista) }
        }.distinctBy { it.browseId })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    // ── PÁGINA DE ARTISTA, ÁLBUM Y PLAYLIST (browse) ─────────────────────
    private suspend fun browse(cuerpo: JsonObjectBuilder.() -> Unit): JsonObject =
        client.post("https://music.youtube.com/youtubei/v1/browse") {
            contentType(ContentType.Application.Json)
            header("User-Agent", "Mozilla/5.0")
            header("Origin", "https://music.youtube.com")
            header("Referer", "https://music.youtube.com/")
            header("X-YouTube-Client-Name", "67")
            header("X-YouTube-Client-Version", "1.20240101.01.00")
            setBody(buildJsonObject { put("context", webMusicContext()); cuerpo() })
        }.body()

    /**
     * Página de un artista de YT Music: cabecera, canciones populares (y el id de
     * su lista completa), carruseles de álbumes, sencillos, playlists y artistas
     * similares, y biografía. Los carruseles de videos se descartan.
     */
    suspend fun artista(browseId: String): Result<PaginaArtista> = try {
        val r = browse { put("browseId", browseId) }
        val h = r["header"]?.jsonObject?.get("musicImmersiveHeaderRenderer")?.jsonObject
            ?: r["header"]?.jsonObject?.get("musicVisualHeaderRenderer")?.jsonObject
            ?: error("Sin cabecera de artista")
        val nombre = h["title"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content ?: error("Sin nombre")
        val oyentes = h["monthlyListenerCount"]?.jsonObject?.get("runs")?.jsonArray
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.orEmpty()
        val secciones = r["contents"]?.jsonObject?.get("singleColumnBrowseResultsRenderer")?.jsonObject
            ?.get("tabs")?.jsonArray?.firstOrNull()?.jsonObject?.get("tabRenderer")?.jsonObject
            ?.get("content")?.jsonObject?.get("sectionListRenderer")?.jsonObject?.get("contents")?.jsonArray.orEmpty()
        var populares = emptyList<SearchItem>()
        var todas: String? = null
        var bio = ""
        val carruseles = mutableListOf<SeccionArtista>()
        for (sec in secciones) {
            val o = sec.jsonObject
            o["musicShelfRenderer"]?.jsonObject?.let { shelf ->
                if (populares.isEmpty()) {
                    populares = shelf["contents"]?.jsonArray.orEmpty().mapNotNull { f ->
                        f.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject?.let(::filaCancion)
                    }.filter { it.isSong }.map { if (it.artistId.isBlank()) it.copy(artistId = browseId) else it }
                    todas = destino(shelf["title"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("navigationEndpoint")).second
                        ?: destino(shelf["bottomEndpoint"]).second
                }
            }
            o["musicCarouselShelfRenderer"]?.jsonObject?.let { c -> carrusel(c)?.let { carruseles += it } }
            o["musicDescriptionShelfRenderer"]?.jsonObject?.let { d ->
                bio = d["description"]?.jsonObject?.get("runs")?.jsonArray
                    ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.orEmpty()
            }
        }
        if (bio.isBlank()) bio = h["description"]?.jsonObject?.get("runs")?.jsonArray
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.orEmpty()
        Result.success(PaginaArtista(
            browseId = browseId, nombre = nombre, fotoUrl = miniaturaDe(h["thumbnail"]), oyentes = oyentes,
            biografia = bio, populares = populares, todasId = todas,
            aleatorio = listaDeBoton(h["playButton"]), mix = listaDeBoton(h["startRadioButton"]),
            secciones = carruseles
        ))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Un carrusel de la página de artista, o null si es de videos (fuera de alcance). */
    private fun carrusel(c: JsonObject): SeccionArtista? {
        val titulo = c["header"]?.jsonObject?.get("musicCarouselShelfBasicHeaderRenderer")?.jsonObject
            ?.get("title")?.jsonObject?.get("runs")?.jsonArray
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() } ?: return null
        val items = c["contents"]?.jsonArray.orEmpty().mapNotNull { e ->
            val r = e.jsonObject["musicTwoRowItemRenderer"]?.jsonObject ?: return@mapNotNull null
            // Los videos y "en directo" llevan watchEndpoint, no una página: se descartan.
            val (tipo, id) = destino(r["navigationEndpoint"])
            if (id == null || tipo !in setOf(PAGINA_ALBUM, PAGINA_PLAYLIST, PAGINA_ARTISTA)) return@mapNotNull null
            ItemColeccion(
                browseId = id, tipo = tipo!!,
                titulo = r["title"]?.jsonObject?.get("runs")?.jsonArray
                    ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.orEmpty(),
                subtitulo = r["subtitle"]?.jsonObject?.get("runs")?.jsonArray
                    ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.orEmpty(),
                portadaUrl = r["thumbnailRenderer"]?.jsonObject?.get("musicThumbnailRenderer")?.jsonObject
                    ?.get("thumbnail")?.jsonObject?.get("thumbnails")?.jsonArray
                    ?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content.orEmpty()
            )
        }
        return if (items.isEmpty()) null else SeccionArtista(titulo, items)
    }

    // Botón de la cabecera (Aleatorio / Mix) → la cola de YT Music que abre.
    private fun listaDeBoton(boton: JsonElement?): PlaylistRef? {
        val w = (boton as? JsonObject)?.get("buttonRenderer")?.jsonObject?.get("navigationEndpoint")?.jsonObject
            ?.let { it["watchEndpoint"] ?: it["watchPlaylistEndpoint"] }?.jsonObject ?: return null
        val id = w["playlistId"]?.jsonPrimitive?.content ?: return null
        return PlaylistRef(id, w["params"]?.jsonPrimitive?.content)
    }

    /**
     * Álbum (MPREb…) o playlist (VL…) con todas sus canciones; las playlists
     * largas llegan por partes (continuations), hasta [MAX_CANCIONES_LISTA].
     */
    suspend fun coleccion(browseId: String): Result<PaginaColeccion> = try {
        val r = browse { put("browseId", browseId) }
        val dos = r["contents"]?.jsonObject?.get("twoColumnBrowseResultsRenderer")?.jsonObject
            ?: error("Formato de colección desconocido")
        val h = dos["tabs"]?.jsonArray?.firstOrNull()?.jsonObject?.get("tabRenderer")?.jsonObject
            ?.get("content")?.jsonObject?.get("sectionListRenderer")?.jsonObject?.get("contents")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("musicResponsiveHeaderRenderer")?.jsonObject
        fun texto(e: JsonElement?) = (e as? JsonObject)?.get("runs")?.jsonArray
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.orEmpty()
        val portada = miniaturaDe(h?.get("thumbnail"))
        val autor = h?.get("straplineTextOne")?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject
        val autorNombre = autor?.get("text")?.jsonPrimitive?.content.orEmpty()
        val autorId = destino(autor?.get("navigationEndpoint")).takeIf { it.first == PAGINA_ARTISTA }?.second
        val shelf = dos["secondaryContents"]?.jsonObject?.get("sectionListRenderer")?.jsonObject
            ?.get("contents")?.jsonArray?.firstOrNull()?.jsonObject
            ?.let { it["musicPlaylistShelfRenderer"] ?: it["musicShelfRenderer"] }?.jsonObject
        val canciones = mutableListOf<SearchItem>()
        var filas = shelf?.get("contents")?.jsonArray.orEmpty()
        while (true) {
            var siguiente: String? = null
            for (f in filas) {
                val o = f.jsonObject
                o["musicResponsiveListItemRenderer"]?.jsonObject?.let(::filaCancion)?.let { canciones += it }
                o["continuationItemRenderer"]?.jsonObject?.let { c ->
                    siguiente = c["continuationEndpoint"]?.jsonObject?.get("continuationCommand")?.jsonObject
                        ?.get("token")?.jsonPrimitive?.content
                }
            }
            val token = siguiente ?: break
            if (canciones.size >= MAX_CANCIONES_LISTA) break
            val mas = browse { put("continuation", token) }
            filas = mas["onResponseReceivedActions"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("appendContinuationItemsAction")?.jsonObject?.get("continuationItems")?.jsonArray.orEmpty()
            if (filas.isEmpty()) break
        }
        // Las pistas de un álbum no traen portada ni, a veces, artista: se toman del álbum.
        val pistas = canciones.filter { it.isSong }.distinctBy { it.videoId }.map {
            it.copy(
                thumbnailUrl = it.thumbnailUrl.ifBlank { portada },
                artist = it.artist.ifBlank { autorNombre },
                artistId = it.artistId.ifBlank { autorId.orEmpty() }
            )
        }
        Result.success(PaginaColeccion(
            browseId = browseId, titulo = texto(h?.get("title")), subtitulo = texto(h?.get("subtitle")),
            autor = autorNombre, autorId = autorId, autorFotoUrl = miniaturaDe(h?.get("straplineThumbnail")),
            portadaUrl = portada, pie = texto(h?.get("secondSubtitle")), canciones = pistas,
            esAlbum = browseId.startsWith("MPRE")
        ))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * browseId del artista cuando solo se sabe su nombre (biblioteca, reproductor):
     * el que coincide exacto (sin mayúsculas) o, si no, el primero de la búsqueda.
     */
    suspend fun idArtista(nombre: String): Result<String?> = buscarArtistas(nombre).map { lista ->
        (lista.firstOrNull { it.nombre.equals(nombre.trim(), ignoreCase = true) } ?: lista.firstOrNull())?.browseId
    }

    /** Canciones de una cola de YT Music (botones Aleatorio y Mix de un artista). */
    suspend fun colaDePlaylist(lista: PlaylistRef): List<SearchItem> = try {
        val response: JsonObject = client.post("https://music.youtube.com/youtubei/v1/next") {
            contentType(ContentType.Application.Json)
            header("User-Agent", "Mozilla/5.0")
            header("Origin", "https://music.youtube.com")
            header("Referer", "https://music.youtube.com/")
            header("X-YouTube-Client-Name", "67")
            header("X-YouTube-Client-Version", "1.20240101.01.00")
            setBody(buildJsonObject {
                put("context", webMusicContext())
                put("playlistId", lista.playlistId)
                lista.params?.let { put("params", Uri.decode(it)) }
                put("isAudioOnly", true)
            })
        }.body()
        parseNextRadio(response, semilla = "")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "colaDePlaylist: ${e.message.sinUrls()}"); emptyList()
    }

    suspend fun getSearchSuggestions(query: String): List<String> {
        return try {
            val response: JsonObject = client.post(
                "https://music.youtube.com/youtubei/v1/music/get_search_suggestions"
            ) {
                contentType(ContentType.Application.Json)
                header("User-Agent", "Mozilla/5.0")
                header("Origin", "https://music.youtube.com")
                setBody(buildJsonObject {
                    put("context", webMusicContext())
                    put("input", query)
                })
            }.body()
            response["contents"]?.jsonArray
                ?.flatMap {
                    it.jsonObject["searchSuggestionsSectionRenderer"]
                        ?.jsonObject?.get("contents")?.jsonArray ?: emptyList()
                }?.mapNotNull {
                    it.jsonObject["searchSuggestionRenderer"]?.jsonObject
                        ?.get("suggestion")?.jsonObject?.get("runs")?.jsonArray
                        ?.joinToString("") { r ->
                            r.jsonObject["text"]?.jsonPrimitive?.content ?: ""
                        }
                } ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    // ── RELACIONADAS (radio de YouTube Music, endpoint next) ─────────────
    // Fix 3: pide la cola "a continuación" al endpoint next de YouTube Music
    // (cliente WEB_REMIX), NO a YouTube general. Devuelve canciones reales con
    // metadatos musicales; lo que no los tenga se descarta silenciosamente.
    suspend fun relacionadasYtm(videoId: String): List<SearchItem> {
        if (videoId.isBlank()) return emptyList()
        return try {
            val response: JsonObject = client.post(
                "https://music.youtube.com/youtubei/v1/next"
            ) {
                contentType(ContentType.Application.Json)
                header("User-Agent", "Mozilla/5.0")
                header("Origin", "https://music.youtube.com")
                header("Referer", "https://music.youtube.com/")
                header("X-YouTube-Client-Name", "67")
                header("X-YouTube-Client-Version", "1.20240101.01.00")
                setBody(buildJsonObject {
                    put("context", webMusicContext())
                    put("videoId", videoId)
                    put("playlistId", "RDAMVM$videoId")   // radio de la canción
                    put("isAudioOnly", true)
                })
            }.body()
            parseNextRadio(response, videoId)
        } catch (e: Exception) {
            Log.e(TAG, "next: ${e.message.sinUrls()}"); emptyList()
        }
    }

    /**
     * Datos de UNA canción por su videoId (título, artista, portada), para las que
     * llegan por enlace y no están en la biblioteca. La radio de YT Music empieza
     * por la propia canción, así que se toma de ahí. Null si no se encuentra.
     */
    suspend fun cancionPorId(videoId: String): SearchItem? = try {
        val response: JsonObject = client.post("https://music.youtube.com/youtubei/v1/next") {
            contentType(ContentType.Application.Json)
            header("User-Agent", "Mozilla/5.0")
            header("Origin", "https://music.youtube.com")
            header("Referer", "https://music.youtube.com/")
            header("X-YouTube-Client-Name", "67")
            header("X-YouTube-Client-Version", "1.20240101.01.00")
            setBody(buildJsonObject {
                put("context", webMusicContext())
                put("videoId", videoId)
                put("playlistId", "RDAMVM$videoId")
                put("isAudioOnly", true)
            })
        }.body()
        parseNextRadio(response, semilla = "", soloId = videoId).firstOrNull()
    } catch (e: Exception) {
        Log.w(TAG, "cancionPorId: ${e.message.sinUrls()}"); null
    }

    private fun parseNextRadio(response: JsonObject, semilla: String, soloId: String? = null): List<SearchItem> {
        val items = mutableListOf<SearchItem>()
        try {
            val panel = response["contents"]?.jsonObject
                ?.get("singleColumnMusicWatchNextResultsRenderer")?.jsonObject
                ?.get("tabbedRenderer")?.jsonObject
                ?.get("watchNextTabbedResultsRenderer")?.jsonObject
                ?.get("tabs")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("tabRenderer")?.jsonObject?.get("content")?.jsonObject
                ?.get("musicQueueRenderer")?.jsonObject?.get("content")?.jsonObject
                ?.get("playlistPanelRenderer")?.jsonObject
                ?.get("contents")?.jsonArray ?: return emptyList()
            for (entry in panel) {
                val r = entry.jsonObject["playlistPanelVideoRenderer"]?.jsonObject ?: continue
                val vid = r["videoId"]?.jsonPrimitive?.content ?: continue
                if (vid.isBlank() || vid == semilla) continue
                if (soloId != null && vid != soloId) continue
                val title = r["title"]?.jsonObject?.get("runs")?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                // Descarta lo que no tenga metadatos musicales válidos (Fix 3).
                if (title.isNullOrBlank()) continue
                val byline = (r["longBylineText"] ?: r["shortBylineText"])?.jsonObject
                    ?.get("runs")?.jsonArray
                val artist = byline?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
                val thumb = r["thumbnail"]?.jsonObject?.get("thumbnails")?.jsonArray
                    ?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content ?: ""
                val explicit = r["badges"]?.jsonArray?.any { b ->
                    b.jsonObject["musicInlineBadgeRenderer"]?.jsonObject
                        ?.get("icon")?.jsonObject?.get("iconType")
                        ?.jsonPrimitive?.content == "MUSIC_EXPLICIT_BADGE"
                } ?: false
                items.add(SearchItem(vid, title, artist, thumb, "", true, explicit))
            }
        } catch (_: Exception) {}
        return items
    }

    // ── URL DE AUDIO — 4 métodos en cascada ──────────────────────────────
    // URLs ya resueltas y comprobadas, para que "Precargar siguiente" haga
    // instantáneo el salto a la próxima canción. Clave: videoId|calidad|códec.
    private data class UrlGuardada(val info: AudioInfo, val caducaMs: Long)
    private val urlsResueltas = ConcurrentHashMap<String, UrlGuardada>()

    // Resoluciones en curso: si la precarga y ExoPlayer piden la misma canción a la
    // vez, comparten una sola llamada a NewPipe (antes se lanzaban 2-3 iguales).
    // Corren en su propio scope: cancelar a quien la pidió (p. ej. la precarga al
    // saltar de canción) no la aborta, y el resultado igual queda guardado.
    private val enCurso = ConcurrentHashMap<String, Deferred<AudioInfo?>>()
    private val scopeResolucion = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Descarta las URLs guardadas de un vídeo (p. ej. si ExoPlayer recibió un error con ella). */
    fun olvidarUrl(videoId: String) {
        urlsResueltas.keys.removeAll { it.startsWith("$videoId|") }
    }

    suspend fun getAudioUrl(videoId: String, calidad: AudioQuality, codec: CodecAudio): AudioInfo? {
        Log.d(TAG, "=== getAudioUrl($videoId, $calidad, $codec) ===")
        val clave = "$videoId|$calidad|$codec"
        urlsResueltas[clave]?.let { guardada ->
            if (System.currentTimeMillis() < guardada.caducaMs) return guardada.info
            urlsResueltas.remove(clave)
        }

        val nueva = scopeResolucion.async(start = CoroutineStart.LAZY) {
            resolverConReintento(videoId, calidad, codec, clave)
        }
        val tarea = enCurso.putIfAbsent(clave, nueva)
        if (tarea != null) { nueva.cancel(); return tarea.await() }
        nueva.invokeOnCompletion { enCurso.remove(clave, nueva) }
        nueva.start()
        return nueva.await()
    }

    private suspend fun resolverConReintento(
        videoId: String, calidad: AudioQuality, codec: CodecAudio, clave: String
    ): AudioInfo? {
        // Única fuente: NewPipe Extractor (descifra localmente y resuelve el poToken).
        // Medido 2026-10: los clientes Innertube directos (TVHTML5_SIMPLY_EMBEDDED,
        // WEB_CREATOR, IOS, ANDROID_VR, *_MUSIC, WEB_REMIX) fallan con canciones de
        // YouTube Music (LOGIN_REQUIRED / ERROR / 403 a mitad), por eso se quitaron.
        //
        // Reintento: a veces (~1 de cada 6 medido) YouTube entrega URLs que dan 403
        // en todas las calidades; pedirlas de nuevo a NewPipe lo resuelve. Cada URL
        // se comprueba con una petición de 1 byte (~100 ms) antes de devolverla.
        repeat(INTENTOS_AUDIO) { intento ->
            val info = tryNewPipe(videoId, calidad, codec) ?: return@repeat
            when (comprobarUrl(info.url)) {
                EstadoUrl.VALIDA -> {
                    Log.d(TAG, "✅ NewPipe (intento ${intento + 1}): ${info.mimeType} ${info.bitrate}kbps")
                    urlsResueltas[clave] = UrlGuardada(info, caducidadDe(info.url))
                    return info
                }
                // Sin red no tiene sentido reintentar: se devuelve y ExoPlayer/la
                // descarga reportan el error de conexión como siempre.
                EstadoUrl.SIN_RED -> return info
                EstadoUrl.RECHAZADA -> Log.w(TAG, "URL rechazada (intento ${intento + 1}), reintentando")
            }
        }

        Log.e(TAG, "❌ NewPipe no pudo resolver $videoId")
        return null
    }

    // Las URLs de googlevideo llevan "expire=<segundos unix>" (~6 h). Se guardan
    // hasta 30 min antes de caducar; sin el parámetro, 1 hora.
    private fun caducidadDe(url: String): Long {
        val expira = runCatching { Uri.parse(url).getQueryParameter("expire")?.toLong() }.getOrNull()
        return if (expira != null) expira * 1000 - 30 * 60 * 1000
               else System.currentTimeMillis() + 60 * 60 * 1000
    }

    private enum class EstadoUrl { VALIDA, RECHAZADA, SIN_RED }

    private suspend fun comprobarUrl(url: String): EstadoUrl = try {
        val codigo = client.get(url) { header(HttpHeaders.Range, "bytes=0-0") }.status.value
        if (codigo in 200..299) EstadoUrl.VALIDA else EstadoUrl.RECHAZADA
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Comprobación de URL sin respuesta: ${e.message.sinUrls()?.take(80)}")
        EstadoUrl.SIN_RED
    }

    // ── NewPipe Extractor ──────────────────────────────────────────────
    private suspend fun tryNewPipe(videoId: String, calidad: AudioQuality, codec: CodecAudio): AudioInfo? =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val url = "https://www.youtube.com/watch?v=$videoId"
                Log.d(TAG, "NewPipe: extracting $url")
                val info = org.schabi.newpipe.extractor.stream.StreamInfo
                    .getInfo(org.schabi.newpipe.extractor.ServiceList.YouTube, url)

                val streams = info.audioStreams
                Log.d(TAG, "NewPipe: ${streams.size} audio streams")
                if (streams.isEmpty()) return@withContext null

                // isUrl() = true significa que es una URL directa reproducible
                val direct = streams.filter { it.isUrl && !it.content.isNullOrEmpty() }
                Log.d(TAG, "NewPipe: ${direct.size} streams directos")
                if (direct.isEmpty()) return@withContext null

                // Códec elegido (AAC = M4A, Opus = WebM); si YouTube no lo ofrece,
                // cualquiera. YouTube da AAC ~48/128 kbps y Opus ~50/70/160 kbps.
                val delCodec = direct.filter {
                    when (codec) {
                        CodecAudio.AAC  -> it.format == MediaFormat.M4A
                        CodecAudio.OPUS -> it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.OPUS
                    }
                }.ifEmpty { direct }
                val porBitrate = delCodec.sortedBy { it.averageBitrate }
                val chosen = when (calidad) {
                    AudioQuality.LOW    -> porBitrate.first()
                    AudioQuality.MEDIUM -> porBitrate.minBy { kotlin.math.abs(it.averageBitrate - 128) }
                    AudioQuality.HIGH   -> porBitrate.last()
                }
                val streamUrl = chosen.content ?: return@withContext null

                Log.d(TAG, "NewPipe URL: ${streamUrl.take(80)}...")
                AudioInfo(streamUrl, chosen.averageBitrate,
                    chosen.format?.mimeType ?: "audio/mp4")
            } catch (e: Exception) {
                Log.w(TAG, "NewPipe: ${e.javaClass.simpleName}: ${e.message.sinUrls()?.take(120)}")
                null
            }
        }

    // ── LETRAS ────────────────────────────────────────────────────────────
    // R: orden de prioridad → LRCLIB; si no encuentra, KuGou como fallback. Si
    // ninguno tiene la letra, null (la UI muestra "no disponible"). Vale igual
    // para streaming y para descargas (el .lrc se guarda de lo que devuelva esto).
    suspend fun getLyrics(title: String, artist: String, durationSec: Int): LyricsResult? {
        letrasLrclib(title, artist, durationSec)?.let { return it }
        letrasKugou(title, artist, durationSec)?.let { return it }
        return null
    }

    private suspend fun letrasLrclib(title: String, artist: String, durationSec: Int): LyricsResult? {
        return try {
            val r = tryLrclib(title, artist, durationSec)
                ?: tryLrclibSearch(title, artist) ?: return null
            val synced = r["syncedLyrics"]?.jsonPrimitive?.contentOrNull
            val plain  = r["plainLyrics"]?.jsonPrimitive?.contentOrNull
            when {
                !synced.isNullOrBlank() -> LyricsResult(parseLrc(synced), true, "LRCLIB")
                !plain.isNullOrBlank()  -> LyricsResult(
                    plain.split("\n").mapIndexed { i, l -> LyricLine(i * 3000L, l) }, false, "LRCLIB"
                )
                else -> null
            }
        } catch (e: Exception) { null }
    }

    // Fallback KuGou (R): busca candidatos y baja la letra en formato lrc (viene en
    // base64). Usa https para respetar la política de cleartext de la app.
    private suspend fun letrasKugou(title: String, artist: String, durationSec: Int): LyricsResult? {
        return try {
            val keyword = if (artist.isNotBlank()) "$artist - $title" else title
            val busqueda: JsonObject = client.get("https://lyrics.kugou.com/search") {
                parameter("ver", 1); parameter("man", "yes"); parameter("client", "mobi")
                parameter("keyword", keyword)
                if (durationSec > 0) parameter("duration", durationSec * 1000)
                header("User-Agent", "Mozilla/5.0")
            }.body()
            val cand = busqueda["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val id = cand["id"]?.jsonPrimitive?.content ?: return null
            val accesskey = cand["accesskey"]?.jsonPrimitive?.content ?: return null

            val descarga: JsonObject = client.get("https://lyrics.kugou.com/download") {
                parameter("ver", 1); parameter("client", "pc"); parameter("id", id)
                parameter("accesskey", accesskey); parameter("fmt", "lrc"); parameter("charset", "utf8")
                header("User-Agent", "Mozilla/5.0")
            }.body()
            val b64 = descarga["content"]?.jsonPrimitive?.content ?: return null
            val lrc = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            // KuGou suele abrir con una línea "Artista - Título" que no se canta.
            val lineas = parseLrc(lrc).dropWhile { l ->
                l.text.isBlank() || (l.text.contains(" - ") && l.text.contains(title, ignoreCase = true))
            }
            if (lineas.isNotEmpty()) LyricsResult(lineas, true, "KuGou") else null
        } catch (e: Exception) {
            Log.w(TAG, "KuGou: ${e.message.sinUrls()?.take(80)}"); null
        }
    }

    // D2: parsea un .lrc LOCAL (descarga offline) a LyricsResult, sin tocar la red.
    // Si tiene timestamps [mm:ss.xx] es sincronizada; si no, líneas planas.
    fun parsearLrcLocal(contenido: String): LyricsResult? {
        val sincronizadas = parseLrc(contenido)
        if (sincronizadas.isNotEmpty()) return LyricsResult(sincronizadas, true, "Descargada")
        val planas = contenido.lines().filter { it.isNotBlank() }
            .mapIndexed { i, l -> LyricLine(i * 3000L, l) }
        return if (planas.isEmpty()) null else LyricsResult(planas, false, "Descargada")
    }

    private suspend fun tryLrclib(t: String, a: String, d: Int): JsonObject? = try {
        val r = client.get("https://lrclib.net/api/get") {
            parameter("track_name", t); parameter("artist_name", a); parameter("duration", d)
            header("User-Agent", "AuraMusic/6.0")
        }
        if (r.status.value == 200) r.body() else null
    } catch (e: Exception) { null }

    private suspend fun tryLrclibSearch(t: String, a: String): JsonObject? = try {
        val r: JsonArray = client.get("https://lrclib.net/api/search") {
            parameter("track_name", t); parameter("artist_name", a)
            header("User-Agent", "AuraMusic/6.0")
        }.body()
        r.firstOrNull()?.jsonObject
    } catch (e: Exception) { null }

    /**
     * Las líneas VACÍAS se conservan: en LRCLIB marcan el final de un verso o un
     * tramo instrumental, y el visor las muestra como "♫" (antes se tiraban y la
     * línea anterior seguía resaltada aunque no se cantara nada). También admite
     * varios tiempos en una línea ("[00:12.00][00:45.00]coro") y la etiqueta [offset:].
     */
    private fun parseLrc(lrc: String): List<LyricLine> {
        val tiempo = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")
        // [offset:+500] adelanta la letra medio segundo (estándar LRC).
        val offset = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)
            .find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return lrc.lines().flatMap { linea ->
            var resto = linea.trim()
            val tiempos = mutableListOf<Long>()
            while (true) {
                val m = tiempo.matchAt(resto, 0) ?: break
                tiempos += m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 +
                    m.groupValues[3].padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
                resto = resto.substring(m.range.last + 1)
            }
            tiempos.map { LyricLine((it - offset).coerceAtLeast(0L), resto.trim()) }
        }.sortedBy { it.timeMs }
            // Una letra que solo tiene marcas vacías no es una letra.
            .takeIf { l -> l.any { it.text.isNotBlank() } }.orEmpty()
    }

    private fun parseSearchResults(response: JsonObject): List<SearchItem> {
        val items = mutableListOf<SearchItem>()
        try {
            val contents = response["contents"]?.jsonObject
                ?.get("tabbedSearchResultsRenderer")?.jsonObject
                ?.get("tabs")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("tabRenderer")?.jsonObject?.get("content")?.jsonObject
                ?.get("sectionListRenderer")?.jsonObject?.get("contents")?.jsonArray
                ?: return emptyList()
            for (section in contents) {
                val shelf = section.jsonObject["musicShelfRenderer"]?.jsonObject ?: continue
                for (item in shelf["contents"]?.jsonArray ?: continue) {
                    val r = item.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject ?: continue
                    filaCancion(r)?.let { items.add(it) }
                }
            }
        } catch (_: Exception) {}
        return items
    }

    /** Una fila de canción (musicResponsiveListItemRenderer) de búsqueda o de una tarjeta. */
    private fun filaCancion(r: JsonObject): SearchItem? {
        val flex = r["flexColumns"]?.jsonArray ?: return null
        val title = runsDe(flex, 0)?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: return null
        val watch = r["overlay"]?.jsonObject
            ?.get("musicItemThumbnailOverlayRenderer")?.jsonObject
            ?.get("content")?.jsonObject?.get("musicPlayButtonRenderer")?.jsonObject
            ?.get("playNavigationEndpoint")?.jsonObject?.get("watchEndpoint")?.jsonObject
        val videoId = watch?.get("videoId")?.jsonPrimitive?.content ?: return null
        val secondCol = runsDe(flex, 1)
        val artist   = secondCol?.getOrNull(0)?.jsonObject
            ?.get("text")?.jsonPrimitive?.content ?: ""
        // La duración va en una columna fija (álbumes, playlists) o al final de la 2.ª.
        val duration = r["fixedColumns"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("musicResponsiveListItemFixedColumnRenderer")?.jsonObject?.get("text")?.jsonObject
            ?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: secondCol?.lastOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?.takeIf { DURACION.matches(it) } ?: ""
        // browseId del artista (el run que enlaza a su página), para abrir su catálogo.
        val artistId = secondCol?.firstNotNullOfOrNull { run ->
            destino(run.jsonObject["navigationEndpoint"]).takeIf { it.first == PAGINA_ARTISTA }?.second
        } ?: ""
        val musicType = watch["watchEndpointMusicSupportedConfigs"]?.jsonObject
            ?.get("watchEndpointMusicConfig")?.jsonObject
            ?.get("musicVideoType")?.jsonPrimitive?.content
        // Q: badge "MUSIC_EXPLICIT_BADGE" en la lista de badges del ítem.
        val explicit = r["badges"]?.jsonArray?.any { b ->
            b.jsonObject["musicInlineBadgeRenderer"]?.jsonObject
                ?.get("icon")?.jsonObject?.get("iconType")
                ?.jsonPrimitive?.content == "MUSIC_EXPLICIT_BADGE"
        } ?: false
        return SearchItem(videoId, title, artist, miniaturaDe(r["thumbnail"]), duration,
            musicType != "MUSIC_VIDEO_TYPE_UGC", explicit, artistId)
    }

    private fun parseBusquedaCompleta(response: JsonObject): ResultadoBusqueda {
        val secciones = response["contents"]?.jsonObject
            ?.get("tabbedSearchResultsRenderer")?.jsonObject
            ?.get("tabs")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("tabRenderer")?.jsonObject?.get("content")?.jsonObject
            ?.get("sectionListRenderer")?.jsonObject?.get("contents")?.jsonArray
            ?: return ResultadoBusqueda(null, emptyList())
        var principal: ResultadoPrincipal? = null
        val artistas = mutableListOf<ArtistaItem>()
        for (seccion in secciones) {
            val s = seccion.jsonObject
            s["musicCardShelfRenderer"]?.jsonObject?.let { principal = runCatching { tarjetaPrincipal(it) }.getOrNull() }
            // La lista mixta llega en itemSectionRenderer (antes, musicShelfRenderer).
            val filas = (s["itemSectionRenderer"] ?: s["musicShelfRenderer"])?.jsonObject
                ?.get("contents")?.jsonArray ?: continue
            for (fila in filas) {
                fila.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject?.let(::filaArtista)?.let { artistas += it }
            }
        }
        // El artista de la tarjeta no se repite en la fila de artistas.
        val idPrincipal = (principal as? ResultadoPrincipal.Artista)?.artista?.browseId
        return ResultadoBusqueda(principal, artistas.filter { it.browseId != idPrincipal }.distinctBy { it.browseId })
    }

    /** Una fila de artista (musicResponsiveListItemRenderer que lleva a su página), o null. */
    private fun filaArtista(r: JsonObject): ArtistaItem? {
        val (tipo, browseId) = destino(r["navigationEndpoint"])
        if (tipo != PAGINA_ARTISTA || browseId == null) return null
        val flex = r["flexColumns"]?.jsonArray ?: return null
        val nombre = runsDe(flex, 0)?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: return null
        // "Artista • 9,11 M usuarios mensuales" → se queda la cifra.
        val oyentes = runsDe(flex, 1)?.lastOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
        return ArtistaItem(browseId, nombre, miniaturaDe(r["thumbnail"]), oyentes)
    }

    private fun tarjetaPrincipal(card: JsonObject): ResultadoPrincipal? {
        val titulo = card["title"]?.jsonObject?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val nombre = titulo["text"]?.jsonPrimitive?.content ?: return null
        val subtitulo = card["subtitle"]?.jsonObject?.get("runs")?.jsonArray
        val foto = miniaturaDe(card["thumbnail"])
        val (tipo, browseId) = destino(titulo["navigationEndpoint"])
        if (tipo == PAGINA_ARTISTA && browseId != null) {
            val canciones = card["contents"]?.jsonArray.orEmpty().mapNotNull { c ->
                c.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject?.let { filaCancion(it) }
                    // En la tarjeta la 2.ª columna dice "Canción • 3:40", no el artista.
                    ?.copy(artist = nombre, artistId = browseId)
            }
            val listas = card["buttons"]?.jsonArray.orEmpty().mapNotNull { b ->
                b.jsonObject["buttonRenderer"]?.jsonObject?.get("command")?.jsonObject
                    ?.get("watchPlaylistEndpoint")?.jsonObject?.let { w ->
                        w["playlistId"]?.jsonPrimitive?.content?.let { PlaylistRef(it, w["params"]?.jsonPrimitive?.content) }
                    }
            }
            // "RDEM…" es el Mix (radio del artista); la otra, sus canciones al azar.
            val (mix, aleatorio) = listas.partition { it.playlistId.startsWith("RDEM") }
            val oyentes = subtitulo?.lastOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
            return ResultadoPrincipal.Artista(ArtistaItem(browseId, nombre, foto, oyentes),
                canciones, aleatorio.firstOrNull(), mix.firstOrNull())
        }
        // Canción o vídeo: "Vídeo • Olivia Rodrigo • 53 M de visualizaciones • 5:15".
        val watch = titulo["navigationEndpoint"]?.jsonObject?.get("watchEndpoint")?.jsonObject ?: return null
        val videoId = watch["videoId"]?.jsonPrimitive?.content ?: return null
        val musicType = watch["watchEndpointMusicSupportedConfigs"]?.jsonObject
            ?.get("watchEndpointMusicConfig")?.jsonObject?.get("musicVideoType")?.jsonPrimitive?.content
        val artistaRun = subtitulo?.firstOrNull { destino(it.jsonObject["navigationEndpoint"]).first == PAGINA_ARTISTA }?.jsonObject
        val duracion = subtitulo?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            ?.lastOrNull { it.matches(Regex("\\d+:\\d{2}(:\\d{2})?")) } ?: ""
        // Aquí isSong solo vale para la canción (ATV): el videoclip oficial (OMV) no lo es.
        return ResultadoPrincipal.Cancion(SearchItem(videoId, nombre,
            artistaRun?.get("text")?.jsonPrimitive?.content ?: "", foto, duracion,
            musicType == "MUSIC_VIDEO_TYPE_ATV", false,
            destino(artistaRun?.get("navigationEndpoint")).second ?: ""))
    }

    private fun runsDe(flex: JsonArray, columna: Int): JsonArray? = flex.getOrNull(columna)?.jsonObject
        ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
        ?.get("text")?.jsonObject?.get("runs")?.jsonArray

    private fun miniaturaDe(thumbnail: JsonElement?): String = thumbnail?.jsonObject
        ?.get("musicThumbnailRenderer")?.jsonObject
        ?.get("thumbnail")?.jsonObject?.get("thumbnails")?.jsonArray
        ?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content ?: ""

    /** pageType y browseId de un navigationEndpoint de tipo browse (artista, álbum, playlist). */
    private fun destino(ep: JsonElement?): Pair<String?, String?> {
        val b = (ep as? JsonObject)?.get("browseEndpoint")?.jsonObject ?: return null to null
        val tipo = b["browseEndpointContextSupportedConfigs"]?.jsonObject
            ?.get("browseEndpointContextMusicConfig")?.jsonObject?.get("pageType")?.jsonPrimitive?.content
        return tipo to b["browseId"]?.jsonPrimitive?.content
    }
}

@kotlinx.serialization.Serializable
data class SearchItem(val videoId: String, val title: String, val artist: String,
    val thumbnailUrl: String, val duration: String, val isSong: Boolean = true,
    val explicit: Boolean = false,   // Q: badge de contenido explícito de YTM
    val artistId: String = "")       // browseId del artista en YT Music ("" si no se sabe)

data class ArtistaItem(val browseId: String, val nombre: String, val fotoUrl: String, val subtitulo: String)
data class PlaylistRef(val playlistId: String, val params: String?)
data class ResultadoBusqueda(val principal: ResultadoPrincipal?, val artistas: List<ArtistaItem>)

sealed class ResultadoPrincipal {
    data class Cancion(val item: SearchItem) : ResultadoPrincipal()
    data class Artista(val artista: ArtistaItem, val canciones: List<SearchItem>,
        val aleatorio: PlaylistRef?, val mix: PlaylistRef?) : ResultadoPrincipal()
}
data class PaginaArtista(
    val browseId: String, val nombre: String, val fotoUrl: String, val oyentes: String, val biografia: String,
    val populares: List<SearchItem>, val todasId: String?,   // todasId: playlist VL… con todas sus populares
    val aleatorio: PlaylistRef?, val mix: PlaylistRef?, val secciones: List<SeccionArtista>
)
/** Un carrusel de la página de artista con el título que le da YT Music ("Álbumes", "Singles y EPs"…). */
data class SeccionArtista(val titulo: String, val items: List<ItemColeccion>)
/** Álbum, playlist o artista de un carrusel; [tipo] es el pageType de YT Music. */
data class ItemColeccion(val browseId: String, val tipo: String, val titulo: String,
    val subtitulo: String, val portadaUrl: String) {
    val esArtista get() = tipo == "MUSIC_PAGE_TYPE_ARTIST"
}
data class PaginaColeccion(
    val browseId: String, val titulo: String, val subtitulo: String,
    val autor: String, val autorId: String?, val autorFotoUrl: String,
    val portadaUrl: String, val pie: String, val canciones: List<SearchItem>, val esAlbum: Boolean
)
data class AudioInfo(val url: String, val bitrate: Int, val mimeType: String)
data class LyricLine(val timeMs: Long, val text: String)
data class LyricsResult(val lines: List<LyricLine>, val isSynced: Boolean, val source: String)
