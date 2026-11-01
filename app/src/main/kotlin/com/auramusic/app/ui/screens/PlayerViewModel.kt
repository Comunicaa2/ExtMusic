package com.auramusic.app.ui.screens

import android.content.ComponentName
import android.content.Context
import coil.imageLoader
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.auramusic.app.R
import com.auramusic.app.data.api.*
import com.auramusic.app.data.db.SongDao
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.data.repository.RecommendationRepository
import com.auramusic.app.data.repository.RepositorioPreferencias
import com.auramusic.app.domain.model.AudioQuality
import com.auramusic.app.domain.model.kbpsAprox
import com.auramusic.app.domain.model.OrigenCancion
import com.auramusic.app.service.MusicService
import com.auramusic.app.service.ReproduccionAutomatica
import com.auramusic.app.work.DescargaWorker
import com.auramusic.app.ui.components.AudioQualityBadge
import com.auramusic.app.ui.components.bitrateToQualityBadge
import com.auramusic.app.util.sinUrls
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * Reproductor. Desde el lote de bugs críticos, la COLA vive dentro de ExoPlayer
 * (cola nativa multi-pista), no en este ViewModel:
 *  - reproducirCola() carga TODA la lista con setMediaItems(); las pistas de
 *    YouTube usan la uri "ytstream:VIDEOID", que el SERVICIO resuelve al vuelo.
 *  - El auto-avance lo hace ExoPlayer en el servicio → sigue con la app cerrada
 *    (Bug B) y skip usa seekToNext/Previous nativos → sin loops manuales (Bug A).
 *  - Aquí solo reaccionamos a onMediaItemTransition para pintar la UI.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val innertubeClient: InnertubeClient,
    private val songDao: SongDao,
    private val recommendationRepository: RecommendationRepository,
    private val repositorioPreferencias: RepositorioPreferencias,
    private val reproduccionAutomatica: ReproduccionAutomatica
) : ViewModel() {

    private val TAG = "AuraVM"

    val currentSong    = MutableStateFlow<SongEntity?>(null)
    val isPlaying      = MutableStateFlow(false)
    val progressMs     = MutableStateFlow(0L)
    val durationMs     = MutableStateFlow(0L)
    val lyrics         = MutableStateFlow<LyricsResult?>(null)
    val isLoading      = MutableStateFlow(false)
    val errorMessage   = MutableStateFlow<String?>(null)   // ← visible en pantalla
    // ── Cola visible (hoja "A continuación") ──────────────────────────────
    /** Una canción de la cola de ExoPlayer. [clave] es única aunque se repita la canción. */
    data class ItemCola(val clave: String, val mediaId: String, val titulo: String,
        val artista: String, val portada: String?, val duracionMs: Long?)
    val cola = MutableStateFlow<List<ItemCola>>(emptyList())
    val indiceActual = MutableStateFlow(0)
    /** De dónde salió la cola ("Búsqueda", el álbum, el artista…); null si no se sabe. */
    val origenCola = MutableStateFlow<String?>(null)
    // ── Reproductor a pantalla completa ───────────────────────────────────
    /**
     * El reproductor es una capa encima de la navegación (MainActivity), no una
     * pantalla más: así el Inicio no se destruye al abrirlo y la portada puede volar
     * de vuelta al mini al cerrarlo.
     */
    val reproductorAbierto = MutableStateFlow(false)
    fun abrirReproductor() { reproductorAbierto.value = true }
    fun cerrarReproductor() { reproductorAbierto.value = false }
    private val prefsCola = context.getSharedPreferences(MusicService.PREFS_COLA, Context.MODE_PRIVATE)
    // playlistId de la lista de YT Music (Aleatorio/Mix) que se está pidiendo.
    val cargandoListaYtm = MutableStateFlow<String?>(null)
    // ── Aleatorio y repetir (botones del reproductor) ─────────────────────
    /** El mismo ajuste que "Barajar al reproducir una lista": el botón también baraja la cola que suena. */
    val aleatorio = repositorioPreferencias.ajustes.map { it.colaAleatoria }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    // Orden de las siguientes antes de barajar (mediaIds), para volver a él al apagarlo.
    private var ordenAntesDeBarajar: List<String>? = null
    val modoRepetir = MutableStateFlow(Player.REPEAT_MODE_OFF)
    // ── Tarjeta "Artista" del reproductor ─────────────────────────────────
    val artistaActual = MutableStateFlow<PaginaArtista?>(null)
    private val artistasVistos = mutableMapOf<String, PaginaArtista?>()
    private var trabajoArtista: Job? = null
    val qualityBadge   = MutableStateFlow(AudioQualityBadge.UNKNOWN)
    val currentBitrate = MutableStateFlow(0)
    val recommendations = MutableStateFlow<List<SearchItem>>(emptyList())
    /** Ya se leyeron las recomendaciones guardadas: el Inicio espera a esto para no saltar. */
    val recomendacionesListas = MutableStateFlow(false)

    // Q: si "Ocultar contenido explícito" está activo, las listas ocultan (no
    // borran) las canciones explicit. Reactivo: combina la lista con el ajuste.
    private fun Flow<List<SongEntity>>.ocultandoExplicito(): Flow<List<SongEntity>> =
        combine(this, repositorioPreferencias.ajustes) { lista, aj ->
            if (aj.ocultarExplicito) lista.filter { !it.explicit } else lista
        }

    val recentlyPlayed  = songDao.getRecentlyPlayed().ocultandoExplicito()
    val recentesStreaming = songDao.getRecentlyPlayedStreaming().ocultandoExplicito()  // Inicio: solo YouTube (Mejora 12)
    val likedSongs      = songDao.getLiked().ocultandoExplicito()
    val downloadedSongs = songDao.getDownloaded().ocultandoExplicito()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var progressJob: Job? = null
    private var serviceConnected = false

    // Copia EN MEMORIA de la cola (solo para pintar metadatos por mediaId al
    // cambiar de pista). El orden y el avance reales los gobierna ExoPlayer.
    private var colaActual: List<SongEntity> = emptyList()

    private var ultimoComando = 0L            // debounce de skip (Bug 7)
    // Bug A: bloqueo de cambio en curso. Se libera al confirmarse la transición
    // (onMediaItemTransition) o tras un tope de seguridad, para no quedar trabado.
    private var cambiandoCancion = false
    private var inicioCambio = 0L
    private var errorJob: Job? = null         // muestra el error con retardo (ver onPlayerError)
    // Qué repetir con "Intentar de nuevo" si el error no es de la canción que suena
    // (p. ej. no se pudo cargar el Mix); null = reintentar la reproducción.
    private var reintentoPendiente: (() -> Unit)? = null
    private var watchdogJob: Job? = null      // Bug U: corta el "cargando" infinito

    // AA: hora (epoch ms) a la que el temporizador pausará, o null si no hay timer.
    val temporizadorActivoHasta = MutableStateFlow<Long?>(null)

    // Fallos 5 y 6: veces que ha vuelto la conexión. La UI lo usa para reintentar
    // portadas (LocalIntentoRed) y aquí se vuelve a pedir la letra que faltaba.
    val intentoRed = MutableStateFlow(0)
    private var redPerdida = false
    private val vigilanteRed = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onLost(network: android.net.Network) { redPerdida = true }
        // Se espera a que Android confirme que hay internet (VALIDATED): con
        // onAvailable la red aún no resolvía DNS y la portada volvía a fallar.
        override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
            if (redPerdida && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                redPerdida = false; intentoRed.value++
            }
        }
    }

    init {
        runCatching {
            context.getSystemService(android.net.ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(vigilanteRed)
        }
        connectToService()
        loadRecommendations()
        observarLetras()
    }

    // Bug I: las letras se cargan aquí, observando currentSong, NO dentro de la
    // transición del reproductor. Así da igual qué ruta cambie la canción (play,
    // skip, auto-avance, reconexión): cada vez que cambia el id de la canción
    // actual se vuelven a pedir las letras a LRCLIB con su título/artista.
    private fun observarLetras() {
        viewModelScope.launch {
            // Se vuelve a pedir al cambiar de canción y, si faltaba, al volver la red.
            var ultimoId: String? = null
            combine(currentSong.map { it?.id }.distinctUntilChanged(), intentoRed) { id, _ -> id }
                .collectLatest { id ->
                    if (id.isNullOrBlank()) { lyrics.value = null; ultimoId = null; return@collectLatest }
                    if (id == ultimoId && lyrics.value != null) return@collectLatest   // ya la tiene
                    ultimoId = id
                    lyrics.value = null
                    // D2: si la canción está descargada y tiene .lrc en disco, lee la
                    // letra LOCAL (offline, sin red) en vez de consultar LRCLIB.
                    val local = DescargaWorker.archivoLetra(context, id)
                    if (local.exists()) {
                        val res = runCatching {
                            innertubeClient.parsearLrcLocal(local.readText())
                        }.getOrNull()
                        if (res != null) { lyrics.value = res; return@collectLatest }
                    }
                    val s = currentSong.value
                    if (s == null || s.title.isBlank()) { lyrics.value = null; return@collectLatest }
                    val seg = (s.durationMs / 1000).toInt()
                    lyrics.value = runCatching {
                        innertubeClient.getLyrics(s.title, s.artist, seg)
                    }.getOrNull()
                }
        }
    }

    private fun connectToService() {
        try {
            val token = SessionToken(context, ComponentName(context, MusicService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            controllerFuture = future
            future.addListener({
                try {
                    val mc = future.get()
                    mediaController = mc
                    serviceConnected = true
                    // La repetición la elige el usuario con el botón del reproductor y
                    // vive en el servicio: al reconectar se respeta la que haya.
                    modoRepetir.value = mc.repeatMode
                    // Sin aleatorio por defecto: la cola respeta el orden hasta que el
                    // usuario active el shuffle explícitamente (Bug 6).
                    mc.shuffleModeEnabled = false
                    setupPlayerListeners(mc)
                    leerCola(mc)
                    // Tras (re)conectar, leer lo que YA está en la sesión para
                    // que el mini player reaparezca al volver a la app (Bug 4).
                    sincronizarDesdeSesion(mc)
                    Log.d(TAG, "✅ MediaController listo")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ MediaController falló: ${e.message.sinUrls()}")
                    viewModelScope.launch {
                        delay(2000)
                        connectToService()
                    }
                }
            }, MoreExecutors.directExecutor())
        } catch (e: Exception) {
            Log.e(TAG, "connectToService exception: ${e.message.sinUrls()}")
        }
    }

    private fun setupPlayerListeners(mc: MediaController) {
        mc.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
                if (playing) { cancelarWatchdog(); startTick() } else stopTick()
            }
            // Cada vez que ExoPlayer pasa de pista (manual o automático) pintamos
            // la canción actual desde su mediaId. Es el ÚNICO lugar donde cambia
            // currentSong durante la reproducción de la cola.
            // La radio amplió la cola en segundo plano: precarga la portada que entra.
            override fun onRepeatModeChanged(modo: Int) { modoRepetir.value = modo }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                precargarPortadaSiguiente()
                leerCola(mc)
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                cambiandoCancion = false      // Bug A: cambio confirmado
                errorJob?.cancel()
                indiceActual.value = mc.currentMediaItemIndex
                progressMs.value = 0L
                // En un paso automático de la cola el estado sigue en READY y
                // onPlaybackStateChanged no salta: la duración vieja se quedaría.
                durationMs.value = mediaController?.duration?.coerceAtLeast(0L) ?: 0L
                val id = item?.mediaId
                if (item == null || id.isNullOrBlank()) { currentSong.value = null; return }
                pintarCancion(id, item)
            }
            override fun onPlaybackStateChanged(state: Int) {
                val mc2 = mediaController ?: return
                when (state) {
                    Player.STATE_READY     -> {
                        durationMs.value = mc2.duration.coerceAtLeast(0L)
                        guardarDuracion(mc2.currentMediaItem?.mediaId, mc2.duration)
                        isLoading.value = false
                        errorMessage.value = null
                        errorJob?.cancel()
                        cancelarWatchdog()
                        cambiandoCancion = false
                        Log.d(TAG, "▶ READY — duración: ${mc2.duration}ms")
                    }
                    Player.STATE_BUFFERING -> { isLoading.value = true; errorJob?.cancel() }
                    // Fin de la cola: la reproducción automática la gestiona el servicio.
                    Player.STATE_ENDED     -> { isLoading.value = false }
                    Player.STATE_IDLE      -> { isLoading.value = false }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "❌ ${error.errorCode}: ${error.message.sinUrls()}")
                Log.e(TAG, "  causa: ${error.cause?.javaClass?.simpleName}: ${error.cause?.message.sinUrls()}")
                // El SERVICIO reintenta solo ante errores de red (Bug D). Por eso NO
                // mostramos el error de inmediato: si el reintento funciona en pocos
                // segundos, ni se ve; si falla de verdad, el banner aparece luego.
                val msg = mapearError(error)
                errorJob?.cancel()
                errorJob = viewModelScope.launch {
                    delay(3200)
                    reintentoPendiente = null
                    errorMessage.value = msg
                    isLoading.value = false
                    isPlaying.value = false
                }
            }
        })
    }

    // Fallo 3: sin red, el error que llega suele ser genérico (2000, "No se pudo
    // resolver…") y se mostraba "Error de reproducción (2000)". Se mira la red.
    private fun hayRed(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun mapearError(error: PlaybackException): String = if (!hayRed())
        context.getString(R.string.error_sin_conexion)
    else when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            context.getString(R.string.error_sin_conexion)
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            context.getString(R.string.error_http, error.cause?.message ?: "")
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            context.getString(R.string.error_formato)
        PlaybackException.ERROR_CODE_TIMEOUT ->
            context.getString(R.string.error_timeout)
        else -> context.getString(R.string.error_reproduccion, error.errorCode)
    }

    // Pinta currentSong + letras + badge a partir del id de la pista que empezó.
    private fun pintarCancion(id: String, item: MediaItem) {
        viewModelScope.launch {
            errorMessage.value = null
            val deCola = colaActual.find { it.id == id }
            val guardada = songDao.getById(id)
            // Preferimos la fila de BD (conserva likes/descargas) sobre la de la cola.
            val cancion = guardada ?: deCola ?: SongEntity(
                id = id,
                title = item.mediaMetadata.title?.toString() ?: "",
                artist = item.mediaMetadata.artist?.toString() ?: "",
                artistId = "", album = "",
                thumbnailUrl = item.mediaMetadata.artworkUri?.toString() ?: "",
                durationMs = 0L
            )
            currentSong.value = cancion
            precargarPortadaSiguiente()
            // Registra la canción nueva (de YouTube) si aún no estaba en BD.
            if (guardada == null && deCola != null && deCola.title.isNotBlank()) songDao.upsert(deCola)
            songDao.incrementPlayCount(id)

            // Badge de calidad: aprox. por la calidad descargada o por la preferencia.
            qualityBadge.value =
                if (cancion.isDownloaded && cancion.downloadQuality > 0)
                    bitrateToQualityBadge(cancion.downloadQuality)
                else badgeDeCalidad(repositorioPreferencias.ajustes.first().audioQuality)
            // Las letras las carga observarLetras() al cambiar currentSong (Bug I).
        }
    }

    // La radio y algunas listas no traen la duración: se guarda la real al cargar la
    // canción, para que la barra la muestre aunque la próxima vez se abra en pausa.
    private fun guardarDuracion(id: String?, ms: Long) {
        val s = currentSong.value ?: return
        // READY puede llegar antes de que se pinte la canción nueva: solo si es la misma.
        if (ms <= 0L || s.id != id || s.durationMs > 0L) return
        val conDuracion = s.copy(durationMs = ms)
        currentSong.value = conDuracion
        // Se parte de la fila de la BD (no de currentSong) para no pisar likes o descargas.
        viewModelScope.launch { songDao.getById(s.id)?.takeIf { it.durationMs <= 0L }?.let { songDao.upsert(it.copy(durationMs = ms)) } }
    }

    // Descarga ya la portada de la próxima canción a la caché de imágenes, para que
    // al cambiar aparezca al instante (el audio ya lo precarga el servicio).
    private fun precargarPortadaSiguiente() {
        val mc = mediaController ?: return
        val i = mc.nextMediaItemIndex
        if (i == androidx.media3.common.C.INDEX_UNSET) return
        val url = mejorarCaratula(mc.getMediaItemAt(i).mediaMetadata.artworkUri?.toString()) ?: return
        context.imageLoader.enqueue(coil.request.ImageRequest.Builder(context).data(url).build())
    }

    private fun badgeDeCalidad(q: AudioQuality): AudioQualityBadge = bitrateToQualityBadge(q.kbpsAprox)

    // La posición se lee cada 0,5 s; con la letra a la vista, cada 0,1 s para que
    // el cambio de línea llegue a tiempo (antes podía ir medio segundo tarde).
    @Volatile private var letrasVisibles = false
    fun setLetrasVisibles(visibles: Boolean) { letrasVisibles = visibles }

    // Con la app en segundo plano o la pantalla apagada nadie lee la posición:
    // el tic se para y, al volver, arranca de nuevo con la posición al día.
    @Volatile private var interfazVisible = true
    fun setInterfazVisible(visible: Boolean) {
        interfazVisible = visible
        if (!visible) { stopTick(); return }
        val mc = mediaController ?: return
        progressMs.value = mc.currentPosition.coerceAtLeast(0L)
        if (mc.isPlaying) startTick()
    }

    private fun startTick() {
        progressJob?.cancel()
        if (!interfazVisible) return
        progressJob = viewModelScope.launch {
            while (true) {
                val mc = mediaController
                progressMs.value = mc?.currentPosition?.coerceAtLeast(0L) ?: 0L
                // La duración del stream puede conocerse o corregirse después de READY.
                mc?.duration?.takeIf { it > 0L && it != durationMs.value }?.let { durationMs.value = it }
                delay(if (letrasVisibles) 100 else 500)
            }
        }
    }
    private fun stopTick() { progressJob?.cancel() }

    // ── RECONEXIÓN A LA SESIÓN EN CURSO (Bug 4) ─────────────────────────────
    // Al (re)conectar el MediaController, la sesión del servicio puede tener ya
    // una cola sonando o en pausa. Reconstruimos la cola en memoria desde la
    // timeline para que skip siguiente/anterior funcionen al volver, y pintamos
    // la pista actual (los listeners solo disparan ante CAMBIOS, no al enganchar).
    private fun sincronizarDesdeSesion(mc: MediaController) {
        val item = mc.currentMediaItem ?: return
        if (origenCola.value == null) origenCola.value = prefsCola.getString("origen", null)
        isPlaying.value  = mc.isPlaying
        durationMs.value = mc.duration.coerceAtLeast(0L)
        progressMs.value = mc.currentPosition.coerceAtLeast(0L)
        if (mc.isPlaying) startTick()

        val reconstruida = mutableListOf<SongEntity>()
        for (i in 0 until mc.mediaItemCount) {
            val mi = mc.getMediaItemAt(i)
            reconstruida.add(
                SongEntity(
                    id = mi.mediaId,
                    title = mi.mediaMetadata.title?.toString() ?: "",
                    artist = mi.mediaMetadata.artist?.toString() ?: "",
                    artistId = "", album = "",
                    thumbnailUrl = mi.mediaMetadata.artworkUri?.toString() ?: "",
                    durationMs = 0L
                )
            )
        }
        if (reconstruida.isNotEmpty()) colaActual = reconstruida

        viewModelScope.launch {
            val id = item.mediaId
            val desdeBd = if (id.isNotBlank()) songDao.getById(id) else null
            currentSong.value = desdeBd ?: colaActual.find { it.id == id } ?: SongEntity(
                id = id,
                title = item.mediaMetadata.title?.toString() ?: "",
                artist = item.mediaMetadata.artist?.toString() ?: "",
                artistId = "", album = "",
                thumbnailUrl = item.mediaMetadata.artworkUri?.toString() ?: "",
                durationMs = mc.duration.coerceAtLeast(0L)
            )
            Log.d(TAG, "🔄 Sesión recuperada: ${currentSong.value?.title} (playing=${mc.isPlaying})")
        }
    }

    // ── Construcción de MediaItem (una canción → una pista de ExoPlayer) ─────
    // LOCAL/descargada → uri real del archivo. YouTube en streaming → uri interna
    // "ytstream:VIDEOID" que el SERVICIO resuelve a la URL real al reproducirla.
    private suspend fun construirMediaItem(cancion: SongEntity): MediaItem =
        reproduccionAutomatica.mediaItemDe(cancion)

    // ── COLA NATIVA ─────────────────────────────────────────────────────────
    /** Carga la lista completa en ExoPlayer desde startIndex y reproduce. */
    fun reproducirCola(canciones: List<SongEntity>, startIndex: Int = 0, origen: String? = null) {
        if (canciones.isEmpty()) return
        origenCola.value = origen
        // Se guarda junto a la cola del servicio para mostrarlo al volver a abrir la app.
        prefsCola.edit().putString("origen", origen).apply()
        viewModelScope.launch {
            val mc = esperarController() ?: run {
                Log.w(TAG, "⚠ Sin MediaController; no se pudo reproducir la cola"); return@launch
            }
            isLoading.value = true
            errorMessage.value = null
            errorJob?.cancel()
            // Bug J (punto 4): validación final antes de encolar. Descarta items sin
            // metadatos válidos (id/título) para que ningún video general "fantasma"
            // corte la reproducción. El item ELEGIDO por el usuario SIEMPRE se conserva,
            // aunque el resto se filtre (no se le niega una reproducción explícita).
            val elegido = canciones[startIndex.coerceIn(0, canciones.lastIndex)]
            val validas = canciones
                .filter { esCancionValida(it) || it.id == elegido.id }
                .ifEmpty { listOf(elegido) }
            // Ajuste "Reproducción aleatoria": la elegida suena primero y el resto se
            // baraja. Se reordena la lista (no shuffleModeEnabled) para que colaActual
            // y la precarga de la siguiente coincidan con lo que de verdad va a sonar.
            val barajar = repositorioPreferencias.ajustes.first().colaAleatoria
            val lista = if (barajar)
                listOf(elegido) + validas.filter { it.id != elegido.id }.shuffled()
            else validas
            ordenAntesDeBarajar = if (barajar) validas.filter { it.id != elegido.id }.map { it.id } else null
            val inicio = lista.indexOfFirst { it.id == elegido.id }.coerceAtLeast(0)
            colaActual = lista
            // Fix 1: pinta la canción YA (antes de resolver el stream). Esto dispara
            // observarLetras() en paralelo con la carga del audio, así las letras no
            // esperan a que ExoPlayer confirme reproducción. Si está descargada, su
            // .lrc local se lee al instante.
            currentSong.value = lista[inicio]
            val items = lista.map { construirMediaItem(it) }
            marcarCambiando()
            mc.setMediaItems(items, inicio, 0L)
            mc.prepare()
            mc.play()
        }
    }

    // Bug J: una canción es válida para encolar si tiene id y título. La sync de YT
    // Music ya viene filtrada a categoría música, así que esto atrapa restos
    // malformados sin descartar canciones legítimas (la radio no trae duración, por
    // eso NO se valida por duración aquí).
    private fun esCancionValida(c: SongEntity): Boolean =
        c.id.isNotBlank() && c.title.isNotBlank()

    // Espera breve a que el MediaController termine de conectar (la cola puede
    // pedirse justo al arrancar la app, antes de que la sesión esté lista).
    private suspend fun esperarController(): MediaController? {
        repeat(20) {
            mediaController?.let { return it }
            delay(150)
        }
        return mediaController
    }

    // ── REPRODUCIR (entradas públicas; cada una arma su cola) ───────────────
    fun playSong(item: SearchItem, origen: String? = null) {
        val entity = SongEntity(
            id = item.videoId, title = item.title, artist = item.artist,
            artistId = item.artistId, album = "", thumbnailUrl = item.thumbnailUrl,
            durationMs = parseDuration(item.duration),
            origen = OrigenCancion.YOUTUBE.name, fuente = item.videoId,
            explicit = item.explicit
        )
        reproducirCola(listOf(entity), 0, origen)
    }

    /**
     * Cola de YT Music ya armada (Aleatorio o Mix de un artista): se pide y se reproduce.
     * [onLista] se llama solo si cargó (para abrir el reproductor); los toques
     * repetidos mientras carga se ignoran.
     */
    fun reproducirListaYtm(lista: PlaylistRef, origen: String? = null, onLista: () -> Unit = {}) {
        if (cargandoListaYtm.value != null) return
        cargandoListaYtm.value = lista.playlistId
        viewModelScope.launch {
            try {
                val canciones = innertubeClient.colaDePlaylist(lista).filter { it.isSong }
                if (canciones.isEmpty()) {
                    reintentoPendiente = { reproducirListaYtm(lista, origen, onLista) }
                    errorMessage.value = context.getString(R.string.error_cola_vacia)
                    return@launch
                }
                reproducirCola(canciones.map { it.toSongEntity() }, 0, origen)
                onLista()
            } finally {
                cargandoListaYtm.value = null
            }
        }
    }

    /**
     * "Reproducir todo" de un artista: pide su lista completa de populares (playlist
     * VL…) y la reproduce desde la primera. Si no hay lista o llega vacía, suenan
     * las populares que ya se ven ([respaldo]). Mismo bloqueo que [reproducirListaYtm].
     */
    fun reproducirColeccionYtm(browseId: String?, respaldo: List<SearchItem>, origen: String? = null, onLista: () -> Unit = {}) {
        val clave = browseId ?: "respaldo"
        if (cargandoListaYtm.value != null) return
        cargandoListaYtm.value = clave
        viewModelScope.launch {
            try {
                val todas = browseId?.let { innertubeClient.coleccion(it) }
                if (todas?.isFailure == true && respaldo.isEmpty()) {
                    reintentoPendiente = { reproducirColeccionYtm(browseId, respaldo, origen, onLista) }
                    errorMessage.value = context.getString(R.string.error_cola_vacia)
                    return@launch
                }
                val canciones = todas?.getOrNull()?.canciones.orEmpty().ifEmpty { respaldo }
                if (canciones.isEmpty()) {
                    errorMessage.value = context.getString(R.string.error_cola_vacia); return@launch
                }
                reproducirCola(canciones.map { it.toSongEntity() }, 0, origen)
                onLista()
            } finally {
                cargandoListaYtm.value = null
            }
        }
    }

    // La cola cambia sola (la radio añade canciones, el usuario reordena o quita):
    // se relee entera de ExoPlayer cada vez.
    private fun leerCola(mc: MediaController) {
        val vistas = mutableMapOf<String, Int>()
        cola.value = (0 until mc.mediaItemCount).map { i ->
            val mi = mc.getMediaItemAt(i)
            val n = vistas.merge(mi.mediaId, 1, Int::plus)!!
            ItemCola(
                clave = "${mi.mediaId}#$n", mediaId = mi.mediaId,
                titulo = mi.mediaMetadata.title?.toString().orEmpty(),
                artista = mi.mediaMetadata.artist?.toString().orEmpty(),
                portada = mi.mediaMetadata.artworkUri?.toString(),
                duracionMs = mi.mediaMetadata.durationMs
            )
        }
        indiceActual.value = mc.currentMediaItemIndex
    }

    /** Mueve una canción de la cola (arrastrar ≡). Índices de la cola completa. */
    fun moverEnCola(desde: Int, hasta: Int) {
        val mc = mediaController ?: return
        if (desde == hasta || desde !in 0 until mc.mediaItemCount || hasta !in 0 until mc.mediaItemCount) return
        mc.moveMediaItem(desde, hasta)
    }

    /** Quita una canción de la cola (deslizar). La que suena no se quita. */
    fun quitarDeCola(indice: Int) {
        val mc = mediaController ?: return
        if (indice == mc.currentMediaItemIndex || indice !in 0 until mc.mediaItemCount) return
        mc.removeMediaItem(indice)
    }

    /** Vuelve a meter una canción quitada ("Deshacer"). */
    fun reinsertarEnCola(indice: Int, item: ItemCola) {
        viewModelScope.launch {
            val mc = mediaController ?: return@launch
            val cancion = songDao.getById(item.mediaId) ?: SongEntity(
                id = item.mediaId, title = item.titulo, artist = item.artista, artistId = "", album = "",
                thumbnailUrl = item.portada.orEmpty(), durationMs = item.duracionMs ?: 0L,
                origen = OrigenCancion.YOUTUBE.name, fuente = item.mediaId
            )
            mc.addMediaItem(indice.coerceIn(0, mc.mediaItemCount), construirMediaItem(cancion))
        }
    }

    /** ⋮ "Reproducir a continuación": entra justo después de la que suena. */
    fun reproducirDespues(cancion: SongEntity) = encolar(cancion, despuesDeLaActual = true)

    /** ⋮ "Añadir a la cola": entra al final. */
    fun anadirACola(cancion: SongEntity) = encolar(cancion, despuesDeLaActual = false)

    // Sin nada en la cola, la canción suena ya. La cola se guarda sola al cambiar
    // (MusicService.guardarCola) y leerCola() la vuelve a pintar.
    private fun encolar(cancion: SongEntity, despuesDeLaActual: Boolean) {
        viewModelScope.launch {
            val mc = esperarController() ?: return@launch
            if (mc.mediaItemCount == 0) { reproducirCola(listOf(cancion)); return@launch }
            val indice = if (despuesDeLaActual) mc.currentMediaItemIndex + 1 else mc.mediaItemCount
            mc.addMediaItem(indice.coerceIn(0, mc.mediaItemCount), construirMediaItem(cancion))
        }
    }

    /** Guarda la canción si aún no está en la BD (una playlist solo admite canciones guardadas). */
    fun guardarSiFalta(cancion: SongEntity, alTerminar: () -> Unit) {
        viewModelScope.launch {
            if (songDao.getById(cancion.id) == null) songDao.upsert(cancion)
            alTerminar()
        }
    }

    /**
     * Botón Aleatorio: cambia el ajuste "Barajar al reproducir una lista" y baraja
     * las siguientes de la cola que suena; al apagarlo vuelven a su orden. Se mueven
     * las pistas que ya están (moveMediaItem) en vez de cambiarlas por otras.
     */
    fun toggleAleatorio() {
        viewModelScope.launch {
            val activar = !repositorioPreferencias.ajustes.first().colaAleatoria
            repositorioPreferencias.guardarColaAleatoria(activar)
            val mc = mediaController ?: return@launch
            val desde = mc.currentMediaItemIndex + 1
            val ids = (desde until mc.mediaItemCount).map { mc.getMediaItemAt(it).mediaId }
            if (ids.size < 2) return@launch
            val objetivo: List<Int> = if (activar) {
                ordenAntesDeBarajar = ids
                ids.indices.shuffled()
            } else {
                val orden = ordenAntesDeBarajar ?: return@launch
                ordenAntesDeBarajar = null
                // Las que entraron después (radio) quedan al final, en su orden.
                ids.indices.sortedBy { i -> orden.indexOf(ids[i]).let { if (it < 0) Int.MAX_VALUE else it } }
            }
            val actual = ids.indices.toMutableList()
            objetivo.forEachIndexed { k, i ->
                val j = actual.indexOf(i)
                if (j != k) { mc.moveMediaItem(desde + j, desde + k); actual.add(k, actual.removeAt(j)) }
            }
        }
    }

    /** Botón Repetir: no → toda la cola → esta canción → no. */
    fun cambiarRepetir() {
        val mc = mediaController ?: return
        mc.repeatMode = when (mc.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /**
     * Tarjeta "Artista" del reproductor: la página de YT Music del artista principal
     * de la canción. Se pide al abrir el reproductor o cambiar de canción, y se
     * recuerda por artista (también los que no se encuentran) para no repetirla.
     */
    fun cargarArtistaActual() {
        val s = currentSong.value ?: return
        val nombre = extractMainArtist(s.artist)
        if (nombre.isBlank()) { artistaActual.value = null; return }
        val clave = s.artistId.takeIf { it.startsWith("UC") } ?: nombre.lowercase()
        if (artistasVistos.containsKey(clave)) { artistaActual.value = artistasVistos[clave]; return }
        trabajoArtista?.cancel()
        artistaActual.value = null
        trabajoArtista = viewModelScope.launch {
            val id = s.artistId.takeIf { it.startsWith("UC") }
                ?: innertubeClient.idArtista(nombre).getOrElse { return@launch } ?: run {
                    artistasVistos[clave] = null; return@launch
                }
            innertubeClient.artista(id).onSuccess { artistasVistos[clave] = it; artistaActual.value = it }
        }
    }

    /** Tocar una canción de la cola: salta a ella y suena. */
    fun saltarA(indice: Int) {
        val mc = mediaController ?: return
        if (indice !in 0 until mc.mediaItemCount) return
        marcarCambiando()
        mc.seekToDefaultPosition(indice)
        mc.play()
    }

    // Reproducir UNA canción suelta (un toque, sin lista alrededor).
    fun reproducirLocal(cancion: SongEntity) = reproducirCola(listOf(cancion), 0)
    fun reproducir(cancion: SongEntity) = reproducirCola(listOf(cancion), 0)

    /** D3: reproduce un videoId suelto (deep-link de music.youtube.com). Si ya está
     *  en la BD usa sus metadatos; si no, arma una entrada mínima de YouTube. */
    fun reproducirVideoId(videoId: String) {
        if (videoId.isBlank()) return
        viewModelScope.launch {
            val guardada = songDao.getById(videoId)
            // Fallo 4: si no está en la biblioteca, se piden sus datos a YT Music;
            // antes sonaba sin título, artista ni portada (también en la notificación).
            val incompleta = guardada == null || guardada.title.isBlank()
            val datos = if (incompleta) innertubeClient.cancionPorId(videoId) else null
            val cancion = guardada?.takeIf { !incompleta } ?: (guardada ?: SongEntity(
                id = videoId, title = "", artist = "", artistId = "", album = "",
                thumbnailUrl = "", durationMs = 0L, origen = OrigenCancion.YOUTUBE.name, fuente = videoId
            )).copy(
                title = datos?.title ?: guardada?.title.orEmpty(),
                artist = datos?.artist ?: guardada?.artist.orEmpty(),
                thumbnailUrl = datos?.thumbnailUrl ?: guardada?.thumbnailUrl.orEmpty(),
                explicit = datos?.explicit ?: guardada?.explicit ?: false
            )
            if (incompleta && datos != null) songDao.upsert(cancion)
            reproducirCola(listOf(cancion), 0)
        }
    }

    fun togglePlayPause() {
        val mc = mediaController ?: return
        if (mc.isPlaying) mc.pause() else mc.play()
    }

    // Bug 13: protege el seek. Verifica sesión + item válido y acota la posición al
    // rango [0, duración]; envuelve en try-catch para no cerrar la app.
    fun seekTo(ms: Long) {
        val mc = mediaController ?: return
        if (mc.currentMediaItem == null) return
        try {
            val dur = mc.duration
            val destino = if (dur > 0L) ms.coerceIn(0L, dur) else ms.coerceAtLeast(0L)
            mc.seekTo(destino)
            // Al instante: si no, la barra y la letra vuelven atrás hasta el próximo tic.
            progressMs.value = destino
        } catch (e: Exception) {
            Log.e(TAG, "seekTo($ms) falló: ${e.javaClass.simpleName}: ${e.message.sinUrls()}")
        }
    }

    // Debounce + bloqueo de cambio en curso (Bug 7 + Bug A).
    private fun marcarCambiando() {
        cambiandoCancion = true
        inicioCambio = System.currentTimeMillis()
        iniciarWatchdog()
    }

    // Bug U: si tras 15s no empezó a sonar (poToken caducado, sin red, o BravePipe
    // no pudo extraer el stream), corta el "cargando" infinito y muestra el error
    // con botón "Intentar de nuevo" (diálogo global). El error exacto ya se loggea
    // en onPlayerError / en el resolver del servicio.
    private fun iniciarWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            delay(15_000)
            if (!isPlaying.value) {
                Log.e(TAG, "⏱ Timeout 15s: la canción no empezó a reproducir (poToken/red/extracción)")
                errorMessage.value = context.getString(R.string.error_timeout_repro)
                isLoading.value = false
            }
        }
    }
    private fun cancelarWatchdog() { watchdogJob?.cancel() }

    // AA: envía el comando al servicio y recuerda la hora de fin para la UI.
    fun iniciarTemporizador(min: Int) {
        val mc = mediaController ?: return
        val args = Bundle().apply { putInt(MusicService.ARG_SLEEP_MIN, min) }
        mc.sendCustomCommand(SessionCommand(MusicService.CMD_SLEEP_START, Bundle.EMPTY), args)
        temporizadorActivoHasta.value = System.currentTimeMillis() + min * 60_000L
    }
    fun cancelarTemporizador() {
        val mc = mediaController ?: return
        mc.sendCustomCommand(SessionCommand(MusicService.CMD_SLEEP_CANCEL, Bundle.EMPTY), Bundle.EMPTY)
        temporizadorActivoHasta.value = null
    }
    private fun comandoPermitido(): Boolean {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoComando < 250L) return false
        // Bug A: si hay un cambio aún sin confirmar (y reciente), ignora el comando
        // para no encadenar cambios en loop. El tope de 2s evita quedar trabado.
        if (cambiandoCancion && ahora - inicioCambio < 2000L) return false
        ultimoComando = ahora
        return true
    }

    // Skip NATIVO sobre la cola de ExoPlayer (Bug A): sin reconstruir items a mano.
    fun skipNext() {
        if (!comandoPermitido()) return
        val mc = mediaController ?: return
        if (mc.hasNextMediaItem()) {
            marcarCambiando()
            mc.seekToNextMediaItem()
            mc.play()
        } else {
            // Última canción: el servicio añade canciones de la radio y avanza.
            mc.sendCustomCommand(SessionCommand(MusicService.CMD_SIGUIENTE, Bundle.EMPTY), Bundle.EMPTY)
        }
    }
    fun skipPrevious() {
        if (!comandoPermitido()) return
        val mc = mediaController ?: return
        val pos = mc.currentPosition
        if (pos > 3000L || !mc.hasPreviousMediaItem()) {
            mc.seekTo(0)
        } else {
            marcarCambiando()
            mc.seekToPreviousMediaItem()
            mc.play()
        }
    }

    fun clearError() { errorMessage.value = null; errorJob?.cancel(); reintentoPendiente = null }

    /** Reintento manual desde el diálogo de error (Bug E). */
    fun reintentar() {
        reintentoPendiente?.let { repetir ->
            clearError(); repetir(); return
        }
        val mc = mediaController ?: return
        errorMessage.value = null
        errorJob?.cancel()
        mc.prepare()
        mc.play()
    }

    fun toggleLike() {
        viewModelScope.launch {
            val s = currentSong.value ?: return@launch
            val nuevoLike = !s.isLiked
            songDao.setLiked(s.id, nuevoLike)
            currentSong.value = s.copy(isLiked = nuevoLike)
            // P: si "Descargar al dar me gusta" está activo y se acaba de dar like a
            // una canción de streaming aún no descargada, encola la descarga (2º plano).
            if (nuevoLike && !s.isDownloaded && s.origen != OrigenCancion.LOCAL.name &&
                repositorioPreferencias.ajustes.first().descargarAlDarLike) {
                encolarDescarga(s)
            }
        }
    }

    // P: encola DescargaWorker para una canción (WorkManager, sobrevive al cierre).
    private fun encolarDescarga(s: SongEntity) {
        val videoId = s.fuente.ifBlank { s.id }
        val datos = androidx.work.workDataOf(
            DescargaWorker.KEY_VIDEO_ID to videoId,
            DescargaWorker.KEY_TITLE to s.title,
            DescargaWorker.KEY_ARTIST to s.artist,
            DescargaWorker.KEY_THUMB to s.thumbnailUrl,
            DescargaWorker.KEY_DURATION to s.durationMs
        )
        val req = androidx.work.OneTimeWorkRequestBuilder<DescargaWorker>().setInputData(datos).build()
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            DescargaWorker.nombreUnico(s.id), androidx.work.ExistingWorkPolicy.KEEP, req
        )
    }

    private fun loadRecommendations() {
        viewModelScope.launch {
            // Se muestran las guardadas (al instante) y las nuevas se piden y se guardan
            // para la próxima vez que se abra la app: así Selección rápida no cambia bajo
            // el dedo a los pocos segundos de abrir. Solo si no había guardadas (primera
            // vez) salen las nuevas al momento. Sin red llega una lista vacía, que ya no
            // borra las guardadas.
            val ocultarGuardadas = repositorioPreferencias.ajustes.first().ocultarExplicito
            val guardadas = recommendationRepository.guardadas()
            if (guardadas.isNotEmpty())
                recommendations.value = if (ocultarGuardadas) guardadas.filter { !it.explicit } else guardadas
            recomendacionesListas.value = true
            try {
                val inicio = System.currentTimeMillis()
                val recs = recommendationRepository.getRecommendations()
                Log.d(TAG, "Recomendaciones: ${recs.size} en ${System.currentTimeMillis() - inicio} ms")
                if (guardadas.isEmpty() && recs.isNotEmpty()) {
                    val ocultar = repositorioPreferencias.ajustes.first().ocultarExplicito  // Q
                    recommendations.value = if (ocultar) recs.filter { !it.explicit } else recs
                }
            } catch (e: Exception) { Log.e(TAG, "recs: ${e.message.sinUrls()}") }
        }
    }

    override fun onCleared() {
        runCatching {
            context.getSystemService(android.net.ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(vigilanteRed)
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        progressJob?.cancel()
        super.onCleared()
    }

    private fun parseDuration(d: String): Long {
        val p = d.split(":").mapNotNull { it.toLongOrNull() }
        return when (p.size) {
            2 -> (p[0] * 60 + p[1]) * 1000
            3 -> (p[0] * 3600 + p[1] * 60 + p[2]) * 1000
            else -> 0L
        }
    }
}
