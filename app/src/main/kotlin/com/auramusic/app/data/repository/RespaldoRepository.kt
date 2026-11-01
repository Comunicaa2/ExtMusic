package com.auramusic.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.auramusic.app.data.db.AuraDatabase
import com.auramusic.app.data.db.PlaylistEntity
import com.auramusic.app.data.db.PlaylistSongCrossRef
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.domain.model.AppSettings
import com.auramusic.app.domain.model.ID_YOUTUBE
import com.auramusic.app.domain.model.OrigenCancion
import com.auramusic.app.service.MusicService
import com.auramusic.app.work.DescargaWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup de la biblioteca (canciones con su historial, "me gusta", playlists,
 * descargadas, cola y ajustes) a un archivo elegido por el usuario. Sustituye a
 * la sincronización con la cuenta de Google: los datos solo viven en el teléfono,
 * así que esta es la forma de no perderlos al reinstalar o cambiar de teléfono.
 *
 * Formato 2: JSON compacto dentro de gzip (~8 veces más pequeño; el CRC de gzip
 * rechaza un archivo corrupto o cortado). Se siguen leyendo los .json del formato 1.
 *
 * Importar FUSIONA (nunca borra): lo que ya hay en la app se conserva. El archivo
 * puede venir de cualquier sitio, así que no se confía en nada de lo que trae:
 * ids, rutas, tamaños y textos se validan antes de tocar la BD.
 */
@Singleton
class RespaldoRepository @Inject constructor(
    @ApplicationContext private val contexto: Context,
    private val db: AuraDatabase,
    private val preferencias: RepositorioPreferencias
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    data class Resumen(
        val canciones: Int,
        val playlists: Int,
        val ajustes: Boolean = false,
        val cola: Boolean = false,
        val descartados: Int = 0,
        // Descargadas en el respaldo cuyo archivo no está en este teléfono.
        val porDescargar: List<SongEntity> = emptyList()
    )

    /** El respaldo pasa de los límites (archivo, texto o número de elementos). */
    class RespaldoDemasiadoGrande : IOException("El respaldo es demasiado grande")

    suspend fun exportar(destino: Uri): Resumen = withContext(Dispatchers.IO) {
        val canciones = db.songDao().todas()
        val playlists = db.playlistDao().todas()
        val relaciones = db.playlistDao().todasLasRelaciones().groupBy { it.playlistId }
        val cola = leerCola()
        val respaldo = Respaldo(
            version = VERSION_FORMATO,
            creado = System.currentTimeMillis(),
            canciones = canciones.map { it.aRespaldo() },
            playlists = playlists.map { p ->
                PlaylistRespaldo(
                    id = p.id, nombre = p.name, creada = p.createdAt,
                    canciones = relaciones[p.id].orEmpty().sortedBy { it.position }.map { it.songId }
                )
            },
            ajustes = preferencias.ajustes.first().aRespaldo(),
            cola = cola
        )
        val salida = contexto.contentResolver.openOutputStream(destino, "wt")
            ?: error("No se pudo abrir el archivo de destino")
        GZIPOutputStream(salida).bufferedWriter().use { it.write(json.encodeToString(Respaldo.serializer(), respaldo)) }
        Resumen(canciones.size, playlists.size, ajustes = true, cola = cola != null)
    }

    suspend fun importar(origen: Uri): Resumen = withContext(Dispatchers.IO) {
        val respaldo = json.decodeFromString(Respaldo.serializer(), leerTexto(origen))
        require(respaldo.version <= VERSION_FORMATO) { "Backup de una versión más nueva de ExtMusic" }
        if (respaldo.canciones.size > MAX_CANCIONES || respaldo.playlists.size > MAX_PLAYLISTS ||
            respaldo.playlists.any { it.canciones.size > MAX_POR_LISTA } ||
            (respaldo.cola?.items?.size ?: 0) > MAX_POR_LISTA
        ) throw RespaldoDemasiadoGrande()

        val cancionesValidas = respaldo.canciones.filter { idCancionValido(it.id) }.distinctBy { it.id }
        val playlistsValidas = respaldo.playlists.filter { ID_PLAYLIST.matches(it.id) }.distinctBy { it.id }
        val porDescargar = mutableListOf<SongEntity>()

        val songDao = db.songDao()
        val playlistDao = db.playlistDao()
        db.withTransaction {
            val existentes = songDao.todas().associateBy { it.id }
            val fusionadas = cancionesValidas.map { r ->
                val actual = existentes[r.id]
                if (actual != null) {
                    // Ya está en la app: se respeta su estado (descarga, metadatos) y
                    // solo se suma lo que el backup aporta.
                    actual.copy(
                        isLiked = actual.isLiked || r.isLiked,
                        playCount = maxOf(actual.playCount, r.playCount),
                        lastPlayed = maxOf(actual.lastPlayed, r.lastPlayed)
                    )
                } else {
                    r.aEntidad().conDescargaValida().also { nueva ->
                        if (r.isDownloaded && !nueva.isDownloaded && ID_YOUTUBE.matches(nueva.id)) porDescargar += nueva
                    }
                }
            }
            songDao.upsert(fusionadas)

            playlistDao.insertarSiNoExisten(
                playlistsValidas.map { PlaylistEntity(id = it.id, name = it.nombre.take(MAX_TEXTO_CORTO), createdAt = it.creada) }
            )
            // Solo se relacionan canciones que existen (la FK lo exige). Las que ya
            // estaban en la playlist conservan su posición (IGNORE).
            val idsValidos = existentes.keys + fusionadas.map { it.id }
            playlistDao.relacionarSiNoExisten(
                playlistsValidas.flatMap { p ->
                    p.canciones.filter { it in idsValidos }.mapIndexed { i, id ->
                        PlaylistSongCrossRef(playlistId = p.id, songId = id, position = i)
                    }
                }
            )
        }

        val ajustes = respaldo.ajustes?.let { preferencias.restaurar(it.aplicarSobre(preferencias.ajustes.first())); true } ?: false
        val cola = respaldo.cola?.let { restaurarCola(it) } ?: false
        Resumen(
            canciones = cancionesValidas.size, playlists = playlistsValidas.size,
            ajustes = ajustes, cola = cola,
            descartados = (respaldo.canciones.size - cancionesValidas.size) +
                (respaldo.playlists.size - playlistsValidas.size),
            porDescargar = porDescargar
        )
    }

    // Lee el archivo con topes: comprimido o no, nunca más de MAX_ARCHIVO bytes del
    // disco ni MAX_TEXTO de texto (un "gzip bomba" se corta en vez de llenar la memoria).
    private fun leerTexto(origen: Uri): String {
        val crudo = contexto.contentResolver.openInputStream(origen) ?: error("No se pudo abrir el archivo")
        crudo.use {
            val entrada = BufferedInputStream(Limitado(it, MAX_ARCHIVO))
            entrada.mark(2)
            val esGzip = entrada.read() == 0x1f && entrada.read() == 0x8b
            entrada.reset()
            val datos = if (esGzip) GZIPInputStream(entrada) else entrada
            return Limitado(datos, MAX_TEXTO).bufferedReader().readText()
        }
    }

    // La descarga solo se restaura si el archivo está en la carpeta de descargas de
    // este teléfono. Si no (otro teléfono, reinstalación… o una ruta inventada), la
    // canción vuelve a ser de YouTube para poder transmitirse, igual que al borrar
    // una descarga (DownloadViewModel). Las del teléfono vuelven a su content://.
    private fun SongEntity.conDescargaValida(): SongEntity {
        val descarga = DescargaWorker.archivoDescargado(contexto, localPath)?.takeIf { it.exists() }
        return when {
            isDownloaded && descarga != null -> copy(origen = OrigenCancion.LOCAL.name, fuente = localPath!!)
            id.startsWith(PREFIJO_MEDIA) -> copy(
                isDownloaded = false, localPath = null, downloadQuality = 0,
                origen = OrigenCancion.LOCAL.name, fuente = id
            )
            else -> copy(
                isDownloaded = false, localPath = null, downloadQuality = 0,
                origen = OrigenCancion.YOUTUBE.name, fuente = id
            )
        }
    }

    // ── Cola ─────────────────────────────────────────────────────────────────
    // Mismo formato que MusicService.guardarCola(); al restaurar la URI se arma
    // aquí desde el id (nunca se copia la del archivo).
    private fun leerCola(): ColaRespaldo? = runCatching {
        val prefs = contexto.getSharedPreferences(MusicService.PREFS_COLA, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("items", null) ?: return null)
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ItemColaRespaldo(o.getString("id"), o.optString("titulo"), o.optString("artista"), o.optString("portada"))
        }
        ColaRespaldo(items, prefs.getInt("indice", 0), prefs.getLong("posicion", 0L))
    }.getOrNull()?.takeIf { it.items.isNotEmpty() }

    /** Solo si no hay cola en este teléfono: la que esté sonando o recordada se respeta. */
    private fun restaurarCola(cola: ColaRespaldo): Boolean {
        val prefs = contexto.getSharedPreferences(MusicService.PREFS_COLA, Context.MODE_PRIVATE)
        if (prefs.getString("items", null) != null) return false
        val items = cola.items.filter { idCancionValido(it.id) }
        if (items.isEmpty()) return false
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject()
                .put("id", it.id)
                .put("uri", if (it.id.startsWith(PREFIJO_MEDIA)) it.id else MusicService.uriDeVideo(it.id))
                .put("titulo", it.titulo.take(MAX_TEXTO_CORTO))
                .put("artista", it.artista.take(MAX_TEXTO_CORTO))
                .put("portada", portadaSegura(it.portada)))
        }
        val actual = cola.items.getOrNull(cola.indice)?.id
        prefs.edit()
            .putString("items", arr.toString())
            .putInt("indice", items.indexOfFirst { it.id == actual }.coerceAtLeast(0))
            .putLong("posicion", cola.posicionMs.coerceAtLeast(0L))
            .apply()
        return true
    }

    // ── Validación ───────────────────────────────────────────────────────────
    private fun idCancionValido(id: String) =
        ID_YOUTUBE.matches(id) || (id.startsWith(PREFIJO_MEDIA) && id.length <= 300 && ".." !in id)

    private fun CancionRespaldo.aEntidad() = SongEntity(
        id = id, title = title.take(MAX_TEXTO_CORTO), artist = artist.take(MAX_TEXTO_CORTO),
        artistId = artistId.takeIf { ID_ARTISTA.matches(it) }.orEmpty(), album = album.take(MAX_TEXTO_CORTO),
        thumbnailUrl = portadaSegura(thumbnailUrl), durationMs = durationMs.coerceAtLeast(0),
        isLiked = isLiked, playCount = playCount.coerceAtLeast(0), lastPlayed = lastPlayed,
        isDownloaded = isDownloaded, localPath = localPath, downloadQuality = downloadQuality,
        origen = origen, fuente = fuente, explicit = explicit
    )

    private companion object {
        const val VERSION_FORMATO = 2
        const val PREFIJO_MEDIA = "content://media/"
        const val MAX_ARCHIVO = 10L * 1024 * 1024
        const val MAX_TEXTO = 20L * 1024 * 1024
        const val MAX_CANCIONES = 50_000
        const val MAX_PLAYLISTS = 2_000
        const val MAX_POR_LISTA = 10_000
        const val MAX_TEXTO_CORTO = 500
        // Ids que genera la app (UUID) o importa de YT Music; "auto:*" queda fuera.
        val ID_PLAYLIST = Regex("^[\\w-]{1,64}$")
        val ID_ARTISTA = Regex("^[\\w-]{1,64}$")

        fun portadaSegura(url: String): String =
            url.takeIf { (it.startsWith("https://") || it.startsWith(PREFIJO_MEDIA)) && it.length <= 2_000 }.orEmpty()
    }
}

/** InputStream que se corta con [RespaldoRepository.RespaldoDemasiadoGrande] al pasar de [maximo] bytes. */
private class Limitado(entrada: InputStream, private val maximo: Long) : FilterInputStream(entrada) {
    private var leidos = 0L
    override fun read(): Int = super.read().also { if (it >= 0) contar(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) contar(it.toLong()) }
    private fun contar(n: Long) {
        leidos += n
        if (leidos > maximo) throw RespaldoRepository.RespaldoDemasiadoGrande()
    }
}

// ─── Formato del archivo ─────────────────────────────────────────────────────
// Clases propias (no las entidades de Room) para que cambiar la BD no rompa los
// backups ya guardados. Todo lo nuevo del formato 2 tiene valor por defecto, así
// los backups del formato 1 se siguen leyendo.

@Serializable
private data class Respaldo(
    val version: Int,
    val creado: Long,
    val canciones: List<CancionRespaldo>,
    val playlists: List<PlaylistRespaldo>,
    val ajustes: AjustesRespaldo? = null,
    val cola: ColaRespaldo? = null
)

@Serializable
private data class PlaylistRespaldo(
    val id: String,
    val nombre: String,
    val creada: Long,
    val canciones: List<String>
)

@Serializable
private data class CancionRespaldo(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "",
    val album: String = "",
    val thumbnailUrl: String = "",
    val durationMs: Long = 0,
    val isLiked: Boolean = false,
    val playCount: Long = 0,
    val lastPlayed: Long = 0,
    val isDownloaded: Boolean = false,
    val localPath: String? = null,
    val downloadQuality: Int = 0,
    val origen: String = OrigenCancion.YOUTUBE.name,
    val fuente: String = "",
    val explicit: Boolean = false
)

@Serializable
private data class ColaRespaldo(val items: List<ItemColaRespaldo>, val indice: Int = 0, val posicionMs: Long = 0)

@Serializable
private data class ItemColaRespaldo(val id: String, val titulo: String = "", val artista: String = "", val portada: String = "")

// Ajustes sin las carpetas (son permisos del teléfono donde se eligieron). Todo
// opcional: lo que falte o no se entienda deja el ajuste actual.
@Serializable
private data class AjustesRespaldo(
    val tema: String? = null,
    val desenfoqueFondo: Boolean? = null,
    val liquidGlass: Boolean? = null,
    val segundoPlano: Boolean? = null,
    val crossfadeSegundos: Int? = null,
    val normalizarVolumen: Boolean? = null,
    val ahorroAudio: Boolean? = null,
    val colaAleatoria: Boolean? = null,
    val ocultarExplicito: Boolean? = null,
    val reproduccionAutomatica: Boolean? = null,
    val datosMoviles: Boolean? = null,
    val codec: String? = null,
    val precargarSiguiente: Boolean? = null,
    val region: String? = null,
    val cachearStreams: Boolean? = null,
    val calidadAudio: String? = null,
    val calidadDescarga: String? = null,
    val formatoDescarga: String? = null,
    val descargarAlDarLike: Boolean? = null,
    val incrustarCaratula: Boolean? = null,
    val descargarLetras: Boolean? = null,
    val descargaSoloWifi: Boolean? = null,
    val descargasSimultaneas: Int? = null,
    val cacheMaximoMb: Int? = null,
    val mostrarLetras: Boolean? = null,
    val letrasAutomaticas: Boolean? = null,
    val tamanoLetras: Int? = null,
    val retrasoLetrasMs: Int? = null
) {
    // Los rangos los vuelve a acotar RepositorioPreferencias.restaurar().
    fun aplicarSobre(a: AppSettings) = a.copy(
        tema = enumO(tema, a.tema),
        blurPlayerBg = desenfoqueFondo ?: a.blurPlayerBg,
        liquidGlassEnabled = liquidGlass ?: a.liquidGlassEnabled,
        reproduccionSegundoPlano = segundoPlano ?: a.reproduccionSegundoPlano,
        crossfadeSegundos = crossfadeSegundos ?: a.crossfadeSegundos,
        normalizarVolumen = normalizarVolumen ?: a.normalizarVolumen,
        ahorroAudio = ahorroAudio ?: a.ahorroAudio,
        colaAleatoria = colaAleatoria ?: a.colaAleatoria,
        ocultarExplicito = ocultarExplicito ?: a.ocultarExplicito,
        reproduccionAutomatica = reproduccionAutomatica ?: a.reproduccionAutomatica,
        permitirDatosMoviles = datosMoviles ?: a.permitirDatosMoviles,
        codecAudio = enumO(codec, a.codecAudio),
        precargarSiguiente = precargarSiguiente ?: a.precargarSiguiente,
        regionPais = region?.takeIf { it.isEmpty() || Regex("^[A-Z]{2}$").matches(it) } ?: a.regionPais,
        cachearStreams = cachearStreams ?: a.cachearStreams,
        audioQuality = enumO(calidadAudio, a.audioQuality),
        downloadQuality = enumO(calidadDescarga, a.downloadQuality),
        formatoDescarga = enumO(formatoDescarga, a.formatoDescarga),
        descargarAlDarLike = descargarAlDarLike ?: a.descargarAlDarLike,
        incrustarCaratula = incrustarCaratula ?: a.incrustarCaratula,
        descargarLetras = descargarLetras ?: a.descargarLetras,
        descargarSoloWifi = descargaSoloWifi ?: a.descargarSoloWifi,
        descargasSimultaneas = descargasSimultaneas ?: a.descargasSimultaneas,
        cacheMaximoMb = cacheMaximoMb ?: a.cacheMaximoMb,
        showLyrics = mostrarLetras ?: a.showLyrics,
        letrasAutomaticas = letrasAutomaticas ?: a.letrasAutomaticas,
        tamanoFuenteLetras = tamanoLetras ?: a.tamanoFuenteLetras,
        retrasoLetrasMs = retrasoLetrasMs ?: a.retrasoLetrasMs
    )
}

private inline fun <reified E : Enum<E>> enumO(nombre: String?, porDefecto: E): E =
    nombre?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: porDefecto

private fun AppSettings.aRespaldo() = AjustesRespaldo(
    tema = tema.name, desenfoqueFondo = blurPlayerBg, liquidGlass = liquidGlassEnabled,
    segundoPlano = reproduccionSegundoPlano, crossfadeSegundos = crossfadeSegundos,
    normalizarVolumen = normalizarVolumen, ahorroAudio = ahorroAudio, colaAleatoria = colaAleatoria,
    ocultarExplicito = ocultarExplicito, reproduccionAutomatica = reproduccionAutomatica, datosMoviles = permitirDatosMoviles, codec = codecAudio.name,
    precargarSiguiente = precargarSiguiente, region = regionPais, cachearStreams = cachearStreams,
    calidadAudio = audioQuality.name, calidadDescarga = downloadQuality.name,
    formatoDescarga = formatoDescarga.name, descargarAlDarLike = descargarAlDarLike,
    incrustarCaratula = incrustarCaratula, descargarLetras = descargarLetras,
    descargaSoloWifi = descargarSoloWifi, descargasSimultaneas = descargasSimultaneas,
    cacheMaximoMb = cacheMaximoMb, mostrarLetras = showLyrics, letrasAutomaticas = letrasAutomaticas,
    tamanoLetras = tamanoFuenteLetras, retrasoLetrasMs = retrasoLetrasMs
)

private fun SongEntity.aRespaldo() = CancionRespaldo(
    id, title, artist, artistId, album, thumbnailUrl, durationMs, isLiked, playCount,
    lastPlayed, isDownloaded, localPath, downloadQuality, origen, fuente, explicit
)
