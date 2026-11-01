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
import androidx.activity.compose.BackHandler
import com.auramusic.app.ui.theme.LocalTransicionCompartida
import com.auramusic.app.ui.theme.capsulaCristal
import com.auramusic.app.ui.theme.cristalDisponible
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
    val currentSong by playerViewModel.currentSong.collectAsStateWithLifecycle()

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

    // Escena de la portada que vuela (mini ↔ reproductor): abarca el contenido y la
    // capa del mini reproductor para que la portada pueda pasar de uno a otro.
    SharedTransitionLayout {
    CompositionLocalProvider(LocalTransicionCompartida provides this) {
    if (ventanaExpandida) {
        // Fix 3: pantallas anchas (tablets / horizontal) → NavigationRail vertical a
        // la izquierda + contenido a la derecha; el mini player va abajo del contenido.
        // El rail y el mini player se ocultan al abrir Now Playing (pantalla completa).
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
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    ContenidoPrincipal(navController, playerViewModel)
                }
                AnimatedVisibility(
                    visible = !isNowPlaying && currentSong != null,
                    enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(tween(300)),
                    exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(tween(300))
                ) {
                    MiniPlayer(playerViewModel, fondoCristal = null, alcance = this) {
                        playerViewModel.abrirReproductor()
                    }
                }
            }
        }
        CapaReproductor(isNowPlaying, navController, playerViewModel)
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
                    .then(if (conCristal) Modifier.layerBackdrop(fondoCristal) else Modifier)
                    .background(colorFondo)
            ) {
                ContenidoPrincipal(navController, playerViewModel)
            }
            AnimatedVisibility(
                visible = !isNowPlaying,
                // Al ritmo del reproductor: baja mientras la portada vuela hacia arriba.
                enter = slideInVertically(Movimiento.vuelo()) { it },
                exit = slideOutVertically(Movimiento.vuelo(Movimiento.MEDIA)) { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val alcanceMini = this
                Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
                    AnimatedVisibility(
                        visible = currentSong != null,
                        // Fix 2: el mini player entra/sale deslizando desde abajo + fade (300ms).
                        enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(tween(300)),
                        exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(tween(300))
                    ) {
                        MiniPlayer(playerViewModel, if (conCristal) fondoCristal else null, alcance = alcanceMini) {
                            playerViewModel.abrirReproductor()
                        }
                    }
                    AuraBottomBar(
                        navController = navController,
                        currentDestination = currentDest,
                        fondoCristal = if (conCristal) fondoCristal else null
                    )
                }
            }
            CapaReproductor(isNowPlaying, navController, playerViewModel)
        }
        }
    }
    }
    }
}

// El reproductor a pantalla completa, como capa encima de la navegación: el Inicio
// (o la pantalla que sea) sigue montado debajo, así que al cerrar la portada vuela
// de vuelta al mini igual que al abrir. Sube un poco al aparecer y baja al irse.
@Composable
private fun CapaReproductor(
    abierto: Boolean,
    navController: androidx.navigation.NavHostController,
    playerViewModel: PlayerViewModel
) {
    AnimatedVisibility(
        visible = abierto,
        enter = fadeIn(Movimiento.fundido(Movimiento.LARGA)) +
            slideInVertically(Movimiento.entrada(Movimiento.LARGA)) { SUBIDA_REPRODUCTOR },
        exit = fadeOut(Movimiento.fundido(Movimiento.MEDIA)) +
            slideOutVertically(Movimiento.vuelo(Movimiento.MEDIA)) { SUBIDA_REPRODUCTOR }
    ) {
        // Atrás cierra el reproductor (el visor de letras y la cola, compuestos
        // dentro, se cierran antes porque sus manejadores van después).
        BackHandler { playerViewModel.cerrarReproductor() }
        CompositionLocalProvider(LocalAlcanceReproductor provides this) {
            NowPlayingScreen(navController, playerViewModel)
        }
    }
}

// Lo que sube el reproductor al abrirse, en px.
private const val SUBIDA_REPRODUCTOR = 120

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
    fondoCristal: Backdrop?
) {
    // Colores derivados del TEMA (Claro/Oscuro/AMOLED), no fijos.
    val unselected = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val superficie = MaterialTheme.colorScheme.surfaceContainer
    val indiceActivo = NAV_ITEMS.indexOfFirst { item ->
        currentDestination?.hierarchy?.any { it.route == item.screen.route } == true
    }
    val posicionBurbuja by animateFloatAsState(
        targetValue = indiceActivo.coerceAtLeast(0).toFloat(),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "burbujaPestana"
    )
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(64.dp)
            .capsulaCristal(
                fondo = fondoCristal,
                tinte = superficie.copy(alpha = 0.35f),
                solido = superficie,
                borde = MaterialTheme.colorScheme.onSurface.copy(0.08f)
            )
            .padding(4.dp)
    ) {
        val anchoPestana = maxWidth / NAV_ITEMS.size
        if (indiceActivo >= 0) Box(
            Modifier.offset(x = anchoPestana * posicionBurbuja)
                .width(anchoPestana).fillMaxHeight()
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f))
        )
        Row(Modifier.fillMaxSize()) {
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
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "navScale"
    )
    val color by animateColorAsState(
        targetValue = if (selected) accent else unselected,
        animationSpec = tween(200), label = "navColor"
    )
    Column(
        Modifier.weight(1f).fillMaxHeight()
            .clip(CircleShape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            if (selected) item.filled else item.outlined,
            contentDescription = stringResource(item.labelRes),
            tint = color, modifier = Modifier.size(24.dp).scale(scale)
        )
        Text(stringResource(item.labelRes), fontSize = 10.sp, color = color, maxLines = 1, softWrap = false)
    }
}

// ─── Mini Player Premium ──────────────────────────────────────────────────
@Composable
fun MiniPlayer(vm: PlayerViewModel, fondoCristal: Backdrop?, alcance: AnimatedVisibilityScope? = null, onClick: () -> Unit) {
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
                        transitionSpec = { fadeIn(Movimiento.fundido()) togetherWith fadeOut(Movimiento.salida()) },
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
                    transitionSpec = { cambioVertical() }, label = "miniTextos",
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
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(horizontal = 32.dp, vertical = 3.dp)
                        .fillMaxWidth().height(2.dp)
                )
            }
        }
    }

    // Cápsula de cristal (o sólida del tema si no hay cristal).
    Box(
        modificadorMini.capsulaCristal(
            fondo = fondoCristal,
            tinte = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.20f),
            solido = MaterialTheme.colorScheme.surfaceContainer,
            borde = MaterialTheme.colorScheme.onSurface.copy(0.08f),
            desenfoque = 6.dp,
            alturaRefraccion = 16.dp,
            refraccion = 32.dp
        ),
        content = contenidoMini
    )
}
