package com.auramusic.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.auramusic.app.data.db.AuraDatabase
import com.auramusic.app.data.db.MAX_PERFILES
import com.auramusic.app.data.db.PERFIL_PRINCIPAL
import com.auramusic.app.data.db.PerfilCancion
import com.auramusic.app.data.db.PerfilEntity
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
        val porDescargar: List<SongEntity> = emptyList(),
        // Perfiles del backup que no se importaron porque ya había MAX_PERFILES.
        val perfilesOmitidos: Int = 0
    )

    /** El respaldo pasa de los límites (archivo, texto o número de elementos). */
    class RespaldoDemasiadoGrande : IOException("El respaldo es demasiado grande")

    /** Al exportar: ni dejando fuera lo que sobra cabe en los límites de importar. */
    class RespaldoNoCabe : IOException("La biblioteca no cabe en un backup")

    private class Instantanea(
        val canciones: List<SongEntity>, val perfiles: List<PerfilRespaldo>,
        val playlists: List<PlaylistEntity>, val relaciones: Map<String, List<PlaylistSongCrossRef>>
    )

    suspend fun exportar(destino: Uri): Resumen = withContext(Dispatchers.IO) {
        // Todo en una transacción: un cambio de perfil a mitad no puede mezclar nada.
        val foto = db.withTransaction {
            val canciones = db.songDao().todas()
            val perfiles = db.perfilDao().lista()
            val activo = perfiles.firstOrNull { it.activo }?.id ?: PERFIL_PRINCIPAL
            Instantanea(
                canciones = canciones,
                perfiles = perfiles.map { p ->
                    // El activo está en `songs`; los demás, en perfil_canciones.
                    val historial = if (p.id == activo)
                        canciones.filter { it.tieneEstado() }.map {
                            HistorialRespaldo(it.id, it.isLiked, it.playCount, it.lastPlayed, it.noRecomendar)
                        }
                    else db.perfilDao().estadoDe(p.id).map {
                        HistorialRespaldo(it.songId, it.isLiked, it.playCount, it.lastPlayed, it.noRecomendar)
                    }
                    PerfilRespaldo(p.id, p.nombre, p.creado, historial)
                },
                playlists = db.playlistDao().todasDeTodos(),
                relaciones = db.playlistDao().todasLasRelaciones().groupBy { it.playlistId }
            )
        }
        val cola = leerCola()
        val respaldo = Respaldo(
            version = VERSION_FORMATO,
            creado = System.currentTimeMillis(),
            // Solo el catálogo: lo de cada perfil va en su historial.
            canciones = foto.canciones.map { it.copy(isLiked = false, playCount = 0, lastPlayed = 0).aRespaldo() },
            playlists = foto.playlists.map { p ->
                PlaylistRespaldo(
                    id = p.id, nombre = p.name, creada = p.createdAt, perfilId = p.perfilId,
                    canciones = foto.relaciones[p.id].orEmpty().sortedBy { it.position }.map { it.songId }
                )
            },
            ajustes = preferencias.ajustes.first().aRespaldo(),
            cola = cola,
            perfiles = foto.perfiles
        )
        var escrito = respaldo
        // Se decide dentro de `escribir`, antes de que abra el destino: si no cabe,
        // la copia anterior queda como estaba y no se deja un archivo nuevo vacío.
        EscrituraRespaldo.escribir(contexto, destino) { salida ->
            escrito = loQueCabe(respaldo, foto)
            serializar(escrito, salida)
        }
        Resumen(escrito.canciones.size, escrito.playlists.size, ajustes = true, cola = escrito.cola != null)
    }

    // El backup tal cual si cabe en lo que admite importar; si no, sin las canciones
    // sin uso y con la cola recortada; si ni así, no se exporta.
    private fun loQueCabe(completo: Respaldo, foto: Instantanea): Respaldo {
        if (cabe(completo)) return completo
        val conEstado = completo.perfiles.flatMap { it.historial }.filter { it.tieneEstado() }.map { it.id }.toHashSet()
        val enPlaylists = completo.playlists.flatMap { it.canciones }.toHashSet()
        val quedan = RecorteRespaldo.conUso(foto.canciones, conEstado, enPlaylists).map { it.id }.toHashSet()
        val recortado = completo.copy(
            canciones = completo.canciones.filter { it.id in quedan },
            perfiles = completo.perfiles.map { p -> p.copy(historial = p.historial.filter { it.id in quedan }) },
            cola = completo.cola?.let { c ->
                val (items, indice) = RecorteRespaldo.alrededorDe(c.items, c.indice, MAX_POR_LISTA)
                c.copy(items = items, indice = indice)
            }
        )
        return if (cabe(recortado)) recortado else throw RespaldoNoCabe()
    }

    // Los tamaños se miden escribiendo a un contador: armar el texto entero en memoria
    // agotaba la de un Galaxy J7 antes de poder avisar. Comprimir solo para medir
    // cuesta y el JSON encoge: con un texto menor que MAX_ARCHIVO no hace falta.
    private fun cabe(r: Respaldo): Boolean {
        if (!cabenLosElementos(r)) return false
        val texto = medir(r, MAX_TEXTO, comprimido = false) ?: return false
        return texto <= MAX_ARCHIVO || medir(r, MAX_ARCHIVO, comprimido = true) != null
    }

    /** Bytes que ocupa [r]; null en cuanto pasa de [maximo]. */
    private fun medir(r: Respaldo, maximo: Long, comprimido: Boolean): Long? {
        val contador = Contador(maximo)
        return try {
            (if (comprimido) GZIPOutputStream(contador) else contador).use { serializar(r, it) }
            contador.total
        } catch (e: Contador.Excedido) { null }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun serializar(r: Respaldo, salida: OutputStream) =
        json.encodeToStream(Respaldo.serializer(), r, salida)

    // Los mismos topes al exportar y al importar.
    private fun cabenLosElementos(r: Respaldo) =
        r.canciones.size <= MAX_CANCIONES && r.playlists.size <= MAX_PLAYLISTS &&
            r.playlists.all { it.canciones.size <= MAX_POR_LISTA } &&
            (r.cola?.items?.size ?: 0) <= MAX_POR_LISTA &&
            r.perfiles.size <= MAX_PERFILES_ARCHIVO &&
            r.perfiles.all { it.historial.size <= MAX_CANCIONES }

    private fun HistorialRespaldo.tieneEstado() = isLiked || playCount > 0 || lastPlayed > 0 || noRecomendar

    suspend fun importar(origen: Uri): Resumen = withContext(Dispatchers.IO) {
        val respaldo = leer(origen)
        require(respaldo.version <= VERSION_FORMATO) { "Backup de una versión más nueva de ExtMusic" }
        if (!cabenLosElementos(respaldo)) throw RespaldoDemasiadoGrande()

        val cancionesValidas = respaldo.canciones.filter { idCancionValido(it.id) }.distinctBy { it.id }
        val playlistsValidas = respaldo.playlists.filter { ID_PLAYLIST.matches(it.id) }.distinctBy { it.id }
        val porDescargar = mutableListOf<SongEntity>()

        // Formato 3: cada perfil trae su historial. Los formatos 1 y 2 traían uno solo,
        // que es del perfil principal (aunque ahora esté activo otro).
        val perfilesArchivo: List<PerfilRespaldo> =
            if (respaldo.version >= 3) respaldo.perfiles.filter { ID_PERFIL.matches(it.id) }.distinctBy { it.id }
            else listOf(PerfilRespaldo(PERFIL_PRINCIPAL, "Principal", 0L,
                cancionesValidas.filter { it.isLiked || it.playCount > 0 || it.lastPlayed > 0 }
                    .map { HistorialRespaldo(it.id, it.isLiked, it.playCount, it.lastPlayed, false) }))
        var descartados = (respaldo.canciones.size - cancionesValidas.size) +
            (respaldo.playlists.size - playlistsValidas.size)
        var perfilesOmitidos = 0
        var playlistsImportadas = 0

        val songDao = db.songDao()
        val playlistDao = db.playlistDao()
        val perfilDao = db.perfilDao()
        db.withTransaction {
            // Catálogo: solo se añaden las que faltan; las que ya están no se tocan.
            val existentes = songDao.todosLosIds().toHashSet()
            val nuevas = cancionesValidas.filter { it.id !in existentes }.map { r ->
                r.aEntidad().copy(isLiked = false, playCount = 0, lastPlayed = 0, noRecomendar = false)
                    .conDescargaValida().also { nueva ->
                        if (r.isDownloaded && !nueva.isDownloaded && ID_YOUTUBE.matches(nueva.id)) porDescargar += nueva
                    }
            }
            songDao.upsert(nuevas)
            val idsValidos = existentes + nuevas.map { it.id }

            // Perfiles: cada uno se fusiona SOLO con el de su mismo id o se crea; nunca
            // se mete en otro ni cambia el activo.
            val locales = perfilDao.lista().associateBy { it.id }.toMutableMap()
            val activo = perfilDao.activoId() ?: PERFIL_PRINCIPAL
            val destinos = HashSet<String>()
            for (p in perfilesArchivo) {
                if (p.id !in locales) {
                    if (locales.size >= MAX_PERFILES) { perfilesOmitidos++; continue }
                    val nuevo = PerfilEntity(p.id, nombrePerfil(p.nombre), p.creado.takeIf { it > 0 } ?: System.currentTimeMillis(), false)
                    perfilDao.crear(nuevo)
                    locales[p.id] = nuevo
                }
                destinos += p.id
                val historial = p.historial.filter { idCancionValido(it.id) && it.id in idsValidos }.distinctBy { it.id }
                if (p.id == activo) fusionarEnActivo(historial) else fusionarEnOtro(p.id, historial)
            }

            // Playlists: cada una a su perfil; si su perfil no entró, se descarta.
            val yaEstaban = playlistDao.todasDeTodos().associateBy { it.id }
            val aceptadas = playlistsValidas.filter { pl ->
                val perfil = if (respaldo.version >= 3) pl.perfilId ?: PERFIL_PRINCIPAL else PERFIL_PRINCIPAL
                val previa = yaEstaban[pl.id]
                // Una con el mismo id en otro perfil no se toca (sería mezclar).
                (perfil in destinos && (previa == null || previa.perfilId == perfil)).also { if (!it) descartados++ }
            }
            playlistDao.insertarSiNoExisten(aceptadas.map {
                PlaylistEntity(id = it.id, name = it.nombre.take(MAX_TEXTO_CORTO),
                    perfilId = if (respaldo.version >= 3) it.perfilId ?: PERFIL_PRINCIPAL else PERFIL_PRINCIPAL,
                    createdAt = it.creada)
            })
            // Solo se relacionan canciones que existen (la FK lo exige). Las que ya
            // estaban en la playlist conservan su posición (IGNORE).
            playlistDao.relacionarSiNoExisten(
                aceptadas.flatMap { p ->
                    p.canciones.filter { it in idsValidos }.mapIndexed { i, id ->
                        PlaylistSongCrossRef(playlistId = p.id, songId = id, position = i)
                    }
                }
            )
            playlistsImportadas = aceptadas.size
        }

        val ajustes = respaldo.ajustes?.let { preferencias.restaurar(it.aplicarSobre(preferencias.ajustes.first())); true } ?: false
        val cola = respaldo.cola?.let { restaurarCola(it) } ?: false
        Resumen(
            canciones = cancionesValidas.size, playlists = playlistsImportadas,
            ajustes = ajustes, cola = cola,
            descartados = descartados,
            porDescargar = porDescargar,
            perfilesOmitidos = perfilesOmitidos
        )
    }

    // Lo que aporta el backup se suma: Me gusta y "No recomendar" si cualquiera lo
    // tiene; escuchas y última vez, el mayor.
    private suspend fun fusionarEnActivo(historial: List<HistorialRespaldo>) {
        if (historial.isEmpty()) return
        val songDao = db.songDao()
        val porId = historial.associateBy { it.id }
        val actuales = songDao.todas().filter { it.id in porId }
        songDao.upsert(actuales.map { s ->
            val h = porId.getValue(s.id)
            s.copy(isLiked = s.isLiked || h.isLiked, playCount = maxOf(s.playCount, h.playCount),
                lastPlayed = maxOf(s.lastPlayed, h.lastPlayed), noRecomendar = s.noRecomendar || h.noRecomendar)
        })
    }

    private suspend fun fusionarEnOtro(perfilId: String, historial: List<HistorialRespaldo>) {
        if (historial.isEmpty()) return
        val perfilDao = db.perfilDao()
        val actuales = perfilDao.estadoDe(perfilId).associateBy { it.songId }
        perfilDao.guardarEstado(historial.map { h ->
            val a = actuales[h.id]
            PerfilCancion(perfilId, h.id,
                isLiked = (a?.isLiked ?: false) || h.isLiked,
                playCount = maxOf(a?.playCount ?: 0L, h.playCount.coerceAtLeast(0)),
                lastPlayed = maxOf(a?.lastPlayed ?: 0L, h.lastPlayed),
                noRecomendar = (a?.noRecomendar ?: false) || h.noRecomendar)
        })
    }

    private fun nombrePerfil(n: String) = n.trim().take(MAX_NOMBRE_PERFIL).ifBlank { "Perfil" }

    private fun SongEntity.tieneEstado() = isLiked || playCount > 0 || lastPlayed > 0 || noRecomendar

    // Lee el archivo con topes: comprimido o no, nunca más de MAX_ARCHIVO bytes del
    // disco ni MAX_TEXTO de texto (un "gzip bomba" se corta en vez de llenar la memoria).
    // Se decodifica según se lee: con el texto entero en una cadena, un Galaxy J7 no
    // podía restaurar una copia grande que él mismo había exportado.
    @OptIn(ExperimentalSerializationApi::class)
    private fun leer(origen: Uri): Respaldo {
        val crudo = contexto.contentResolver.openInputStream(origen) ?: error("No se pudo abrir el archivo")
        crudo.use {
            val entrada = BufferedInputStream(Limitado(it, MAX_ARCHIVO))
            entrada.mark(2)
            val esGzip = entrada.read() == 0x1f && entrada.read() == 0x8b
            entrada.reset()
            val datos = Limitado(if (esGzip) GZIPInputStream(entrada) else entrada, MAX_TEXTO)
            val respaldo = json.decodeFromStream(Respaldo.serializer(), datos)
            // Hasta el final: ahí gzip comprueba su CRC y los topes cuentan todo.
            val resto = ByteArray(8 * 1024)
            while (datos.read(resto) >= 0) Unit
            return respaldo
        }
    }

    // La descarga solo se restaura si el archivo está en la carpeta de descargas de
    // este teléfono. Si no (otro teléfono, reinstalación… o una ruta inventada), la
    // canción vuelve a ser de YouTube para poder transmitirse, igual que al borrar
    // una descarga (DownloadViewModel). Las del teléfono vuelven a su content://.
    private fun SongEntity.conDescargaValida(): SongEntity {
        val descarga = DescargaWorker.archivoDescargado(contexto, localPath)?.takeIf { it.exists() }
        return when {
            // Se guarda la ruta como la escribe DescargaWorker, no el texto del archivo
            // (que podía traer "file://otro/…" o ".." apuntando al mismo sitio).
            isDownloaded && descarga != null -> {
                val ruta = "file://" + File(DescargaWorker.carpetaDescargas(contexto), descarga.name).absolutePath
                copy(localPath = ruta, origen = OrigenCancion.LOCAL.name, fuente = ruta)
            }
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
        // 3: perfiles de gusto, cada uno con su historial (las canciones van sin él).
        // Las versiones anteriores de la app rechazan este formato en vez de mezclarlo.
        const val VERSION_FORMATO = 3
        const val MAX_PERFILES_ARCHIVO = 50
        const val MAX_NOMBRE_PERFIL = 40
        val ID_PERFIL = Regex("^[\\w-]{1,40}$")
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

/** Descarta lo escrito y solo cuenta; se corta al pasar de [maximo] bytes. */
private class Contador(private val maximo: Long) : OutputStream() {
    class Excedido : IOException()
    var total = 0L
        private set
    override fun write(b: Int) = sumar(1)
    override fun write(b: ByteArray, off: Int, len: Int) = sumar(len)
    private fun sumar(n: Int) {
        total += n
        if (total > maximo) throw Excedido()
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
    val cola: ColaRespaldo? = null,
    // Formato 3.
    val perfiles: List<PerfilRespaldo> = emptyList()
)

@Serializable
private data class PlaylistRespaldo(
    val id: String,
    val nombre: String,
    val creada: Long,
    val canciones: List<String>,
    val perfilId: String? = null
)

@Serializable
private data class PerfilRespaldo(
    val id: String,
    val nombre: String,
    val creado: Long = 0,
    val historial: List<HistorialRespaldo> = emptyList()
)

/** Lo de una canción en un perfil. */
@Serializable
private data class HistorialRespaldo(
    val id: String,
    val isLiked: Boolean = false,
    val playCount: Long = 0,
    val lastPlayed: Long = 0,
    val noRecomendar: Boolean = false
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
