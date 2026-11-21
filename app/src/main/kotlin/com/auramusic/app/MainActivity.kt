package com.auramusic.app

import com.auramusic.app.ui.components.LocalIntentoRed
import com.auramusic.app.ui.components.conReintento
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.auramusic.app.R
import com.auramusic.app.ui.components.LineaProgreso
import com.auramusic.app.ui.components.QualityBadge
import com.auramusic.app.ui.navigation.AuraNavHost
import com.auramusic.app.ui.navigation.Screen
import com.auramusic.app.data.repository.RepositorioPreferencias
import com.auramusic.app.ui.screens.PlayerViewModel
import com.auramusic.app.ui.screens.SettingsViewModel
import com.auramusic.app.ui.screens.mejorarCaratula
import com.auramusic.app.ui.theme.AuraMusicTheme
import com.auramusic.app.ui.theme.LocalMovimientoReducido
import com.auramusic.app.ui.theme.rememberMovimientoReducido
import com.auramusic.app.ui.theme.cambioVertical
import com.auramusic.app.ui.theme.Movimiento
import com.auramusic.app.ui.theme.FormaPortada
import com.auramusic.app.ui.components.portadaCompartidaModelo
import com.auramusic.app.ui.theme.portadaCompartida
import com.auramusic.app.ui.components.IconoPlayPausa
import com.auramusic.app.ui.screens.NowPlayingScreen
import com.auramusic.app.ui.theme.LocalAlcanceReproductor
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.BackEventCompat
import com.auramusic.app.ui.theme.encogerConGesto
import kotlinx.coroutines.launch
import com.auramusic.app.ui.theme.LocalTransicionCompartida
import com.auramusic.app.ui.theme.sinAnimacion
import com.auramusic.app.ui.theme.capsulaCristal
import com.auramusic.app.ui.theme.cristalDisponible
import com.auramusic.app.ui.theme.ALTO_GOTA
import com.auramusic.app.ui.theme.GotaPestana
import com.auramusic.app.ui.theme.rememberLuminanciaCristal
import com.auramusic.app.ui.theme.temaOscuro
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.roundToInt
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    val playerViewModel: PlayerViewModel by viewModels()

    @Inject lateinit var repositorioPreferencias: RepositorioPreferencias

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Debe ir ANTES de super.onCreate(): cambia del tema de splash al tema base
        // sin pasar por un fondo blanco.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Lee el tema guardado UNA vez de forma síncrona ANTES de pintar, para que
        // el primer frame ya use el tema correcto (sin parpadeo claro→oscuro).
        // Es una lectura mínima de DataStore; aceptable bloquear aquí.
        val ajustesIniciales = runBlocking { repositorioPreferencias.ajustes.first() }

        // D3: si la app se abrió desde un link de music.youtube.com, reproduce el
        // videoId del link de una vez. Solo en la PRIMERA creación: al recrearse la
        // Activity (girar, modo oscuro, el sistema la mató por memoria) o al volver
        // desde recientes, Android entrega el mismo intent y se reemplazaba la cola
        // por esa canción otra vez (la "misma canción repetida" en autoplay).
        val videoDeepLink = if (savedInstanceState == null && !abiertaDesdeRecientes(intent))
            extraerVideoId(intent) else null
        videoDeepLink?.let { playerViewModel.reproducirVideoId(it) }

        setContent {
            // Recolecta los ajustes en vivo (cambiar el tema se aplica al instante),
            // partiendo del valor ya leído para no parpadear en el primer frame.
            val ajustes by repositorioPreferencias.ajustes.collectAsState(initial = ajustesIniciales)
            // Fix 3: clase de ancho de ventana. Expanded (pantallas anchas/tablets) →
            // NavigationRail vertical; Compact/Medium → barra inferior normal.
            val windowSize = calculateWindowSizeClass(this@MainActivity)
            val ventanaExpandida = windowSize.widthSizeClass == WindowWidthSizeClass.Expanded
            // Fallo 5: las portadas se reintentan solas cuando vuelve la red.
            val intentoRed by playerViewModel.intentoRed.collectAsState()
            AuraMusicTheme(tema = ajustes.tema) {
              CompositionLocalProvider(LocalIntentoRed provides intentoRed, LocalMovimientoReducido provides rememberMovimientoReducido()) {
                AuraMusicApp(
                    playerViewModel = playerViewModel,
                    openNowPlaying = intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) ||
                        videoDeepLink != null,
                    ventanaExpandida = ventanaExpandida
                )
              }
            }
        }
    }

    // El tic de posición solo hace falta con la interfaz a la vista.
    override fun onStart() { super.onStart(); playerViewModel.setInterfazVisible(true) }
    override fun onStop() { playerViewModel.setInterfazVisible(false); super.onStop() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        // D3: link recibido con la app ya abierta.
        val video = extraerVideoId(intent)
        video?.let { playerViewModel.reproducirVideoId(it) }
        // Notificación o Hyper Island con la app ya abierta: muestra el reproductor.
        if (intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) || video != null) playerViewModel.abrirReproductor()
    }

    private fun abiertaDesdeRecientes(intent: Intent?): Boolean =
        (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

    // D3: extrae el videoId de un link de music.youtube.com (watch?v=ID). Devuelve
    // null si no es ese dominio o no es una acción VIEW (no toca youtube.com general).
    private fun extraerVideoId(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        if (data.host != "music.youtube.com") return null
        // (L3) Valida que el id sea EXACTAMENTE un videoId de YouTube (11 chars del
        // alfabeto base64url). Descarta en silencio cualquier otra cosa, evitando que
        // un valor malicioso fluya a URLs o a nombres de archivo de descarga.
        return data.getQueryParameter("v")?.takeIf { it.matches(VIDEO_ID_REGEX) }
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "OPEN_NOW_PLAYING"
        // (L3) videoId de YouTube: 11 caracteres del alfabeto base64url.
        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AuraMusicApp(
    playerViewModel: PlayerViewModel,
    openNowPlaying: Boolean = false,
    ventanaExpandida: Boolean = false
) {
    val navController = rememberNavController()
    LaunchedEffect(openNowPlaying) {
        if (openNowPlaying) playerViewModel.abrirReproductor()
    }
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentDest = navBackStack?.destination
    // El reproductor es una capa encima de la navegación (ver CapaReproductor).
    val isNowPlaying by playerViewModel.reproductorAbierto.collectAsStateWithLifecycle()
    // Aquí solo importa si hay canción: leer la canción entera rehacía la raíz de la
    // app en cada cambio de canción o "me gusta".
    val cancionActual = playerViewModel.currentSong.collectAsStateWithLifecycle()
    val haySong by remember { derivedStateOf { cancionActual.value != null } }

    // Bug E: diálogo de error GLOBAL. Al ser un Dialog se dibuja en su propia
    // ventana, SIEMPRE encima de todo y en cualquier pantalla (ya no queda detrás
    // de la UI como el banner anterior dentro de Now Playing).
    val errorMsg by playerViewModel.errorMessage.collectAsStateWithLifecycle()
    if (errorMsg != null) {
        AlertDialog(
            onDismissRequest = { playerViewModel.clearError() },
            icon = { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.player_error_titulo)) },
            text = { Text(errorMsg ?: "") },
            confirmButton = {
                TextButton(onClick = { playerViewModel.reintentar() }) {
                    Text(stringResource(R.string.accion_reintentar))
                }
            },
            dismissButton = {
                TextButton(onClick = { playerViewModel.clearError() }) {
                    Text(stringResource(R.string.accion_cerrar))
                }
            }
        )
    }

    // Bug D: en HyperOS/MIUI el sistema mata el servicio en segundo plano si la app
    // no está en la lista blanca de batería. Si no lo está, ofrecemos abrir el
    // diálogo del sistema para concederlo (clave para que siga sonando con la app
    // cerrada). Una vez concedido, isIgnoring=true y ya no se vuelve a mostrar.
    val ctx = LocalContext.current
    var mostrarBateria by remember {
        mutableStateOf(
            run {
                val pm = ctx.getSystemService(android.os.PowerManager::class.java)
                !(pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true)
            }
        )
    }
    if (mostrarBateria) {
        AlertDialog(
            onDismissRequest = { mostrarBateria = false },
            icon = { Icon(Icons.Default.BatteryAlert, null) },
            title = { Text(stringResource(R.string.bateria_titulo)) },
            text = { Text(stringResource(R.string.bateria_mensaje)) },
            confirmButton = {
                TextButton(onClick = {
                    mostrarBateria = false
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    }.onFailure {
                        // Algunos OEM no exponen la acción directa: abre la lista general.
                        runCatching {
                            ctx.startActivity(
                                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        }
                    }
                }) { Text(stringResource(R.string.bateria_permitir)) }
            },
            dismissButton = {
                TextButton(onClick = { mostrarBateria = false }) {
                    Text(stringResource(R.string.bateria_ahora_no))
                }
            }
        )
    }

    // true con el reproductor abierto y quieto: lo de debajo no se ve (ver noDibujarSi).
    val reproductorTapa = remember { mutableStateOf(false) }

    if (ventanaExpandida) {
        // Fix 3: pantallas anchas (tablets / horizontal) → NavigationRail vertical a
        // la izquierda + contenido a la derecha; el mini player va abajo del contenido.
        // El rail y el mini player se ocultan al abrir Now Playing (pantalla completa).
        // Aquí el mini va en la columna del contenido, así que la escena de la portada
        // que vuela (mini ↔ reproductor) abarca todo.
        SharedTransitionLayout {
        CompositionLocalProvider(LocalTransicionCompartida provides this) {
        Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AnimatedVisibility(
                visible = !isNowPlaying,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it }
            ) {
                AuraNavRail(navController = navController, currentDestination = currentDest)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth().noDibujarSi(reproductorTapa)) {
                    ContenidoPrincipal(navController, playerViewModel)
                }
                AnimatedVisibility(
                    visible = !isNowPlaying && haySong,
                    enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(tween(300)),
                    exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(tween(300))
                ) {
                    MiniPlayer(playerViewModel, fondoCristal = null, alcance = this) {
                        playerViewModel.abrirReproductor()
                    }
                }
            }
        }
        CapaReproductor(isNowPlaying, navController, playerViewModel, reproductorTapa)
        }
        }
        }
    } else {
        // Liquid glass: el contenido ocupa toda la pantalla y se graba en
        // fondoCristal; el mini reproductor y la barra flotan encima y lo dibujan
        // desenfocado y refractado (ver CristalLiquido.kt). Sin cristal (ajuste
        // apagado o Android < 13) son las mismas cápsulas, pero sólidas.
        val settingsVm: SettingsViewModel = hiltViewModel()
        val ajustes by settingsVm.settings.collectAsStateWithLifecycle()
        val conCristal = ajustes.liquidGlassEnabled && cristalDisponible()
        val fondoCristal = rememberLayerBackdrop()
        val colorFondo = MaterialTheme.colorScheme.background
        // Surface (no Box) para dar a las pantallas el color de texto del tema, como
        // hacía el Scaffold; con un Box el texto sin color explícito salía negro.
        Surface(Modifier.fillMaxSize(), color = colorFondo, contentColor = MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize()
                    .noDibujarSi(reproductorTapa)
                    .then(if (conCristal) Modifier.layerBackdrop(fondoCristal) else Modifier)
                    .background(colorFondo)
            ) {
                ContenidoPrincipal(navController, playerViewModel)
            }
            // Escena de la portada que vuela (mini ↔ reproductor). Abarca SOLO el mini, la
            // barra y el reproductor: dentro de ella todo se mide dos veces (pasada
            // "lookahead"), y antes envolvía también las pantallas de debajo, que se
            // medían dos veces en cada cambio de canción o al desplazarse (en un Galaxy
            // J7, ~1/4 del trabajo de los fotogramas lentos al cambiar de canción).
            SharedTransitionLayout(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalTransicionCompartida provides this) {
            Box(Modifier.fillMaxSize()) {
            // Mini y barra se quedan CONSTRUIDOS con el reproductor abierto, como el
            // reproductor cerrado (ver CapaReproductor): antes AnimatedVisibility los
            // descartaba y al cerrar había que componerlos de cero, con el cristal, en
            // el primer fotograma (~130–140 ms en un ZTE Blade A31 Lite). Ocultos no se
            // colocan: ni se dibujan ni reciben toques, y el progreso del mini se lee
            // al dibujar, así que no trabajan.
            val barras = updateTransition(
                if (isNowPlaying) EnterExitState.PostExit else EnterExitState.Visible, label = "barras")
            // Al ritmo del reproductor: bajan mientras la portada vuela hacia arriba.
            val bajada = barras.animateFloat(
                transitionSpec = { if (targetState == EnterExitState.Visible) Movimiento.vuelo() else Movimiento.vuelo(Movimiento.MEDIA) },
                label = "bajadaBarras"
            ) { if (it == EnterExitState.Visible) 0f else 1f }
            val alcanceMini = remember(barras) { object : AnimatedVisibilityScope { override val transition = barras } }
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .layout { medible, limites ->
                        val p = medible.measure(limites)
                        layout(p.width, p.height) {
                            // Por el estado de la transición, no por isRunning: en el primer
                            // fotograma de abrir aún no corre y desaparecían un fotograma.
                            if (barras.currentState == EnterExitState.Visible || barras.targetState == EnterExitState.Visible)
                                p.placeWithLayer(0, 0) { translationY = bajada.value * p.height }
                        }
                    }
                    .navigationBarsPadding().padding(bottom = 8.dp)
            ) {
                AnimatedVisibility(
                    visible = haySong,
                    // Fix 2: el mini player entra/sale deslizando desde abajo + fade (300ms).
                    enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(tween(300)),
                    exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(tween(300))
                ) {
                    // Tapado por el reproductor, el cambio de canción no se anima.
                    MiniPlayer(playerViewModel, if (conCristal) fondoCristal else null, alcance = alcanceMini, animar = !isNowPlaying) {
                        playerViewModel.abrirReproductor()
                    }
                }
                AuraBottomBar(
                    navController = navController,
                    currentDestination = currentDest,
                    fondoCristal = if (conCristal) fondoCristal else null,
                    medirFondo = !isNowPlaying
                )
            }
            CapaReproductor(isNowPlaying, navController, playerViewModel, reproductorTapa)
            }
            }
            }
        }
        }
    }
}

// El reproductor a pantalla completa, como capa encima de la navegación: el Inicio
// (o la pantalla que sea) sigue montado debajo, así que al cerrar la portada vuela
// de vuelta al mini igual que al abrir. Sube un poco al aparecer y baja al irse.
//
// El reproductor se queda CONSTRUIDO aunque esté cerrado: antes AnimatedVisibility
// lo descartaba y cada apertura tenía que componerlo y medirlo entero, un parón de
// 80–160 ms al empezar la animación en gama baja (ZTE Blade A31 Lite). Cerrado no se
// coloca (no se dibuja ni recibe toques) y NowPlayingScreen para lo que trabaja en
// segundo plano. Cuesta ~1–2 MB. Se construye la primera vez que se abre o, si hay
// una canción, 1,5 s después de arrancar (no en el primer fotograma: el arranque no
// se alarga). El vuelo de la portada usa la misma transición que la capa.
@Composable
private fun CapaReproductor(
    abierto: Boolean,
    navController: androidx.navigation.NavHostController,
    playerViewModel: PlayerViewModel,
    tapa: MutableState<Boolean>
) {
    val cancion = playerViewModel.currentSong.collectAsStateWithLifecycle()
    val haySong by remember { derivedStateOf { cancion.value != null } }
    var construido by remember { mutableStateOf(false) }
    if (abierto) construido = true
    LaunchedEffect(haySong) {
        if (haySong && !construido) { kotlinx.coroutines.delay(1_500); construido = true }
    }
    if (!construido) return

    val transicion = updateTransition(
        if (abierto) EnterExitState.Visible else EnterExitState.PostExit, label = "capaReproductor")
    // Lo que antes eran el enter/exit de AnimatedVisibility, con las mismas curvas.
    val alfa = transicion.animateFloat(
        transitionSpec = { if (targetState == EnterExitState.Visible) Movimiento.fundido(Movimiento.LARGA) else Movimiento.fundido(Movimiento.MEDIA) },
        label = "alfaReproductor"
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val subida = transicion.animateFloat(
        transitionSpec = { if (targetState == EnterExitState.Visible) Movimiento.entrada(Movimiento.LARGA) else Movimiento.vuelo(Movimiento.MEDIA) },
        label = "subidaReproductor"
    ) { if (it == EnterExitState.Visible) 0f else SUBIDA_REPRODUCTOR.toFloat() }
    // Con el estado de la transición y no con isRunning: en el primer fotograma de
    // abrir o cerrar la animación aún no ha empezado (isRunning es false) y salía un
    // fotograma en negro al abrir (se dejaba de dibujar lo de debajo con el
    // reproductor aún transparente) y otro sin reproductor al cerrar.
    val enPantalla = abierto || transicion.currentState == EnterExitState.Visible

    // Atrás cierra el reproductor (el visor de letras y la cola, compuestos dentro,
    // se cierran antes porque sus manejadores van después). Con el gesto de atrás
    // (Android 14+) el reproductor se encoge siguiendo el dedo y deja ver lo de debajo;
    // al soltar se cierra con su animación de siempre y, si se cancela, vuelve a su sitio.
    // Con el botón de atrás (o en Android < 14) se cierra sin más, como antes.
    val gesto = remember { Animatable(0f) }
    val desdeIzquierda = remember { mutableStateOf(true) }
    val ambitoGesto = rememberCoroutineScope()
    PredictiveBackHandler(enabled = abierto) { progreso ->
        try {
            progreso.collect { e ->
                desdeIzquierda.value = e.swipeEdge == BackEventCompat.EDGE_LEFT
                gesto.snapTo(e.progress)
            }
            playerViewModel.cerrarReproductor()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            ambitoGesto.launch { gesto.animateTo(0f, Movimiento.estandar()) }
            throw e
        }
    }
    // Encogido se sigue viendo hasta que termina de cerrarse; al reabrirlo, entero.
    LaunchedEffect(abierto) { if (abierto) gesto.snapTo(0f) }
    LaunchedEffect(transicion.currentState) { if (transicion.currentState == EnterExitState.PostExit) gesto.snapTo(0f) }
    val encogido by remember { derivedStateOf { gesto.value > 0f } }

    // Abierto del todo (opaco, en su sitio y sin encoger) tapa la pantalla entera. Al
    // cerrar, abierto pasa a false en el mismo fotograma y lo de debajo vuelve a dibujarse.
    val tapaAhora = abierto && !encogido && transicion.currentState == EnterExitState.Visible && !transicion.isRunning
    SideEffect { tapa.value = tapaAhora }
    val alcance = remember(transicion) { object : AnimatedVisibilityScope { override val transition = transicion } }
    Box(Modifier.fillMaxSize().layout { medible, limites ->
        // Se mide siempre (así abrir no tiene que medir nada), pero oculto no se coloca.
        val p = medible.measure(limites)
        layout(p.width, p.height) {
            if (enPantalla) p.placeWithLayer(0, 0) {
                alpha = alfa.value; translationY = subida.value
                encogerConGesto(gesto.value, desdeIzquierda.value)
            }
        }
    }) {
        CompositionLocalProvider(LocalAlcanceReproductor provides alcance) {
            NowPlayingScreen(navController, playerViewModel, visible = abierto)
        }
    }
}

// Lo que sube el reproductor al abrirse, en px.
private const val SUBIDA_REPRODUCTOR = 120

/**
 * Con [oculto] en true su capa pasa a opacidad 0 y la GPU se la salta. Lo usa la
 * navegación que queda debajo del reproductor abierto: dibujarla costaba ~5 ms por
 * fotograma en un ZTE Blade A31 Lite (16 ms con el Inicio debajo frente a 11 ms con
 * una pantalla vacía), y el cristal la volvía a grabar en su capa.
 *
 * No se deja de colocar: así Android descarta lo grabado y al cerrar el reproductor
 * había que volver a grabar el Inicio entero (~95 ms, el peor fotograma del cierre
 * pasaba de 87 a 160 ms). Con opacidad 0 lo grabado se conserva y al volver solo
 * cambia una propiedad de la capa. El State se lee en la capa, sin recomponer.
 */
private fun Modifier.noDibujarSi(oculto: State<Boolean>) =
    graphicsLayer { alpha = if (oculto.value) 0f else 1f }

// Contenido principal (NavHost + indicador de sincronización). Reutilizado por el
// layout de barra inferior (Compact/Medium) y el de NavigationRail (Expanded).
@Composable
private fun ContenidoPrincipal(
    navController: androidx.navigation.NavHostController,
    playerViewModel: PlayerViewModel
) {
    Box(Modifier.fillMaxSize()) {
        AuraNavHost(navController, playerViewModel)
    }
}

// ─── Bottom Bar Premium ───────────────────────────────────────────────────
data class NavItem(val screen: Screen, val labelRes: Int,
    val filled: ImageVector, val outlined: ImageVector)

private val NAV_ITEMS = listOf(
    NavItem(Screen.Home,    R.string.nav_inicio,     Icons.Filled.Home,          Icons.Outlined.Home),
    NavItem(Screen.Search,  R.string.nav_buscar,     Icons.Filled.Search,        Icons.Outlined.Search),
    // Cambio 5: "Artistas" (solo locales, poco útil como pestaña) -> "Local" que
    // abre directo la música del dispositivo. Los artistas de YouTube Music se
    // acceden desde el perfil del artista en el reproductor.
    NavItem(Screen.Local,   R.string.nav_local,      Icons.Filled.PhoneAndroid,  Icons.Outlined.PhoneAndroid),
    NavItem(Screen.Library, R.string.nav_biblioteca, Icons.Filled.LibraryMusic,  Icons.Outlined.LibraryMusic),
)

// Navegación de pestañas compartida por la barra inferior y el NavigationRail.
private fun navegarAPestana(navController: androidx.navigation.NavController, screen: Screen) {
    // Bug 14: Inicio debe volver al inicio desde CUALQUIER pantalla (incluida Ajustes).
    // popBackStack es más fiable que saveState/restoreState para la pestaña de inicio.
    if (screen.route == Screen.Home.route) {
        if (!navController.popBackStack(Screen.Home.route, false)) {
            navController.navigate(Screen.Home.route) { launchSingleTop = true }
        }
    } else if (!navController.popBackStack(screen.route, false)) {
        // Si la pestaña ya está debajo (p. ej. Buscar → Artista → Álbum), se vuelve a
        // ella; antes restoreState devolvía la misma página de artista y no pasaba nada.
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true; restoreState = true
        }
    }
}

// Fix 3: NavigationRail vertical para pantallas anchas (tablets / horizontal). Mismos
// destinos y misma lógica de navegación que la barra inferior; ítems centrados.
@Composable
fun AuraNavRail(
    navController: androidx.navigation.NavController,
    currentDestination: androidx.navigation.NavDestination?
) {
    val accent = MaterialTheme.colorScheme.primary
    val unselected = MaterialTheme.colorScheme.onSurfaceVariant
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = unselected
    ) {
        Spacer(Modifier.weight(1f))
        NAV_ITEMS.forEach { item ->
            val selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true
            NavigationRailItem(
                selected = selected,
                onClick = { navegarAPestana(navController, item.screen) },
                icon = {
                    Icon(
                        if (selected) item.filled else item.outlined,
                        contentDescription = stringResource(item.labelRes)
                    )
                },
                label = {
                    Text(stringResource(item.labelRes), fontSize = 10.sp, maxLines = 1, softWrap = false)
                },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = accent,
                    unselectedIconColor = unselected,
                    selectedTextColor = accent,
                    unselectedTextColor = unselected,
                    indicatorColor = accent.copy(alpha = 0.12f)
                )
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

// Barra inferior flotante en forma de píldora (de cristal si fondoCristal != null).
// Una burbuja se desliza hasta la pestaña activa.
@Composable
fun AuraBottomBar(
    navController: androidx.navigation.NavController,
    currentDestination: androidx.navigation.NavDestination?,
    fondoCristal: Backdrop?,
    // False con el reproductor encima: la barra no se ve y medir su fondo cada 2,5 s
    // (una lectura de GPU de ~5 ms en el hilo principal) no serviría de nada.
    medirFondo: Boolean = true
) {
    // Colores derivados del TEMA (Claro/Oscuro/AMOLED), no fijos.
    val unselected = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val superficie = MaterialTheme.colorScheme.surfaceContainer
    val indicePestana = NAV_ITEMS.indexOfFirst { item ->
        currentDestination?.hierarchy?.any { it.route == item.screen.route } == true
    }
    // En un detalle (playlist, artista, ajustes…) sigue marcada la pestaña de la que
    // se vino. Antes no se marcaba ninguna y la burbuja volvía a la posición 0: al
    // volver aparecía en Inicio y saltaba a la pestaña (p. ej. Biblioteca).
    var ultimaPestana by remember { mutableIntStateOf(0) }
    LaunchedEffect(indicePestana) { if (indicePestana >= 0) ultimaPestana = indicePestana }
    val indiceActivo = if (indicePestana >= 0) indicePestana else ultimaPestana

    // Cristal como el de SimpMusic (ver CristalLiquido.kt): la cápsula se oscurece según
    // lo que tiene detrás y crece al pulsarla; la pestaña activa es una gota que se
    // desliza estirándose, se hincha al tocarla y se puede arrastrar a otra pestaña. Sin
    // cristal (Android < 13 o ajuste apagado) la gota es la píldora de color de siempre,
    // con el mismo movimiento.
    val capa = rememberGraphicsLayer()
    val luminancia = rememberLuminanciaCristal(capa, activa = fondoCristal != null && medirFondo)
    val oscuro = temaOscuro()
    val n = NAV_ITEMS.size
    val ambito = rememberCoroutineScope()
    val anchoPestanaPx = remember { floatArrayOf(1f) }
    val arrastrada = remember { booleanArrayOf(false) }
    val activoActual by rememberUpdatedState(indiceActivo)
    val gota = remember(ambito) {
        GotaPestana(
            ambito, valorInicial = indiceActivo.toFloat(), rango = 0f..(n - 1).toFloat(),
            escalaPulsada = 76f / 56f,
            alEmpezar = { arrastrada[0] = false },
            alSoltar = {
                if (arrastrada[0]) {
                    val i = valorObjetivo.roundToInt().coerceIn(0, n - 1)
                    // Siempre a una pestaña, aunque sea la misma: si no, se queda a medias.
                    animarA(i.toFloat())
                    if (i != activoActual) navegarAPestana(navController, NAV_ITEMS[i].screen)
                }
            },
            alArrastrar = { dx ->
                if (dx != 0f) arrastrada[0] = true
                actualizar(valorObjetivo + dx / anchoPestanaPx[0])
            }
        )
    }
    // Al cambiar de pestaña (con un toque, al volver atrás o al arrastrar) la gota va hasta ella.
    LaunchedEffect(indiceActivo) { if (gota.valorObjetivo != indiceActivo.toFloat()) gota.animarA(indiceActivo.toFloat()) }

    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(64.dp)
    ) {
        val anchoPestana = (maxWidth - 8.dp) / n
        anchoPestanaPx[0] = with(LocalDensity.current) { anchoPestana.toPx() }
        // 1) La cápsula.
        Box(
            Modifier.matchParentSize().capsulaCristal(
                fondo = fondoCristal, solido = superficie,
                borde = MaterialTheme.colorScheme.onSurface.copy(0.08f),
                capa = capa, luminancia = luminancia
            )
        )
        // 2) La gota, detrás de los iconos.
        val movimientoGota: GraphicsLayerScope.() -> Unit = {
            scaleX = gota.escalaX; scaleY = gota.escalaY
            val v = gota.velocidad / 10f
            scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)
            scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
        }
        Box(
            Modifier.align(Alignment.CenterStart)
                .graphicsLayer { translationX = gota.valor * anchoPestanaPx[0] + 4.dp.toPx() }
                .then(
                    if (fondoCristal != null) Modifier.drawBackdrop(
                        backdrop = fondoCristal,
                        shape = { Capsule() },
                        effects = {
                            val l = (luminancia.value * 2f - 1f).let { kotlin.math.sign(it) * it * it }
                            val p = gota.progresoPulsado
                            vibrancy()
                            colorControls(0.05f, 1f, 1.5f)
                            blur((if (l > 0f) lerp(8.dp.toPx(), 16.dp.toPx(), l) else lerp(8.dp.toPx(), 2.dp.toPx(), -l)) + 20.dp.toPx())
                            lens(10.dp.toPx() * p, 14.dp.toPx() * p, chromaticAberration = true)
                        },
                        highlight = { Highlight.Default.copy(alpha = 0.6f) },
                        shadow = { Shadow(radius = 4.dp, alpha = 0.4f) },
                        // Sin pulsar (p = 0) no hay sombra interior: null hace que Backdrop
                        // no la dibuje (con radio y opacidad 0 la dibujaba igual).
                        innerShadow = { val p = gota.progresoPulsado; if (p > 0f) InnerShadow(radius = 8.dp * p, alpha = p) else null },
                        layerBlock = movimientoGota,
                        onDrawSurface = {
                            val claro = ((luminancia.value - 0.3f) / 0.5f).coerceIn(0f, 1f)
                            drawRect(Color.Black.copy(alpha = if (oscuro) lerp(0.22f, 0.55f, claro) else lerp(0.06f, 0.14f, claro)))
                        }
                    ) else Modifier.graphicsLayer(movimientoGota).clip(CircleShape).background(accent.copy(alpha = 0.16f))
                )
                .width(anchoPestana).height(ALTO_GOTA)
        )
        // 3) Iconos y nombres encima, nítidos; llevan el arrastre de la gota.
        Row(Modifier.matchParentSize().padding(horizontal = 4.dp).then(gota.modificador)) {
            NAV_ITEMS.forEachIndexed { i, item ->
                PestanaFlotante(
                    item = item, selected = i == indiceActivo, accent = accent, unselected = unselected,
                    onClick = { navegarAPestana(navController, item.screen) }
                )
            }
        }
    }
}

@Composable
private fun RowScope.PestanaFlotante(
    item: NavItem, selected: Boolean, accent: Color, unselected: Color, onClick: () -> Unit
) {
    val color = if (selected) accent else unselected
    Column(
        Modifier.weight(1f).fillMaxHeight()
            .clip(CircleShape)
            .clickable(interactionSource = null, indication = null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        Icon(
            if (selected) item.filled else item.outlined,
            contentDescription = stringResource(item.labelRes),
            tint = color, modifier = Modifier.size(24.dp)
        )
        Text(stringResource(item.labelRes), fontSize = 10.sp, color = color, maxLines = 1, softWrap = false)
    }
}

// ─── Mini Player Premium ──────────────────────────────────────────────────
@Composable
fun MiniPlayer(vm: PlayerViewModel, fondoCristal: Backdrop?, alcance: AnimatedVisibilityScope? = null, animar: Boolean = true, onClick: () -> Unit) {
    val song      by vm.currentSong.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    // progressMs cambia en cada tic: aquí solo se guarda el State y se lee al dibujar
    // la línea de progreso, para no recomponer todo el mini reproductor cada vez.
    val progreso  = vm.progressMs.collectAsStateWithLifecycle()
    val duration  by vm.durationMs.collectAsStateWithLifecycle()
    val badge     by vm.qualityBadge.collectAsStateWithLifecycle()

    // Colores del mini player derivados del TEMA (Claro/Oscuro/AMOLED), no fijos.
    val colorTexto    = MaterialTheme.colorScheme.onSurface
    val colorTextoSec = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val colorPista    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    val modificadorMini = Modifier.fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .height(64.dp)
        .clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick
        )
    val contenidoMini: @Composable BoxScope.() -> Unit = {
        Box(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Portada redonda (FormaPortada a 46 dp es un círculo). Es la misma que
                // la del reproductor: al abrirlo vuela hasta allí. Al cambiar de canción
                // la imagen se funde y los textos suben (solo por id: dar me gusta o
                // cambiar el badge no anima).
                Box(Modifier.size(46.dp)) {
                    AnimatedContent(
                        targetState = song, contentKey = { it?.id },
                        transitionSpec = { if (animar) fadeIn(Movimiento.fundido()) togetherWith fadeOut(Movimiento.salida()) else sinAnimacion() },
                        label = "miniPortada"
                    ) { s ->
                        Card(
                            shape = FormaPortada,
                            modifier = Modifier.portadaCompartida(alcance, s?.id).fillMaxSize(),
                            elevation = CardDefaults.cardElevation(6.dp)
                        ) {
                            AsyncImage(
                                model = portadaCompartidaModelo(mejorarCaratula(s?.thumbnailUrl)), contentDescription = null,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                AnimatedContent(
                    targetState = song, contentKey = { it?.id },
                    transitionSpec = { if (animar) cambioVertical() else sinAnimacion() }, label = "miniTextos",
                    modifier = Modifier.weight(1f)
                ) { s ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                s?.title ?: "", color = colorTexto,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(4.dp))
                            QualityBadge(badge)
                        }
                        Text(
                            s?.artist ?: "", color = colorTextoSec,
                            style = MaterialTheme.typography.labelSmall, maxLines = 1
                        )
                    }
                }
                // Play/Pause con micro-animación
                val playScale by animateFloatAsState(
                    targetValue = if (isPlaying) 1f else 0.9f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "playBtnScale"
                )
                IconButton(onClick = { vm.togglePlayPause() }) {
                    IconoPlayPausa(isPlaying, null, Modifier.size(28.dp).scale(playScale), tint = colorTexto)
                }
                IconButton(onClick = { vm.skipNext() }) {
                    Icon(Icons.Filled.SkipNext, null, tint = colorTexto.copy(0.75f), modifier = Modifier.size(24.dp))
                }
            }
            // Progreso: línea fina abajo, separada de los extremos redondeados.
            // Es decorativa (el progreso accesible está en el reproductor), así que
            // va sin semántica (LineaProgreso): cada tic solo la redibuja.
            if (duration > 0) {
                LineaProgreso(
                    progreso = { progreso.value.toFloat() / duration },
                    color = MaterialTheme.colorScheme.primary,
                    colorPista = colorPista,
                    // Capa propia: el progreso cambia en cada tic (2/s, 10/s con la letra)
                    // y, sin ella, cada tic volvía a grabar la capa de cristal del mini
                    // entera (desenfoque, lente y saturación incluidos).
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(horizontal = 32.dp, vertical = 3.dp)
                        .fillMaxWidth().height(2.dp)
                        .graphicsLayer()
                )
            }
        }
    }

    // Cápsula de cristal (o sólida del tema si no hay cristal). Mide su propio fondo para
    // graduar el velo y crece poco al pulsarla, por ser ancha (como en SimpMusic).
    val capa = rememberGraphicsLayer()
    // Con el reproductor encima (animar = false) el mini no se ve: no se mide su fondo.
    val luminancia = rememberLuminanciaCristal(capa, activa = fondoCristal != null && animar)
    Box(
        modificadorMini.capsulaCristal(
            fondo = fondoCristal,
            solido = MaterialTheme.colorScheme.surfaceContainer,
            borde = MaterialTheme.colorScheme.onSurface.copy(0.08f),
            capa = capa, luminancia = luminancia, escalaPulsado = 1.04f
        ),
        content = contenidoMini
    )
}
