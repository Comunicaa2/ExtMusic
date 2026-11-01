package com.auramusic.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.*
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.auramusic.app.MainActivity
import com.auramusic.app.data.api.InnertubeClient
import com.auramusic.app.domain.model.AppSettings
import com.auramusic.app.domain.model.AudioQuality
import com.auramusic.app.domain.model.CodecAudio
import com.auramusic.app.domain.model.codec
import com.auramusic.app.util.sinUrls
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@AndroidEntryPoint
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var sleepJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Ajuste "Reproducción en segundo plano": si está OFF y el usuario cierra la
    // app (desliza la tarea), se detiene la reproducción. Se lee de DataStore.
    @javax.inject.Inject lateinit var repositorioPreferencias:
        com.auramusic.app.data.repository.RepositorioPreferencias

    // Motor de extracción de YouTube (Bug B/D): resuelve la URL de stream DENTRO
    // del servicio, en el momento en que ExoPlayer abre cada pista de la cola.
    @javax.inject.Inject lateinit var innertube: InnertubeClient

    // Reproducción automática (radio de YT Music) al acabar la cola o al pulsar
    // "siguiente" en la última canción, venga de donde venga.
    @javax.inject.Inject lateinit var reproduccionAutomatica: ReproduccionAutomatica
    private var extendiendo = false

    // Para saber qué canciones están descargadas al limpiar la caché de audio.
    @javax.inject.Inject lateinit var songDao: com.auramusic.app.data.db.SongDao

    @Volatile private var permitirSegundoPlano = true

    // Ajuste "Normalizar volumen" (Android 9+): compresor sobre la sesión de audio.
    private val normalizador = NormalizadorVolumen()

    // Bug D: contador de reintentos por pista. Una URL de stream puede caducar en
    // segundo plano; al re-preparar, el ResolvingDataSource pide una URL fresca.
    private var reintentos = 0

    companion object {
        private const val TAG = "MusicService"
        // Esquema interno para pistas de YouTube cuya URL se resuelve al vuelo.
        // Opaco (sin "//") para tolerar videoIds con "-" y "_".
        const val ESQUEMA_YT = "ytstream"
        fun uriDeVideo(videoId: String): String = "$ESQUEMA_YT:$videoId"
        // Cola recordada (ver guardarCola). El respaldo la lee y la escribe con
        // este mismo formato: "items" (JSON), "indice" y "posicion".
        const val PREFS_COLA = "cola_guardada"

        private var cache: SimpleCache? = null

        // El límite (Ajustes ▸ Tamaño máximo de caché) se fija al crear la caché:
        // un cambio se aplica la próxima vez que se inicie la app.
        fun getOrCreateCache(dir: File, ctx: android.content.Context, maximoMb: Int): SimpleCache {
            if (cache == null) {
                cache = SimpleCache(
                    File(dir, "audio_cache"),
                    LeastRecentlyUsedCacheEvictor(maximoMb.toLong() * 1024 * 1024),
                    androidx.media3.database.StandaloneDatabaseProvider(ctx)
                )
            }
            return cache!!
        }
        /**
         * Vacía la caché de audio. Si el servicio ya la abrió, se borra con la API
         * de SimpleCache (borrar la carpeta con la caché abierta la corrompe).
         */
        fun limpiarCache(dir: File) {
            val abierta = cache
            if (abierta != null) abierta.keys.toList().forEach { runCatching { abierta.removeResource(it) } }
            else File(dir, "audio_cache").deleteRecursively()
        }

        /** Bytes que ocupa ahora la caché de audio. */
        fun usoCache(dir: File): Long =
            cache?.cacheSpace ?: File(dir, "audio_cache").walkTopDown().filter { it.isFile }.sumOf { it.length() }

        private const val META_FORMATO = "aura_formato"

        const val CMD_SLEEP_START  = "SLEEP_START"
        const val CMD_SLEEP_CANCEL = "SLEEP_CANCEL"
        // "Siguiente" desde la app en la última canción: el MediaController tiene
        // una lista de comandos desactualizada y descartaba seekToNext() en
        // silencio; con un comando propio llega siempre al servicio.
        const val CMD_SIGUIENTE    = "SIGUIENTE"
        const val ARG_SLEEP_MIN    = "SLEEP_MIN"
        private const val CANAL_TEMPORIZADOR = "temporizador_sueno"
        private const val NOTIF_TEMPORIZADOR_ID = 7301
    }

    override fun onCreate() {
        super.onCreate()

        // IMPORTANTE: Sin headers custom. Las URLs de YouTube CDN (googlevideo.com)
        // y de NewPipe se autentican mediante parámetros en la URL misma.
        // Agregar User-Agent custom o headers extra ROMPE la reproducción.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)

        // ── RESOLUCIÓN AL VUELO (Bug B / Bug D) ─────────────────────────────
        // Las pistas de YouTube se encolan con uri "ytstream:VIDEOID". Cuando
        // ExoPlayer va a reproducir una (incluida la SIGUIENTE de la cola, con la
        // app cerrada), este resolver obtiene la URL real de stream. Como vive en
        // el servicio, el auto-avance de la cola ya NO depende de la UI (Bug B), y
        // al resolverse en cada apertura una URL caducada se renueva sola (Bug D).
        val resolver = ResolvingDataSource.Resolver { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == ESQUEMA_YT) {
                val videoId = uri.schemeSpecificPart
                val ajustes = runBlocking { repositorioPreferencias.ajustes.first() }
                val (calidad, codec) = formatoPara(videoId, ajustes)
                val info = runBlocking { innertube.getAudioUrl(videoId, calidad, codec) }
                    ?: throw IOException("No se pudo resolver el audio de $videoId")
                recordarFormato(videoId, calidad, codec)
                Log.d(TAG, "Resuelto $videoId → ${info.mimeType} @ ${info.bitrate}kbps")
                dataSpec.withUri(Uri.parse(info.url))
            } else dataSpec   // local (file://, content://) o ya resuelto: pasa igual
        }
        // HTTP en trozos de 2 MB: YouTube limita las peticiones abiertas a ~la
        // velocidad de reproducción (ver TrozosDataSource).
        val resolvingFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, TrozosDataSource.Factory(httpDataSourceFactory)),
            resolver
        )

        // La caché indexa por la uri "ytstream:VIDEOID" (estable), no por la URL
        // de googlevideo (volátil), así lo ya descargado se reutiliza igual.
        val cacheFactory = CacheDataSource.Factory()
            .setCache(getOrCreateCache(cacheDir, applicationContext,
                runBlocking { repositorioPreferencias.ajustes.first().cacheMaximoMb }))
            // Un 403 descarta la URL guardada para que el reintento pida otra.
            .setUpstreamDataSourceFactory(UrlRechazadaDataSource.Factory(resolvingFactory) { videoId ->
                Log.w(TAG, "URL rechazada para $videoId: se pedirá una nueva")
                innertube.olvidarUrl(videoId)
            })
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Fuera de la caché lo que ya está en el teléfono (ver LimpiezaCache).
        scope.launch(Dispatchers.IO) {
            val abierta = cache ?: return@launch
            val descargadas = songDao.getDownloaded().first().flatMap { listOf(it.id, it.fuente) }.toSet()
            val liberados = LimpiezaCache.quitarDuplicados(abierta, descargadas)
            if (liberados > 0) Log.i(TAG, "Caché de audio: ${liberados / 1_048_576} MB duplicados liberados")
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_000, 5_000)
            .build()

        player = ExoPlayer.Builder(this)
            // Planificación dinámica: el bucle de ExoPlayer solo despierta la CPU cuando
            // el audio necesita datos, en vez de cada 10 ms. Ahorra batería aunque el
            // teléfono no pueda usar offload (MediaTek con el firmware roto, Dolby activo).
            .experimentalSetDynamicSchedulingEnabled(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(
                CacheSoloStreaming.Factory(cacheFactory, DefaultDataSource.Factory(this))))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onPlayerError(e: AnalyticsListener.EventTime, error: PlaybackException) {
                Log.e(TAG, "ERROR ${error.errorCode}: ${error.message.sinUrls()}")
                error.cause?.let { Log.e(TAG, "  → ${it.javaClass.simpleName}: ${it.message.sinUrls()}") }
            }
            // Errores de carga que ExoPlayer reintenta en silencio (no llegan a
            // onPlayerError hasta que se rinde). Sin esto, un atasco en "cargando" no
            // deja rastro en el registro.
            override fun onLoadError(
                e: AnalyticsListener.EventTime,
                info: androidx.media3.exoplayer.source.LoadEventInfo,
                data: androidx.media3.exoplayer.source.MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                Log.w(TAG, "Error de carga (${info.dataSpec.uri.toString().sinUrls()}, pos ${info.dataSpec.position}): " +
                    "${error.javaClass.simpleName}: ${error.message.sinUrls()?.take(160)}")
            }
            override fun onPlaybackStateChanged(e: AnalyticsListener.EventTime, state: Int) {
                Log.d(TAG, "State: ${when(state){1->"IDLE";2->"BUFFERING";3->"READY";4->"ENDED";else->"?"}}")
            }
            override fun onIsPlayingChanged(e: AnalyticsListener.EventTime, p: Boolean) {
                Log.d(TAG, "isPlaying=$p")
            }
            override fun onAudioTrackInitialized(e: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) {
                pistaEnOffload = config.offload
                Log.d(TAG, "AudioTrack: offload=${config.offload}, encoding=${config.encoding}, ${config.sampleRate} Hz")
            }
            override fun onAudioSinkError(e: AnalyticsListener.EventTime, error: Exception) {
                if (pistaEnOffload) offloadFallo(error)
            }
        })

        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // La sesión (notificación, auriculares, pantalla de bloqueo y la propia app)
        // ve este envoltorio: "siguiente" está siempre disponible y, si no hay
        // siguiente en la cola, pide canciones a la reproducción automática.
        // Antes "siguiente" en la última canción no hacía nada fuera de la app, y
        // dentro añadía canciones pero no avanzaba.
        val jugadorSesion = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .build()
            override fun isCommandAvailable(command: Int): Boolean =
                command == Player.COMMAND_SEEK_TO_NEXT ||
                    command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    super.isCommandAvailable(command)
            override fun seekToNext() {
                if (player.hasNextMediaItem()) super.seekToNext() else extenderCola()
            }
            // La cola recordada se restaura sin preparar (no carga audio ni da error
            // sin red al abrir la app): al pulsar reproducir se prepara aquí.
            override fun play() {
                if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
                super.play()
            }
            override fun seekToNextMediaItem() {
                if (player.hasNextMediaItem()) super.seekToNextMediaItem() else extenderCola()
            }
        }

        mediaSession = MediaSession.Builder(this, jugadorSesion)
            .setSessionActivity(intent)
            // Bug C: la notificación/pantalla de bloqueo no sabe bajar URLs remotas
            // por su cuenta. Este cargador descarga la portada y entrega el Bitmap
            // ya decodificado para que el sistema lo muestre.
            .setBitmapLoader(CargadorPortada())
            .setCallback(Callback())
            .build()

        // Listener del SERVICIO (no de la UI): maneja el ciclo de vida y el
        // auto-reintento. Vive mientras suene la música, con la app abierta o no.
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) reintentos = 0
                // Se acabó la cola: sigue con la radio (si no es música del teléfono).
                if (state == Player.STATE_ENDED && !player.hasNextMediaItem()) extenderCola()
                // Solo se detiene el servicio si NO queda nada que reproducir; ante
                // un error la cola sigue cargada, así el reintento puede actuar.
                if (state == Player.STATE_IDLE && !player.isPlaying &&
                    player.mediaItemCount == 0) stopSelf()
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                normalizador.setSesion(audioSessionId)
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                reintentos = 0
                anclar(player.currentPosition)
                guardarPosicion()
                item?.let { reproduccionAutomatica.registrar(it) }
                // Empieza la última canción de la cola: se pide ya la siguiente tanda
                // de la radio (sin saltar). Así, al terminar o al pulsar "siguiente",
                // la próxima ya está en la cola y precargada; antes ese cambio tardaba
                // ~5 s (pedir la radio + resolver el audio en ese momento).
                if (player.currentMediaItemIndex == player.mediaItemCount - 1) extenderCola(saltar = false)
                precargarSiguiente()
            }
            // También al cambiar la cola (añadir, reordenar, "reproducir a continuación").
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                precargarSiguiente()
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) guardarCola()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                anclar()
                guardarPosicion()
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
            ) {
                // El salto de la recuperación de un offload roto (INTERNAL) no ancla:
                // la posición buena es la del reloj, no la que deja esa recuperación.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) { anclar(player.currentPosition); guardarPosicion() }
            }
            override fun onPlayerError(error: PlaybackException) {
                // Bug D: ante un error de red/stream (típico al caducar la URL en
                // segundo plano) re-prepara hasta 3 veces para pedir una URL nueva.
                if (reintentos < 3 && esErrorDeRed(error)) {
                    reintentos++
                    // La URL guardada (precarga) pudo ser la que falló: que se pida otra.
                    videoIdDe(player.currentMediaItem)?.let { innertube.olvidarUrl(it) }
                    Log.w(TAG, "Error ${error.errorCode}; reintento $reintentos (re-preparando)")
                    player.prepare()
                } else {
                    Log.e(TAG, "Error definitivo tras $reintentos reintentos: ${error.errorCode}")
                }
            }
        })

        // Mantiene en caché el ajuste de reproducción en segundo plano (Fase 2)
        // para consultarlo sin bloquear en onTaskRemoved.
        normalizador.setSesion(player.audioSessionId)
        restaurarCola()
        colaGuardada.registerOnSharedPreferenceChangeListener(colaImportada)
        // Si el sistema mata la app (o se fuerza el cierre) no hay onDestroy:
        // la posición se guarda cada 10 s mientras suena.
        scope.launch {
            while (true) {
                delay(10_000)
                if (player.isPlaying) guardarPosicion()
            }
        }
        scope.launch {
            repositorioPreferencias.ajustes.collect {
                permitirSegundoPlano = it.reproduccionSegundoPlano
                autoplayActivo = it.reproduccionAutomatica
                normalizador.setActivo(it.normalizarVolumen)
                aplicarAhorroAudio(it.ahorroAudio)
            }
        }
    }

    // ── Recordar la cola ────────────────────────────────────────────────────
    // Antes, si el sistema cerraba la app, al volver no había canción ni cola.
    // Se guarda la cola (al cambiar) y la posición (al cambiar de pista, pausar,
    // saltar con la barra y cada 10 s) y se restaura en pausa al crear el servicio.
    private val colaGuardada by lazy { getSharedPreferences(PREFS_COLA, MODE_PRIVATE) }

    private fun guardarCola() {
        val n = player.mediaItemCount
        if (n == 0) return
        val items = org.json.JSONArray()
        for (i in 0 until n) {
            val mi = player.getMediaItemAt(i)
            items.put(org.json.JSONObject()
                .put("id", mi.mediaId)
                .put("uri", mi.localConfiguration?.uri?.toString().orEmpty())
                .put("titulo", mi.mediaMetadata.title?.toString().orEmpty())
                .put("artista", mi.mediaMetadata.artist?.toString().orEmpty())
                .put("portada", mi.mediaMetadata.artworkUri?.toString().orEmpty())
                // La duración, para que la barra la muestre aunque la cola se abra en pausa.
                .put("duracion", mi.mediaMetadata.durationMs ?: 0L))
        }
        colaGuardada.edit().putString("items", items.toString()).apply()
        guardarPosicion()
    }

    private fun guardarPosicion() {
        if (player.mediaItemCount == 0) return
        colaGuardada.edit()
            .putInt("indice", player.currentMediaItemIndex)
            // Con offload, la de ExoPlayer va ~1 min adelantada (ver posicionReal).
            .putLong("posicion", (if (pistaEnOffload) posicionReal() else player.currentPosition).coerceAtLeast(0L))
            .apply()
    }

    // Un respaldo importado escribe la cola solo si no había ninguna; con el
    // servicio ya en marcha y sin nada cargado, se carga en ese momento.
    // (Campo, no lambda suelta: SharedPreferences guarda el listener con referencia débil.)
    private val colaImportada = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, clave ->
        if (clave == "items") restaurarCola()
    }

    private fun restaurarCola() {
        if (player.mediaItemCount > 0) return
        val json = colaGuardada.getString("items", null) ?: return
        val items = runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val meta = MediaMetadata.Builder()
                    .setTitle(o.optString("titulo")).setArtist(o.optString("artista"))
                o.optString("portada").takeIf { it.isNotBlank() }?.let { meta.setArtworkUri(Uri.parse(it)) }
                o.optLong("duracion").takeIf { it > 0 }?.let { meta.setDurationMs(it) }
                MediaItem.Builder().setMediaId(o.getString("id")).setUri(o.getString("uri"))
                    .setMediaMetadata(meta.build()).build()
            }.filter { it.localConfiguration != null }
        }.getOrElse { Log.w(TAG, "Cola guardada ilegible: ${it.message.sinUrls()}"); return }
        if (items.isEmpty()) return
        val indice = colaGuardada.getInt("indice", 0).coerceIn(0, items.lastIndex)
        player.setMediaItems(items, indice, colaGuardada.getLong("posicion", 0L))
        Log.d(TAG, "Cola restaurada: ${items.size} canciones, en la ${indice + 1}")
    }

    // Con "Ahorro de batería" se pide AAC: el chip de audio de muchos teléfonos no
    // admite Opus (el del Honor de pruebas: MP3/AAC/FLAC/Vorbis). Lo ya guardado en
    // caché sigue sonando en su formato (ver formatoPara). Con el offload roto se
    // sigue pidiendo AAC, que la CPU también decodifica sin problema.
    private fun codecDe(ajustes: AppSettings): CodecAudio =
        if (ajustes.ahorroAudio) CodecAudio.AAC else ajustes.audioQuality.codec

    // ── Offload roto ────────────────────────────────────────────────────────
    // En MediaTek (Poco X8 Pro tras HyperOS OS3.0.307, Galaxy A15) el chip acepta el
    // AAC unos 10 s y luego lo rechaza ("Invalid format"); ExoPlayer se recupera
    // retomando ~1 min más adelante (lo ya entregado al chip). Al primer fallo se
    // vuelve a la posición real y se deja de usar offload con ESTE firmware: si una
    // actualización del sistema lo arregla, cambia el fingerprint y se reintenta.
    private var ahorroPedido = false
    private var pistaEnOffload = false

    // Posición real por reloj. En offload la posición de ExoPlayer avanza al ritmo
    // al que el chip ACEPTA audio (~1 min en 10 s), no al que suena: solo es fiable
    // tras un salto o un cambio de canción. Al pausar o cargar se sigue el reloj.
    private var anclaPosicion = 0L
    private var anclaReloj = 0L
    private var anclaCorriendo = false

    private fun anclar(posicion: Long = if (pistaEnOffload) posicionReal() else player.currentPosition) {
        anclaPosicion = posicion
        anclaReloj = android.os.SystemClock.elapsedRealtime()
        anclaCorriendo = player.isPlaying
    }

    private fun posicionReal(): Long =
        if (!anclaCorriendo) anclaPosicion
        else anclaPosicion + ((android.os.SystemClock.elapsedRealtime() - anclaReloj) *
            player.playbackParameters.speed).toLong()

    private fun offloadFallo(error: Exception) {
        if (OffloadRoto.estaRoto(this)) return
        val posicion = posicionReal()
        Log.w(TAG, "Offload roto en este firmware (${Build.FINGERPRINT}): se desactiva " +
            "y se vuelve a ${posicion} ms. ${error.message}")
        OffloadRoto.marcar(this)
        scope.launch {
            aplicarAhorroAudio(ahorroPedido)
            player.seekTo(posicion)
        }
    }

    // Ajuste "Ahorro de batería" (audio offload): el chip de audio del teléfono
    // decodifica en vez de la CPU (~18 % de un núcleo en segundo plano medido en un
    // Honor sin él). Si el formato o el teléfono no lo admiten, o hay un efecto en
    // la sesión (Normalizar volumen), ExoPlayer sigue como siempre.
    private fun aplicarAhorroAudio(pedido: Boolean) {
        ahorroPedido = pedido
        val activo = pedido && !OffloadRoto.estaRoto(this)
        val modo = if (activo) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        if (player.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode == modo) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder().setAudioOffloadMode(modo).build()
            ).build()
    }

    // Precarga (siempre activa): resuelve ya la URL de la próxima canción de la cola
    // (~1,5 s con NewPipe) y la deja guardada en InnertubeClient, así saltar a ella
    // es instantáneo. El audio en sí lo adelanta ExoPlayer al acercarse el final.
    private var precargaJob: Job? = null

    private fun precargarSiguiente() {
        val siguiente = player.nextMediaItemIndex
        if (siguiente == C.INDEX_UNSET) return
        val videoId = videoIdDe(player.getMediaItemAt(siguiente)) ?: return
        precargaJob?.cancel()
        precargaJob = scope.launch(Dispatchers.IO) {
            val (calidad, codec) = formatoPara(videoId, repositorioPreferencias.ajustes.first())
            innertube.getAudioUrl(videoId, calidad, codec)
        }
    }

    // ── Un formato por canción en la caché ──────────────────────────────────
    // La caché indexa por "ytstream:ID", sin el formato. Si una canción quedó a
    // medias en un formato (p. ej. Opus) y luego se cambia la calidad o el Ahorro
    // de batería, el resto llegaría en otro (AAC) y se mezclarían bytes de dos
    // archivos: ERROR 3003 o saltos. Por eso se recuerda el formato en los
    // metadatos de la caché y, mientras quede algo guardado, se pide ese mismo.
    private fun formatoPara(videoId: String, ajustes: AppSettings): Pair<AudioQuality, CodecAudio> {
        val clave = uriDeVideo(videoId)
        val guardado = cache?.takeIf { it.getCachedSpans(clave).isNotEmpty() }
            ?.getContentMetadata(clave)?.get(META_FORMATO, null as String?)
            ?.split('|')?.takeIf { it.size == 2 }
            ?.let { (q, c) -> runCatching { AudioQuality.valueOf(q) to CodecAudio.valueOf(c) }.getOrNull() }
        return guardado ?: (ajustes.audioQuality to codecDe(ajustes))
    }

    private fun recordarFormato(videoId: String, calidad: AudioQuality, codec: CodecAudio) {
        runCatching {
            cache?.applyContentMetadataMutations(uriDeVideo(videoId),
                ContentMetadataMutations().set(META_FORMATO, "${calidad.name}|${codec.name}"))
        }.onFailure { Log.w(TAG, "No se pudo guardar el formato de $videoId: ${it.message.sinUrls()}") }
    }

    // Añade canciones de la radio al final de la cola y salta a la primera nueva.
    // Se salta por ÍNDICE (seekToDefaultPosition): "siguiente" no sirve justo
    // después de añadir, porque aún no consta que haya siguiente.
    // Interruptor "Reproducción automática" (cola y Ajustes): apagado, la cola se acaba.
    @Volatile private var autoplayActivo = true

    private fun extenderCola(saltar: Boolean = true) {
        // Con "Repetir toda la cola" la cola vuelve a empezar: no se le añade radio.
        if (extendiendo || !autoplayActivo || player.repeatMode == Player.REPEAT_MODE_ALL) return
        val actual = player.currentMediaItem ?: return
        extendiendo = true
        scope.launch {
            try {
                val cola = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                val nuevos = reproduccionAutomatica.siguientes(actual, cola)
                if (nuevos.isEmpty()) return@launch
                val primero = player.mediaItemCount
                player.addMediaItems(nuevos)
                if (saltar || player.playbackState == Player.STATE_ENDED) {
                    player.seekToDefaultPosition(primero)
                    player.prepare()
                    player.play()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reproducción automática: ${e.message.sinUrls()}")
            } finally {
                extendiendo = false
            }
        }
    }

    private fun videoIdDe(item: MediaItem?): String? {
        val uri = item?.localConfiguration?.uri ?: return null
        return if (uri.scheme == ESQUEMA_YT) uri.schemeSpecificPart else null
    }

    private fun esErrorDeRed(e: PlaybackException): Boolean = e.errorCode in intArrayOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT
    )

    override fun onGetSession(info: MediaSession.ControllerInfo) = mediaSession

    // Si el segundo plano está desactivado, al cerrar la app se detiene aunque
    // estuviera sonando. Con él activado (por defecto), sigue reproduciendo.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!permitirSegundoPlano || !player.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        colaGuardada.unregisterOnSharedPreferenceChangeListener(colaImportada)
        mediaSession?.run { player.release(); release(); mediaSession = null }
        normalizador.liberar()
        sleepJob?.cancel(); scope.cancel()
        super.onDestroy()
    }

    fun startSleepTimer(min: Int) {
        sleepJob?.cancel()
        sleepJob = scope.launch {
            delay(min * 60_000L)
            // Función AA: fade-out suave de ~3s antes de pausar.
            val volInicial = player.volume
            val pasos = 30
            for (i in pasos - 1 downTo 0) {
                player.volume = volInicial * (i / pasos.toFloat())
                delay(100)
            }
            player.pause()
            player.volume = volInicial   // restaura para la próxima reproducción
            notificarPausaPorTemporizador()
        }
    }
    fun cancelSleepTimer() { sleepJob?.cancel(); sleepJob = null }

    private fun notificarPausaPorTemporizador() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CANAL_TEMPORIZADOR, getString(com.auramusic.app.R.string.menu_temporizador),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notif = NotificationCompat.Builder(this, CANAL_TEMPORIZADOR)
            .setSmallIcon(com.auramusic.app.R.mipmap.ic_launcher)
            .setContentTitle(getString(com.auramusic.app.R.string.sleep_notif_titulo))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_TEMPORIZADOR_ID, notif)
    }

    // ── Cargador de portadas para la notificación/lockscreen (Bug C) ────────
    // El sistema de notificaciones de Media3 pide un Bitmap; aquí lo descargamos
    // (subiendo la resolución de la miniatura) y lo entregamos ya decodificado.
    inner class CargadorPortada : BitmapLoader {
        private val http = OkHttpClient()
        private val ejecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
        @Volatile private var ultimaUrl: String? = null
        @Volatile private var ultimoBitmap: Bitmap? = null

        override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            ejecutor.submit(Callable {
                BitmapFactory.decodeByteArray(data, 0, data.size)
                    ?: throw IOException("Portada ilegible")
            })

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
            ejecutor.submit(Callable { descargarPortada(uri) })

        private fun descargarPortada(uri: Uri): Bitmap {
            val esquema = uri.scheme ?: ""
            if (esquema != "http" && esquema != "https") throw IOException("Portada no remota: $uri")
            val url = mejorarPortada(uri.toString())
            // Caché de 1 entrada: evita re-descargar la misma portada.
            ultimoBitmap?.let { if (url == ultimaUrl) return it }
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            http.newCall(req).execute().use { r ->
                val bytes = r.body?.bytes() ?: throw IOException("Portada vacía")
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("Portada ilegible")
                ultimaUrl = url; ultimoBitmap = bmp
                return bmp
            }
        }
    }

    // Sube la resolución de la miniatura (misma lógica que mejorarCaratula de la
    // UI, replicada aquí para no acoplar el servicio a la capa de pantallas).
    private fun mejorarPortada(url: String): String = when {
        url.contains("googleusercontent.com") || url.contains("ggpht.com") ->
            url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
        url.contains("ytimg.com") ->
            url.replace(Regex("/(default|mqdefault|sddefault)\\.jpg"), "/hqdefault.jpg")
        else -> url
    }

    inner class Callback : MediaSession.Callback {
        override fun onConnect(s: MediaSession, c: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // El servicio está exportado (controles del sistema, relojes, coche): las
            // apps externas solo reciben los controles normales de reproducción.
            if (c.packageName != packageName) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(s).build()
            }
            // La propia app: además, los comandos internos (temporizador AA y
            // "siguiente" con reproducción automática).
            val comandos = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(androidx.media3.session.SessionCommand(CMD_SLEEP_START, Bundle.EMPTY))
                .add(androidx.media3.session.SessionCommand(CMD_SLEEP_CANCEL, Bundle.EMPTY))
                .add(androidx.media3.session.SessionCommand(CMD_SIGUIENTE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                comandos, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }
        override fun onCustomCommand(
            s: MediaSession, c: MediaSession.ControllerInfo,
            cmd: androidx.media3.session.SessionCommand, args: Bundle
        ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
            // Defensa extra: los comandos internos solo se aceptan de la propia app.
            if (c.packageName != packageName) {
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED)
                )
            }
            when (cmd.customAction) {
                CMD_SLEEP_START  -> startSleepTimer(args.getInt(ARG_SLEEP_MIN, 30))
                CMD_SLEEP_CANCEL -> cancelSleepTimer()
                CMD_SIGUIENTE    -> if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.play() } else extenderCola()
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(
                androidx.media3.session.SessionResult(
                    androidx.media3.session.SessionResult.RESULT_SUCCESS
                )
            )
        }
    }
}
