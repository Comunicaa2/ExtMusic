package com.auramusic.app.ui.screens

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auramusic.app.R
import com.auramusic.app.data.api.InnertubeClient
import com.auramusic.app.data.api.ItemColeccion
import com.auramusic.app.data.api.PaginaArtista
import com.auramusic.app.data.api.SearchItem
import com.auramusic.app.data.db.EscuchaArtista
import com.auramusic.app.data.db.PerfilDao
import com.auramusic.app.data.db.SongDao
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.data.repository.ArchivosPerfil
import com.auramusic.app.data.repository.RepositorioPreferencias
import com.auramusic.app.domain.model.ID_YOUTUBE
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

// ── Carruseles del Inicio (se guardan en filesDir/inicio.json) ─────────────────
/** Álbum, playlist o artista de un carrusel; [tipo] es el pageType de YT Music. */
@Serializable
data class TarjetaInicio(val browseId: String, val tipo: String, val titulo: String,
    val subtitulo: String, val portadaUrl: String) {
    val esArtista get() = tipo == "MUSIC_PAGE_TYPE_ARTIST"
}
/** Mix (radio de YT Music) de uno de tus artistas. */
@Serializable
data class MixInicio(val artista: String, val fotoUrl: String, val playlistId: String, val params: String?)
/** Un carrusel del Inicio: de canciones, de tarjetas o de mixes. */
@Serializable
data class SeccionInicio(val titulo: String, val canciones: List<SearchItem> = emptyList(),
    val colecciones: List<TarjetaInicio> = emptyList(), val mixes: List<MixInicio> = emptyList())

/**
 * Inicio armado con lo que escuchas (el feed de YT Music sin cuenta es el mismo
 * para todo un país): de tus artistas más escuchados y de los más recientes salen
 * sus novedades, sus mixes, artistas parecidos y las playlists donde aparecen.
 * Se rehace solo cuando cambian esos artistas; el último se guarda en
 * filesDir/inicio.json y se muestra al abrir mientras se actualiza.
 */
@HiltViewModel
class InicioViewModel @Inject constructor(
    @ApplicationContext private val contexto: Context,
    private val innertube: InnertubeClient,
    private val songDao: SongDao,
    private val perfilDao: PerfilDao,
    preferencias: RepositorioPreferencias
) : ViewModel() {

    data class Estado(
        val secciones: List<SeccionInicio> = emptyList(),
        val cargando: Boolean = false,
        val aviso: SearchViewModel.Aviso? = null
    )

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado

    // Las listas empiezan vacías hasta que llega la base de datos: sin esperar a eso,
    // el primer fotograma mostraba "no hay música" y luego las secciones entraban a
    // saltos. Cada lista marca su bit al recibir su primer valor y el inicio.json
    // guardado el suyo; con los cuatro, el Inicio se muestra entero de una vez.
    private val cargado = MutableStateFlow(0)
    private fun marcar(bit: Int) = cargado.update { it or bit }
    val listo: StateFlow<Boolean> = cargado.map { it == TODO_CARGADO }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Sube cada vez que se desliza para actualizar: 0 = tus 10 más escuchadas; después,
    // 10 al azar de tus 30 más escuchadas (si no, esa mitad nunca cambiaba).
    private val variacion = MutableStateFlow(0)

    /**
     * Tus canciones de YouTube más escuchadas (también las descargadas), para Selección rápida.
     * La base de datos ya las da filtradas y ordenadas: leer la tabla entera en cada
     * cambio de canción costaba ~0,7 s de CPU con 3.000 canciones en un Galaxy J7.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val masEscuchadas: StateFlow<List<SongEntity>> = preferencias.ajustes.map { it.ocultarExplicito }.distinctUntilChanged()
        .flatMapLatest { ocultar -> songDao.masEscuchadas(conExplicito = !ocultar, limite = GRUPO_VARIADO * 2) }
        .combine(variacion) { lista, v ->
            val mias = lista.filter { esDeYoutube(it.id, it.fuente) }
            if (v == 0) mias.take(MAX_MIAS) else mias.take(GRUPO_VARIADO).shuffled(kotlin.random.Random(v)).take(MAX_MIAS)
        }.soloSiCambiaLoQueSeVe().onEach { marcar(1) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Cada canción que suena reescribe su fila (contador, última vez, duración) y Room
     * vuelve a emitir la tabla entera, varias veces por cambio de canción. Las listas
     * se calculan fuera del hilo principal y solo llegan al Inicio si cambia algo que
     * se ve: en un Galaxy J7 el Inicio se rehacía entero bajo el reproductor en cada
     * cambio de canción, con sus portadas.
     */
    private fun Flow<List<SongEntity>>.soloSiCambiaLoQueSeVe() =
        distinctUntilChanged { a, b ->
            a.size == b.size && a.indices.all { i ->
                val x = a[i]; val y = b[i]
                x.id == y.id && x.title == y.title && x.artist == y.artist && x.thumbnailUrl == y.thumbnailUrl &&
                    x.durationMs == y.durationMs && x.isLiked == y.isLiked && x.explicit == y.explicit
            }
        }.flowOn(Dispatchers.Default)

    // "Escuchar de nuevo" y "Me gusta" se guardan aquí (el ViewModel sobrevive mientras
    // el reproductor tapa el Inicio): al volver ya tienen sus canciones en el primer
    // fotograma. Leídas de PlayerViewModel empezaban vacías y el Inicio aparecía con
    // otras secciones arriba y luego saltaba.
    val recientes: StateFlow<List<SongEntity>> = combine(songDao.getRecentlyPlayedStreaming(), preferencias.ajustes) { l, aj ->
        if (aj.ocultarExplicito) l.filter { !it.explicit } else l
    }.soloSiCambiaLoQueSeVe().onEach { marcar(2) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val meGusta: StateFlow<List<SongEntity>> = combine(songDao.getLiked(), preferencias.ajustes) { l, aj ->
        if (aj.ocultarExplicito) l.filter { !it.explicit } else l
    }.soloSiCambiaLoQueSeVe().onEach { marcar(4) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // El Inicio guardado es de cada perfil de gusto (ver ArchivosPerfil).
    @Volatile private var perfilActual: String? = null
    private val serializador = ListSerializer(SeccionInicio.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private var artistas: List<ArtistaTuyo> = emptyList()
    private var trabajo: Job? = null

    init {
        viewModelScope.launch {
            // Al cambiar de perfil se empieza de nuevo con lo guardado de ese perfil.
            perfilDao.activo().map { it?.id }.distinctUntilChanged().collectLatest { perfil ->
                trabajo?.cancel()
                perfilActual = perfil
                variacion.value = 0
                val g = guardado(perfil)
                _estado.update { Estado(secciones = g.orEmpty()) }
                marcar(8)
                // Cada canción que suena cambia el historial, pero el Inicio solo se rehace
                // cuando cambian tus artistas (o su orden), y solo pide a YT Music las
                // páginas que no tiene (ver paginasVistas).
                songDao.escuchasPorArtista().map { tusArtistas(it) }.distinctUntilChanged().flowOn(Dispatchers.Default).collect { a ->
                    artistas = a
                    armar()
                }
            }
        }
    }

    fun reintentar() { armar() }

    /** Deslizar para actualizar: otra mezcla de tus canciones y los carruseles de nuevo. */
    suspend fun actualizar() {
        variacion.value++
        armar(forzar = true).join()
    }

    /**
     * Páginas de artista ya pedidas (también los artistas que no se encuentran), por
     * artista. Casi cada canción que suena reordena tus artistas (los 2 recientes) y
     * antes se volvían a pedir las 6 páginas a YT Music en cada cambio de canción: en
     * un Galaxy J7, guardarlas bajó la CPU de la app en 8 cambios de ~38–46 s a
     * ~24–26 s. Ahora solo se pide la de un artista nuevo o la que ha caducado.
     */
    private class PaginaVista(val pagina: PaginaArtista?, val hora: Long)
    private val paginasVistas = java.util.concurrent.ConcurrentHashMap<ArtistaTuyo, PaginaVista>()
    private fun vigente(v: PaginaVista?) = v != null && System.currentTimeMillis() - v.hora < VIGENCIA_MS

    private fun armar(forzar: Boolean = false): Job {
        trabajo?.cancel()
        val lista = artistas
        val perfil = perfilActual
        return viewModelScope.launch {
            if (lista.isEmpty()) {
                _estado.update { Estado() }
                return@launch
            }
            paginasVistas.entries.removeAll { !vigente(it.value) }   // solo la última hora
            // Sin nada que pedir, no se muestra "cargando" (si no, parpadeaba en cada canción).
            if (forzar || !lista.all { vigente(paginasVistas[it]) }) _estado.update { it.copy(cargando = true, aviso = null) }
            val paginas = coroutineScope { lista.map { a -> async { pagina(a, forzar) } }.awaitAll() }
            val listas = paginas.mapNotNull { it?.getOrNull() }
            if (listas.isEmpty()) {
                val sinRed = paginas.any { it?.exceptionOrNull() is java.io.IOException }
                Log.w(TAG, "Sin páginas de artista (sin red: $sinRed)")
                _estado.update { it.copy(cargando = false, aviso = when {
                    it.secciones.isNotEmpty() -> null       // queda lo guardado
                    sinRed -> SearchViewModel.Aviso.SIN_CONEXION
                    else -> SearchViewModel.Aviso.ERROR
                }) }
                return@launch
            }
            val secciones = construir(listas)
            val cambia = secciones != _estado.value.secciones
            _estado.update { Estado(secciones) }
            if (cambia) guardar(perfil, secciones)
        }.also { trabajo = it }
    }

    // La página de YT Music de un artista tuyo: la ya vista si sigue vigente (salvo
    // [forzar]) o pedida de nuevo. Los fallos de red no se guardan: se reintentan.
    private suspend fun pagina(a: ArtistaTuyo, forzar: Boolean): Result<PaginaArtista>? {
        paginasVistas[a]?.takeIf { !forzar && vigente(it) }?.let { v -> return v.pagina?.let { Result.success(it) } }
        val r = pedirPagina(a)
        if (r == null || r.isSuccess) paginasVistas[a] = PaginaVista(r?.getOrNull(), System.currentTimeMillis())
        return r
    }

    // Sin su id, se busca por el nombre exacto (para no traer a otro artista que se
    // llame parecido); null si no se encuentra.
    private suspend fun pedirPagina(a: ArtistaTuyo): Result<PaginaArtista>? {
        val id = a.id ?: innertube.buscarArtistas(a.nombre).getOrElse { return Result.failure(it) }
            .firstOrNull { it.nombre.equals(a.nombre, ignoreCase = true) }?.browseId ?: return null
        return innertube.artista(id)
    }

    private fun construir(paginas: List<PaginaArtista>): List<SeccionInicio> {
        val secciones = mutableListOf<SeccionInicio>()
        val items = paginas.map { p -> p.secciones.flatMap { it.items } }

        // Novedades: los primeros álbumes y sencillos de cada uno (YT Music los da
        // del más nuevo al más viejo), ordenados por año.
        data class Lanzamiento(val t: TarjetaInicio, val anio: Int, val pos: Int, val rango: Int)
        val novedades = paginas.flatMapIndexed { rango, p ->
            items[rango].filter { it.tipo == ALBUM }.distinctBy { it.browseId }.take(POR_ARTISTA)
                .mapIndexed { pos, it ->
                    Lanzamiento(tarjeta(it, listOf(p.nombre, it.subtitulo).filter(String::isNotBlank).joinToString(" • ")),
                        ANIO.findAll(it.subtitulo).lastOrNull()?.value?.toInt() ?: 0, pos, rango)
                }
        }.sortedWith(compareByDescending<Lanzamiento> { it.anio }.thenBy { it.pos }.thenBy { it.rango })
            .map { it.t }.distinctBy { it.browseId }.take(MAX_CARRUSEL)
        if (novedades.isNotEmpty()) secciones += SeccionInicio(contexto.getString(R.string.home_novedades), colecciones = novedades)

        val mixes = paginas.mapNotNull { p -> p.mix?.let { MixInicio(p.nombre, p.fotoUrl, it.playlistId, it.params) } }
        if (mixes.isNotEmpty()) secciones += SeccionInicio(contexto.getString(R.string.home_mixes), mixes = mixes)

        // Artistas parecidos a los dos primeros que no escuchas ya.
        val tuyos = paginas.map { it.nombre.lowercase() }.toSet()
        paginas.take(2).forEachIndexed { i, p ->
            val parecidos = items[i].filter { it.esArtista && it.titulo.lowercase() !in tuyos }.distinctBy { it.browseId }
            if (parecidos.isNotEmpty()) secciones += SeccionInicio(contexto.getString(R.string.home_si_te_gusta, p.nombre),
                colecciones = parecidos.map { tarjeta(it, it.subtitulo) })
        }

        // Playlists donde aparecen (o que hicieron), intercaladas entre tus artistas.
        val listas = intercalar(items.map { l -> l.filter { it.tipo == PLAYLIST } })
            .distinctBy { it.browseId }.take(MAX_CARRUSEL)
        if (listas.isNotEmpty()) secciones += SeccionInicio(contexto.getString(R.string.home_playlists_artistas),
            colecciones = listas.map { tarjeta(it, it.subtitulo) })
        return secciones
    }

    private fun tarjeta(i: ItemColeccion, subtitulo: String) =
        TarjetaInicio(i.browseId, i.tipo, i.titulo, subtitulo, i.portadaUrl)

    private fun <T> intercalar(listas: List<List<T>>): List<T> {
        val salida = mutableListOf<T>()
        val its = listas.map { it.iterator() }
        while (its.any { it.hasNext() }) its.forEach { if (it.hasNext()) salida += it.next() }
        return salida
    }

    private fun archivo(perfil: String?) = ArchivosPerfil.archivo(contexto, perfil, "inicio.json")

    private suspend fun guardado(perfil: String?): List<SeccionInicio>? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(serializador, archivo(perfil).readText()) }.getOrNull()
    }

    private suspend fun guardar(perfil: String?, s: List<SeccionInicio>) = withContext(Dispatchers.IO) {
        runCatching { archivo(perfil).writeText(json.encodeToString(serializador, s)) }
            .onFailure { Log.w(TAG, "No se pudo guardar el Inicio: ${it.message}") }
    }

    companion object {
        private const val TAG = "Inicio"
        private const val MAX_MIAS = 10
        private const val GRUPO_VARIADO = 30
        private const val TODO_CARGADO = 1 or 2 or 4 or 8   // listas y inicio.json
        private const val MAX_ARTISTAS = 6
        private const val POR_ARTISTA = 4
        private const val MAX_CARRUSEL = 20
        private const val VIGENCIA_MS = 60 * 60 * 1000L   // páginas de artista: 1 h
        private const val ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
        private const val PLAYLIST = "MUSIC_PAGE_TYPE_PLAYLIST"
        private val ANIO = Regex("\\b(19|20)\\d{2}\\b")
        const val MAX_SELECCION = 20
    }
}

/** Un artista que escuchas: su nombre y, si se sabe, su browseId de YT Music. */
data class ArtistaTuyo(val nombre: String, val id: String?)

private fun esDeYoutube(id: String, fuente: String) = ID_YOUTUBE.matches(fuente) || ID_YOUTUBE.matches(id)

/**
 * Tus artistas: los 4 más escuchados y los 2 de lo último que sonó (así el Inicio
 * sigue lo que escuchas ahora), sin repetir. [historial]: las escuchadas, de la más
 * reciente a la más antigua.
 */
private fun tusArtistas(historial: List<EscuchaArtista>): List<ArtistaTuyo> {
    val grupos = historial.filter { it.artist.isNotBlank() && esDeYoutube(it.id, it.fuente) }
        .groupBy { extractMainArtist(it.artist).lowercase() }
    val porEscuchas = grupos.entries.sortedByDescending { e -> e.value.sumOf { it.playCount } }.map { it.key }
    val porRecientes = grupos.entries.sortedByDescending { e -> e.value.maxOf { it.lastPlayed } }.map { it.key }
    return (porEscuchas.take(4) + porRecientes.take(2)).distinct().take(6).map { clave ->
        val canciones = grupos.getValue(clave)
        ArtistaTuyo(extractMainArtist(canciones.first().artist),
            canciones.map { it.artistId }.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)
    }
}

/**
 * Selección rápida, como en YT Music: intercala una canción tuya (de las más
 * escuchadas) con una nueva parecida, sin repetir ninguna.
 */
fun mezclarSeleccion(mias: List<SongEntity>, nuevas: List<SongEntity>,
                     maximo: Int = InicioViewModel.MAX_SELECCION): List<SongEntity> {
    val elegidas = mutableListOf<SongEntity>()
    val ids = HashSet<String>()
    val a = mias.iterator()
    val b = nuevas.iterator()
    while (elegidas.size < maximo && (a.hasNext() || b.hasNext())) {
        if (a.hasNext()) a.next().let { if (ids.add(it.id)) elegidas += it }
        if (elegidas.size < maximo && b.hasNext()) b.next().let { if (ids.add(it.id)) elegidas += it }
    }
    return elegidas
}
