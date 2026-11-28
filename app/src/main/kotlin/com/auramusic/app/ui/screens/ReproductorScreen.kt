@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.auramusic.app.ui.screens

import com.auramusic.app.ui.components.conReintento
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import com.auramusic.app.domain.model.OrigenCancion
import com.auramusic.app.ui.components.LetraPrevia
import com.auramusic.app.ui.components.VisorLetrasCompleto
import com.auramusic.app.ui.components.QualityBadge
import androidx.compose.ui.res.stringResource
import com.auramusic.app.R
import com.auramusic.app.ui.navigation.Screen
import com.auramusic.app.ui.theme.rememberTintePortada
import com.auramusic.app.ui.theme.cambioDeCancion
import com.auramusic.app.ui.theme.sinAnimacion
import com.auramusic.app.ui.theme.Movimiento
import com.auramusic.app.ui.theme.FormaPortada
import com.auramusic.app.ui.components.portadaCompartidaModelo
import com.auramusic.app.ui.theme.LocalAlcanceReproductor
import com.auramusic.app.ui.theme.portadaCompartida
import com.auramusic.app.ui.theme.escalaEnLayout
import com.auramusic.app.ui.components.CorazonMeGusta
import com.auramusic.app.ui.components.IconoConEstado
import com.auramusic.app.ui.components.IconoPlayPausa
import com.auramusic.app.ui.components.LineaLetraActual
import androidx.compose.ui.graphics.lerp
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.media3.common.Player
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
// ─── NOW PLAYING (con visor de letras y badge de calidad) ──────────────────
//
// Partido en piezas que leen SOLO su estado (cabecera y menú, portada, info,
// progreso, controles, asa de la cola). La raíz solo lee lo que cambia su
// estructura: el id y la portada de la canción, la letra, el artista y los ajustes.
// Antes leía 19 estados y cualquier cambio (pausar, cada % de una descarga, la
// cola que crece) rehacía el reproductor entero, también oculto con el Inicio
// delante: unas 6 veces por cambio de canción.
@Composable
fun NowPlayingScreen(navController: NavController, playerViewModel: PlayerViewModel, visible: Boolean = true) {
    val playlistVm: PlaylistViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val contexto = LocalContext.current

    // La canción se guarda como State sin leerla aquí: la raíz depende solo de su id
    // y su portada (dar me gusta cambia la canción y no debe rehacer el reproductor).
    val cancion = playerViewModel.currentSong.collectAsStateWithLifecycle()
    val idCancion by remember { derivedStateOf { cancion.value?.id } }
    val portadaUrl by remember { derivedStateOf { mejorarCaratula(cancion.value?.thumbnailUrl) } }
    // progressMs cambia hasta 10 veces por segundo: aquí solo se guarda el State,
    // sin leerlo, o recompondría toda la pantalla (98 % de fotogramas lentos). Lo
    // leen BarraProgreso y las letras (solo al cambiar de línea). Oculto no se sigue
    // (ver PosicionVisible).
    val posicionReal = playerViewModel.progressMs.collectAsStateWithLifecycle()
    val visibleActual = rememberUpdatedState(visible)
    val posicion: State<Long> = remember(posicionReal) { PosicionVisible(posicionReal, visibleActual) }
    val lyrics      by playerViewModel.lyrics.collectAsStateWithLifecycle()
    val settings    by settingsVm.settings.collectAsStateWithLifecycle()
    val artista     by playerViewModel.artistaActual.collectAsStateWithLifecycle()

    var showMenu             by remember { mutableStateOf(false) }
    var showSleepTimer       by remember { mutableStateOf(false) }
    var showAddToPlaylist    by remember { mutableStateOf(false) }
    var showLyricsFullscreen by remember { mutableStateOf(false) }
    var showCola             by remember { mutableStateOf(false) }
    // El reproductor se queda construido al cerrarlo (ver CapaReproductor): lo que se
    // abre en su propia ventana (visor, cola, diálogos, menú) se cierra con él, o se
    // quedaría encima del Inicio.
    LaunchedEffect(visible) {
        if (!visible) {
            showMenu = false; showSleepTimer = false; showAddToPlaylist = false
            showLyricsFullscreen = false; showCola = false
        }
    }
    // Sentido del último cambio de canción (1 = siguiente, -1 = anterior) para que
    // portada y título entren por el lado correcto. El índice cambia antes que la
    // canción pintada, así que ya está listo cuando empieza la animación. Se sigue en
    // una corrutina, sin leerlo al componer, para que la raíz no se rehaga con él.
    val direccionCambio = remember { mutableIntStateOf(1) }
    LaunchedEffect(playerViewModel) {
        var previo = -1
        playerViewModel.indiceActual.collect { i ->
            if (previo >= 0 && i != previo) direccionCambio.intValue = if (i < previo) -1 else 1
            previo = i
        }
    }
    val direccion = { direccionCambio.intValue }
    if (showCola) ColaSheet(playerViewModel, settingsVm) { showCola = false }

    if (showSleepTimer) {
        val temporizadorHasta by playerViewModel.temporizadorActivoHasta.collectAsStateWithLifecycle()
        SleepTimerDialog(
            temporizadorHasta = temporizadorHasta,
            onActivar = { min -> playerViewModel.iniciarTemporizador(min); showSleepTimer = false },
            onCancelar = { playerViewModel.cancelarTemporizador(); showSleepTimer = false },
            onDismiss = { showSleepTimer = false }
        )
    }
    if (showAddToPlaylist && idCancion != null) {
        val playlists = playlistVm.playlists.collectAsStateWithLifecycle().value.orEmpty()
        AddToPlaylistDialog(
            playlists = playlists,
            onSelect = { id ->
                val nombre = playlists.firstOrNull { it.id == id }?.name.orEmpty()
                cancion.value?.let { s ->
                    playlistVm.addSongToPlaylist(id, s.id) { anadida ->
                        Toast.makeText(contexto, contexto.getString(
                            if (anadida) R.string.playlist_anadida else R.string.playlist_ya_estaba, nombre), Toast.LENGTH_SHORT).show()
                    }
                }
                showAddToPlaylist = false
            },
            onCrear = { nombre ->
                cancion.value?.let { s -> playlistVm.crearYAgregar(nombre, s.id) }
                Toast.makeText(contexto, contexto.getString(R.string.playlist_anadida, nombre), Toast.LENGTH_SHORT).show()
                showAddToPlaylist = false
            },
            onDismiss = { showAddToPlaylist = false }
        )
    }

    // Fondo teñido con el color de la portada (idea de SimpMusic; ajuste "Fondo con
    // el color de la portada"). Con tema claro el tinte es el fondo del tema.
    val fondo = MaterialTheme.colorScheme.background
    // El tinte se anima: se lee solo al DIBUJAR (drawBehind y lambdas), así su
    // animación repinta pero no recompone el reproductor entero en cada fotograma
    // (en gama baja eso hacía que abrirlo fuera a ~26 fps).
    val tinte = rememberTintePortada(portadaUrl, settings.blurPlayerBg, animar = visible)
    val contenedor = MaterialTheme.colorScheme.surfaceContainer
    val tarjeta: () -> Color = { tinte.value.let { t -> if (t != fondo) lerp(t, Color.White, 0.08f) else contenedor } }
    // La tarjeta del artista se pide solo con el reproductor a la vista (oculto, cada
    // cambio de canción sería una petición que nadie ve).
    LaunchedEffect(idCancion, visible) { if (visible) playerViewModel.cargarArtistaActual() }
    val listaReproductor = rememberLazyListState()
    // Al cerrarlo vuelve arriba, para que al abrirlo de nuevo se vea la portada (antes
    // se construía de cero y siempre empezaba ahí).
    LaunchedEffect(visible) { if (!visible) listaReproductor.scrollToItem(0) }
    // Al cambiar de canción con la lista bajada, sube suave hasta la portada. Las
    // tarjetas Letra y Artista se van mientras cargan las nuevas y la lista, más
    // corta que la pantalla, saltaba arriba de golpe: mientras sube se reserva su
    // altura con un hueco al final (desde el MISMO fotograma del cambio).
    var idAnterior by remember { mutableStateOf(idCancion) }
    val subiendo = idAnterior != null && idCancion != idAnterior
    var alturaLetra by remember { mutableIntStateOf(0) }
    var alturaArtista by remember { mutableIntStateOf(0) }
    LaunchedEffect(idCancion) {
        if (subiendo && (listaReproductor.firstVisibleItemIndex > 0 || listaReproductor.firstVisibleItemScrollOffset > 0))
            listaReproductor.animateScrollToItem(0)
        idAnterior = idCancion
    }

    Box(Modifier.fillMaxSize().drawBehind {
        val t = tinte.value
        drawRect(Brush.verticalGradient(0f to t, 0.55f to lerp(t, fondo, 0.6f), 1f to fondo))
    }) {
        // El error se muestra como un diálogo GLOBAL (ver AuraMusicApp en
        // MainActivity), que se pinta en su propia ventana, siempre encima.

        // Posición de la letra corregida con el retraso de Ajustes (auriculares BT).
        val retrasoLetra = settings.retrasoLetrasMs
        val posLetra = { posicion.value - retrasoLetra }
        val hayLetra = lyrics != null && lyrics!!.lines.isNotEmpty()
        // Oculto no cuenta: si no, la posición se leería cada 0,1 s con el Inicio delante.
        val letraVisible = visible && hayLetra && (settings.showLyrics || showLyricsFullscreen)
        DisposableEffect(letraVisible) {
            playerViewModel.setLetrasVisibles(letraVisible)
            onDispose { playerViewModel.setLetrasVisibles(false) }
        }
        // Si la letra desaparece (otra canción sin letra) el visor se cierra; así
        // no se queda "abierto" esperando y aparece solo al llegar otra letra.
        LaunchedEffect(hayLetra) { if (!hayLetra) showLyricsFullscreen = false }
        // El visor de letras se abre subiendo un poco y fundiéndose sobre el
        // reproductor, y se cierra al revés (antes se cambiaba de golpe). La lista
        // guarda su posición fuera, para volver a donde estaba.
        AnimatedContent(
            targetState = showLyricsFullscreen && lyrics != null,
            transitionSpec = {
                if (targetState) (fadeIn(Movimiento.fundido()) + slideInVertically(Movimiento.entrada()) { it / 12 })
                    .togetherWith(fadeOut(Movimiento.salida(Movimiento.MEDIA)))
                else fadeIn(Movimiento.fundido()).togetherWith(
                    fadeOut(Movimiento.salida()) + slideOutVertically(Movimiento.salida()) { it / 12 })
            },
            label = "visorLetras"
        ) { visor ->
        if (visor) {
            val letraVisor = lyrics ?: return@AnimatedContent
            val s = cancion.value
            val reproduciendo by playerViewModel.isPlaying.collectAsStateWithLifecycle()
            val duracion by rememberDuracion(playerViewModel)
            VisorLetrasCompleto(
                letra = letraVisor,
                titulo = s?.title ?: "", artista = s?.artist ?: "",
                portada = portadaUrl,
                posMs = posLetra, duracionMs = duracion, reproduciendo = reproduciendo,
                tamanoSp = settings.tamanoFuenteLetras,
                onSeek = { playerViewModel.seekTo(it + retrasoLetra) },
                onPlayPause = { playerViewModel.togglePlayPause() },
                onAnterior = { playerViewModel.skipPrevious() },
                onSiguiente = { playerViewModel.skipNext() },
                onCerrar = { showLyricsFullscreen = false }
            )
        } else {
            Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = listaReproductor, contentPadding = PaddingValues(bottom = 32.dp)) {
                // Cabecera: "REPRODUCIENDO AHORA" y de dónde viene la cola
                item(key = "cabecera") {
                    CabeceraReproductor(
                        vm = playerViewModel,
                        menuAbierto = showMenu, onMenu = { showMenu = it },
                        // Con la tarjeta Letra a la vista esto la repetiría: solo aparece
                        // si "Mostrar letras" está apagado y la canción tiene letra.
                        mostrarVerLetra = hayLetra && !settings.showLyrics,
                        onAnadirPlaylist = { showAddToPlaylist = true },
                        onTemporizador = { showSleepTimer = true },
                        onVerLetra = { showLyricsFullscreen = true }
                    )
                }
                item(key = "portada") { PortadaReproductor(playerViewModel, direccion, animar = visible) }
                // La línea de letra que suena, bajo la portada (idea de SimpMusic)
                // Con las letras activadas el hueco se reserva siempre: al cambiar de
                // canción la letra tarda en llegar y todo lo de abajo daba un salto.
                item(key = "lineaLetra") {
                    // Sin semántica, como la barra de progreso (ver BarraProgreso): la letra
                    // entera sigue accesible en el visor a pantalla completa.
                    if (settings.showLyrics) Box(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp).clearAndSetSemantics {}) {
                        // La letra se lee UNA vez aquí: la fila se arma después que la raíz y,
                        // si cambió la canción entre medias, `lyrics` ya es null aunque
                        // `hayLetra` siga en true (con `lyrics!!` la app se cerraba).
                        val letra = lyrics
                        if (letra != null && letra.lines.isNotEmpty() && letra.isSynced) LineaLetraActual(letra, posLetra)
                        else Text(" ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    else Spacer(Modifier.height(18.dp))
                }
                item(key = "info") {
                    InfoCancion(playerViewModel, navController, direccion, animar = visible)
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "progreso") {
                    ProgresoReproductor(playerViewModel, posicion)
                    Spacer(Modifier.height(4.dp))
                }
                item(key = "controles") {
                    ControlesReproductor(playerViewModel, visible)
                    Spacer(Modifier.height(18.dp))
                }
                // Asa "A continuación" (como en YT Music): abre la hoja de la cola.
                item(key = "asaCola") {
                    AsaCola(playerViewModel, tarjeta) { showCola = true }
                    Spacer(Modifier.height(16.dp))
                }
                // Tarjeta Letra — solo si el ajuste está activado
                // Letra y Artista llegan unos segundos después que la canción: entran
                // con un fundido (animateItem) en vez de aparecer de golpe.
                if (hayLetra && settings.showLyrics) {
                    item(key = "letra") {
                        Column(Modifier.animateItem().onSizeChanged { if (!subiendo) alturaLetra = it.height }) {
                        TarjetaReproductor(tarjeta, onClick = { showLyricsFullscreen = true }) {
                            Column {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.player_letras), fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { showLyricsFullscreen = true }) {
                                        Text(stringResource(R.string.player_letra_completa), color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                                // Leída una vez, sin `!!`: ver la fila "lineaLetra".
                                val letra = lyrics
                                if (letra != null) {
                                    // Sin semántica, como la línea bajo la portada.
                                    Box(Modifier.clearAndSetSemantics {}) {
                                        LetraPrevia(
                                            letra = letra, posMs = posLetra,
                                            tamanoSp = settings.tamanoFuenteLetras
                                        )
                                    }
                                    Text(stringResource(R.string.player_letra_de, letra.source),
                                        style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        }
                    }
                }
                // Tarjeta Artista (idea de SimpMusic): foto, oyentes y biografía
                artista?.let { a ->
                    item(key = "artista") {
                        Column(Modifier.animateItem().onSizeChanged { if (!subiendo) alturaArtista = it.height }) {
                        TarjetaReproductor(tarjeta, onClick = {
                            playerViewModel.cerrarReproductor()
                            navController.navigate(Screen.Artist.createRoute(a.nombre, a.browseId))
                        }) {
                            Column {
                                Box(Modifier.fillMaxWidth().height(190.dp)) {
                                    AsyncImage(conReintento(a.fotoUrl), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.45f), 0.4f to Color.Transparent)))
                                    Text(stringResource(R.string.player_artista), color = Color.White, fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                                }
                                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp)) {
                                    Text(a.nombre, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (a.oyentes.isNotBlank()) Text(a.oyentes, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (a.biografia.isNotBlank()) Text(a.biografia, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                        }
                    }
                }
                // Hueco que guarda la altura de Letra y Artista mientras la lista sube.
                if (subiendo) item(key = "reserva") {
                    Spacer(Modifier.height(with(LocalDensity.current) { (alturaLetra + alturaArtista).toDp() }))
                }
            }
            // Franja bajo la barra de estado: al bajar, el texto ya no pasa por debajo de la hora.
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).drawBehind { drawRect(tinte.value) })
            }
        }
        }
    }
}

// ─── Piezas del reproductor: cada una lee solo el estado que muestra ─────────

/**
 * Posición que solo se sigue con el reproductor a la vista. Oculto (cerrado, con el
 * Inicio delante) devuelve la última leída y no lee el tic, así la barra y la letra no
 * se rehacen cada 0,5 s sin que nadie las vea: en un Galaxy J7 eso ocupaba ~6 % del
 * hilo principal todo el rato. Al abrirlo, `visible` cambia y quien la lee vuelve a
 * leer la real en ese mismo fotograma.
 */
private class PosicionVisible(private val real: State<Long>, private val visible: State<Boolean>) : State<Long> {
    private var ultima = 0L
    override val value: Long
        get() = if (visible.value) real.value.also { ultima = it } else ultima
}

/**
 * Duración de lo que suena. Sin preparar (la app se abrió con la cola en pausa)
 * ExoPlayer aún no la sabe y se veía "0:00": se usa la guardada de la canción o de
 * la cola. Derivada: quien la lee solo se rehace cuando cambia el número.
 */
@Composable
private fun rememberDuracion(vm: PlayerViewModel): State<Long> {
    val durationMs = vm.durationMs.collectAsStateWithLifecycle()
    val cancion = vm.currentSong.collectAsStateWithLifecycle()
    val cola = vm.cola.collectAsStateWithLifecycle()
    val indice = vm.indiceActual.collectAsStateWithLifecycle()
    return remember {
        derivedStateOf {
            durationMs.value.takeIf { it > 0 } ?: cancion.value?.durationMs?.takeIf { it > 0 }
                ?: cola.value.getOrNull(indice.value)?.duracionMs?.takeIf { it > 0 } ?: 0L
        }
    }
}

@Composable
private fun CabeceraReproductor(
    vm: PlayerViewModel,
    menuAbierto: Boolean,
    onMenu: (Boolean) -> Unit,
    mostrarVerLetra: Boolean,
    onAnadirPlaylist: () -> Unit,
    onTemporizador: () -> Unit,
    onVerLetra: () -> Unit
) {
    val origen by vm.origenCola.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.cerrarReproductor() }) {
            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.player_cerrar), Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.player_reproduciendo_ahora), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.2.sp)
            origen?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        Box {
            IconButton(onClick = { onMenu(true) }) { Icon(Icons.Default.MoreVert, stringResource(R.string.player_mas_opciones)) }
            DropdownMenu(expanded = menuAbierto, onDismissRequest = { onMenu(false) }) {
                MenuReproductor(vm, onCerrar = { onMenu(false) }, mostrarVerLetra, onAnadirPlaylist, onTemporizador, onVerLetra)
            }
        }
    }
}

/**
 * Contenido del ⋮ del reproductor. Solo existe con el menú abierto: el estado de las
 * descargas cambia en cada % de una descarga y antes rehacía el reproductor entero.
 */
@Composable
private fun MenuReproductor(
    vm: PlayerViewModel,
    onCerrar: () -> Unit,
    mostrarVerLetra: Boolean,
    onAnadirPlaylist: () -> Unit,
    onTemporizador: () -> Unit,
    onVerLetra: () -> Unit
) {
    val downloadVm: DownloadViewModel = hiltViewModel()
    val song by vm.currentSong.collectAsStateWithLifecycle()
    val descargadas by downloadVm.downloadedSongs.collectAsStateWithLifecycle(emptyList())
    val descargas by downloadVm.downloads.collectAsStateWithLifecycle()
    // Mejora X: botón de descarga como toggle según estado. Las canciones del
    // teléfono ya están en él: no se descargan.
    val sid = song?.id
    val estaDescargada = sid != null && descargadas.any { it.id == sid }
    val prog = sid?.let { descargas[it] }
    val descargando = prog?.status == DownloadStatus.DOWNLOADING ||
        prog?.status == DownloadStatus.PENDING
    val delTelefono = song?.origen == OrigenCancion.LOCAL.name && !estaDescargada
    val fallos by downloadVm.fallos.collectAsStateWithLifecycle()
    val fallo = sid?.let { fallos[it] }?.takeIf { !descargando && !estaDescargada }
    if (song != null && !delTelefono) DropdownMenuItem(
        text = {
            if (fallo != null) com.auramusic.app.ui.components.TextoReintentarDescarga(fallo)
            else Text(when {
                descargando    -> stringResource(R.string.desc_descargando, prog?.progress ?: 0)
                estaDescargada -> stringResource(R.string.desc_eliminar)
                else           -> stringResource(R.string.accion_descargar)
            })
        },
        leadingIcon = {
            if (fallo != null) com.auramusic.app.ui.components.IconoReintentarDescarga()
            else Icon(when {
                descargando    -> Icons.Default.Downloading
                estaDescargada -> Icons.Default.DeleteOutline
                else           -> Icons.Default.Download
            }, null)
        },
        modifier = if (fallo != null) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) else Modifier,
        enabled = !descargando,
        onClick = {
            onCerrar()
            song?.let { s ->
                if (estaDescargada) downloadVm.deleteDownload(s.id)
                else downloadVm.downloadSong(s.toSearchItem())
            }
        }
    )
    DropdownMenuItem(text = { Text(stringResource(R.string.menu_anadir_playlist)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
        enabled = song != null,
        onClick = { onCerrar(); onAnadirPlaylist() })
    song?.let { s ->
        com.auramusic.app.ui.components.OpcionNoRecomendar(s, vm) { onCerrar() }
    }
    DropdownMenuItem(text = { Text(stringResource(R.string.menu_temporizador)) },
        leadingIcon = { Icon(Icons.Default.Timer, null) },
        onClick = { onCerrar(); onTemporizador() })
    if (mostrarVerLetra)
        DropdownMenuItem(text = { Text(stringResource(R.string.menu_ver_letras)) },
            leadingIcon = { Icon(Icons.Default.Lyrics, null) },
            onClick = { onCerrar(); onVerLetra() })
}

@Composable
private fun PortadaReproductor(vm: PlayerViewModel, direccion: () -> Int, animar: Boolean) {
    val song by vm.currentSong.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp), Alignment.Center) {
        val scale by animateFloatAsState(if (isPlaying) 1f else 0.94f,
            spring(Spring.DampingRatioMediumBouncy), label = "cover_scale")
        // La portada nueva entra por el lado hacia el que se avanzó, creciendo
        // un poco; la vieja sale por el otro (antes: fundido de 250 ms).
        // El marco es el mismo de la portada del mini: al abrir el
        // reproductor esta vuela desde allí (portadaCompartida).
        val alcanceReproductor = LocalAlcanceReproductor.current
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
        AnimatedContent(
            targetState = song, contentKey = { it?.id },
            transitionSpec = { if (animar) cambioDeCancion(direccion(), conEscala = true) else sinAnimacion() },
            label = "portadaCancion"
        ) { s ->
            Card(
                modifier = Modifier.escalaEnLayout { scale }
                    .portadaCompartida(alcanceReproductor, s?.id).fillMaxWidth().aspectRatio(1f),
                shape = FormaPortada,
                elevation = CardDefaults.cardElevation(20.dp)
            ) {
                AsyncImage(portadaCompartidaModelo(mejorarCaratula(s?.thumbnailUrl)), null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
            }
        }
        }
    }
}

// Título, artista, badge de calidad y me gusta.
@Composable
private fun InfoCancion(vm: PlayerViewModel, navController: NavController, direccion: () -> Int, animar: Boolean) {
    val cancion by vm.currentSong.collectAsStateWithLifecycle()
    val badge by vm.qualityBadge.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // Título y artista acompañan a la portada, con un desplazamiento menor.
        AnimatedContent(
            targetState = cancion, contentKey = { it?.id },
            transitionSpec = { if (animar) cambioDeCancion(direccion(), fraccion = 4) else sinAnimacion() },
            label = "tituloCancion", modifier = Modifier.weight(1f)
        ) { song ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(song?.title ?: stringResource(R.string.player_sin_reproduccion),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                QualityBadge(badge = badge)
            }
            Spacer(Modifier.height(4.dp))
            Text(song?.artist ?: "", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Bug 3: tocar el artista abre su catálogo de YouTube Music.
                modifier = Modifier.clickable {
                    val a = extractMainArtist(song?.artist ?: "")
                    if (a.isNotBlank()) { vm.cerrarReproductor(); navController.navigate(Screen.Artist.createRoute(a, song?.artistId)) }
                })
        }
        }
        CorazonMeGusta(cancion?.isLiked == true, cancion?.id, stringResource(R.string.player_me_gusta),
            MaterialTheme.colorScheme.onSurfaceVariant) { vm.toggleLike() }
    }
}

@Composable
private fun ProgresoReproductor(vm: PlayerViewModel, posicion: State<Long>) {
    val duracion by rememberDuracion(vm)
    BarraProgreso(posicion, duracion) { vm.seekTo(it) }
}

// Controles: aleatorio, anterior, play, siguiente, repetir
@Composable
private fun ControlesReproductor(vm: PlayerViewModel, visible: Boolean = true) {
    val isPlaying   by vm.isPlaying.collectAsStateWithLifecycle()
    // Oculto, la ruedita no se monta: su animación es infinita y, aunque no se
    // dibuje, despertaba el hilo principal en cada fotograma (60/s con el Inicio
    // delante mientras una canción cargaba: ~1,6 s de CPU cada 30 s).
    val isLoading   = vm.isLoading.collectAsStateWithLifecycle().value && visible
    val aleatorio   by vm.aleatorio.collectAsStateWithLifecycle()
    val modoRepetir by vm.modoRepetir.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically) {
        val activo = MaterialTheme.colorScheme.primary
        val inactivo = MaterialTheme.colorScheme.onSurface
        IconButton(onClick = { vm.toggleAleatorio() }) {
            IconoConEstado(Icons.Default.Shuffle,
                stringResource(if (aleatorio) R.string.player_aleatorio_si else R.string.player_aleatorio_no),
                if (aleatorio) activo else inactivo)
        }
        IconButton(onClick = { vm.skipPrevious() }, Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipPrevious, stringResource(R.string.player_anterior), Modifier.size(36.dp))
        }
        // Fix 2: scale sutil al tocar el play/pausa (0.9 → 1.0, 100ms).
        val fabInteraction = remember { MutableInteractionSource() }
        val fabPressed by fabInteraction.collectIsPressedAsState()
        val fabScale by animateFloatAsState(
            if (fabPressed) 0.9f else 1f, tween(100), label = "fab_scale"
        )
        // Como en YT Music: sonando es un cuadrado redondeado; en pausa, un círculo.
        val esquinas by animateDpAsState(if (isPlaying) 28.dp else 42.dp,
            spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow), label = "esquinasPlay")
        FloatingActionButton(
            onClick = { if (!isLoading) vm.togglePlayPause() },
            interactionSource = fabInteraction,
            shape = RoundedCornerShape(esquinas),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(84.dp).scale(fabScale)
        ) {
            if (isLoading) CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
            else IconoPlayPausa(isPlaying,
                stringResource(if (isPlaying) R.string.player_pausar else R.string.player_reproducir), Modifier.size(40.dp))
        }
        IconButton(onClick = { vm.skipNext() }, Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipNext, stringResource(R.string.player_siguiente), Modifier.size(36.dp))
        }
        IconButton(onClick = { vm.cambiarRepetir() }) {
            IconoConEstado(if (modoRepetir == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                stringResource(when (modoRepetir) {
                    Player.REPEAT_MODE_ALL -> R.string.player_repetir_todo
                    Player.REPEAT_MODE_ONE -> R.string.player_repetir_una
                    else -> R.string.player_repetir_no
                }),
                if (modoRepetir == Player.REPEAT_MODE_OFF) inactivo else activo)
        }
    }
}

// Asa "A continuación": solo depende de la siguiente canción (que la reproducción
// automática añada más al final de la cola no la rehace).
@Composable
private fun AsaCola(vm: PlayerViewModel, tarjeta: () -> Color, onAbrir: () -> Unit) {
    val cola = vm.cola.collectAsStateWithLifecycle()
    val indice = vm.indiceActual.collectAsStateWithLifecycle()
    val siguiente by remember { derivedStateOf { cola.value.getOrNull(indice.value + 1) } }
    TarjetaReproductor(tarjeta, onClick = onAbrir) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cola_titulo), fontWeight = FontWeight.Bold)
                    Text(siguiente?.let { "${it.titulo} • ${it.artista}" } ?: stringResource(R.string.cola_vacia),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// Tarjeta del reproductor (cola, letra, artista) con el color del tinte, un poco más claro.
// El color se lee al dibujar: cuando el tinte se anima, la tarjeta solo se repinta.
@Composable
private fun TarjetaReproductor(color: () -> Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
        .drawBehind { drawRect(color()) }.clickable(onClick = onClick)) { content() }
}

// Lee la posición en su propio ámbito y redondeada a medio segundo: con letras el
// tic va a 100 ms, pero la barra no necesita redibujarse 10 veces por segundo
// (en gama baja cada fotograma cuesta). Antes recomponía toda la pantalla.
@Composable
private fun BarraProgreso(posicion: State<Long>, durationMs: Long, onSeek: (Long) -> Unit) {
    val progressMs by remember(posicion) { derivedStateOf { posicion.value / 500 * 500 } }
    // Mientras se arrastra, la barra sigue al dedo al instante y el salto se hace
    // al soltar (como SimpMusic); antes pedía un seekTo por cada movimiento y la
    // barra dependía del tic, así que se quedaba atrás.
    var arrastrando by remember { mutableStateOf<Float?>(null) }
    val fraccion = arrastrando ?: if (durationMs > 0) (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    // Sin semántica (lo decidió el usuario): la barra y el tiempo cambian varias veces
    // por segundo y cada cambio hacía que el servicio de accesibilidad (en HyperOS
    // viene uno activado) releyera la pantalla, ~1/3 del hilo principal sonando.
    // Con TalkBack la barra deja de ser accesible; los botones siguen siéndolo.
    Column(Modifier.padding(horizontal = 20.dp).clearAndSetSemantics {}) {
        Slider(value = fraccion,
            onValueChange = { arrastrando = it },
            onValueChangeFinished = { arrastrando?.let { onSeek((it * durationMs).toLong()) }; arrastrando = null },
            // Bug M: en AMOLED (fondo #000) el track inactivo se volvía
            // invisible. onSurface al 25% es gris visible en CUALQUIER
            // tema (gris sobre blanco en Claro, gris sobre negro en AMOLED).
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
            ))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(formatMs(arrastrando?.let { (it * durationMs).toLong() } ?: progressMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
