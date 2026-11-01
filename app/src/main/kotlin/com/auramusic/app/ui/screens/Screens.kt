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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.auramusic.app.data.api.ArtistaItem
import com.auramusic.app.data.api.ResultadoPrincipal
import com.auramusic.app.data.api.SearchItem
import com.auramusic.app.data.api.PlaylistRef
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import com.auramusic.app.data.db.PlaylistEntity
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.domain.model.OrigenCancion
import com.auramusic.app.ui.components.AudioQualityBadge
import com.auramusic.app.ui.components.LetraPrevia
import com.auramusic.app.ui.components.VisorLetrasCompleto
import com.auramusic.app.ui.components.QualityBadge
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.auramusic.app.R
import kotlin.math.roundToInt
import com.auramusic.app.ui.navigation.Screen
import com.auramusic.app.ui.theme.rememberTintePortada
import com.auramusic.app.ui.theme.cambioDeCancion
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

// ─── HOME ─────────────────────────────────────────────────────────────────
@Composable
fun HomeScreen(navController: NavController, playerViewModel: PlayerViewModel) {
    // Fase E: Inicio con la estructura de YT Music (ver InicioScreen.kt).
    InicioScreen(navController, playerViewModel)
}

// ─── SEARCH ───────────────────────────────────────────────────────────────
@Composable
fun SearchScreen(navController: NavController, playerViewModel: PlayerViewModel) {
    val vm: SearchViewModel = hiltViewModel()
    val origenBusqueda = stringResource(R.string.cola_origen_busqueda)
    val query         by vm.query.collectAsStateWithLifecycle()
    val results       by vm.results.collectAsStateWithLifecycle()
    val suggestions   by vm.suggestions.collectAsStateWithLifecycle()
    val isLoading     by vm.isLoading.collectAsStateWithLifecycle()
    val filtro        by vm.filtro.collectAsStateWithLifecycle()
    val aviso         by vm.aviso.collectAsStateWithLifecycle()
    val principal     by vm.principal.collectAsStateWithLifecycle()
    val artistas      by vm.artistas.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.buscar_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotBlank())
                        IconButton(onClick = { vm.clear() }) { Icon(Icons.Default.Close, null) }
                },
                singleLine = true, shape = RoundedCornerShape(50),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(); keyboard?.hide() })
            )
        }
        // Chips: sin ninguno elegido, la mezcla; tocando el elegido se vuelve a ella.
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(SearchViewModel.Filtro.CANCIONES to R.string.buscar_canciones,
                SearchViewModel.Filtro.ARTISTAS to R.string.buscar_artistas).forEach { (f, texto) ->
                val elegido = filtro == f
                FilterChip(selected = elegido, onClick = { vm.elegirFiltro(f) },
                    label = { Text(stringResource(texto)) },
                    leadingIcon = if (elegido) {{ Icon(Icons.Default.Check, null, Modifier.size(14.dp)) }} else null)
            }
        }
        if (suggestions.isNotEmpty() && results.isEmpty() && artistas.isEmpty() && principal == null && !isLoading) {
            LazyColumn {
                items(suggestions) { s ->
                    ListItem(headlineContent = { Text(s) },
                        leadingContent = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
                        modifier = Modifier.clickable { vm.search(s); keyboard?.hide() })
                }
            }
        }
        if (isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (aviso != SearchViewModel.Aviso.NINGUNO) {
            AvisoBusqueda(aviso) { vm.search() }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 160.dp)) {
                if (filtro == SearchViewModel.Filtro.ARTISTAS) {
                    items(artistas, key = { it.browseId }) { a ->
                        FilaArtistaBusqueda(a) { navController.navigate(Screen.Artist.createRoute(a.nombre, a.browseId)) }
                    }
                    return@LazyColumn
                }
                principal?.let { p -> item { TarjetaResultadoPrincipal(p, navController, playerViewModel) } }
                if (artistas.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.buscar_artistas), Modifier.padding(start = 16.dp, top = 12.dp)) }
                    item {
                        FilaArtistas(artistas) { a ->
                            navController.navigate(Screen.Artist.createRoute(a.nombre, a.browseId))
                        }
                    }
                }
                if (results.isNotEmpty() && (principal != null || artistas.isNotEmpty())) {
                    item { SectionTitle(stringResource(R.string.buscar_canciones), Modifier.padding(start = 16.dp, top = 12.dp)) }
                }
                // Clave única por canción (YT Music a veces repite una en la lista).
                items(results.distinctBy { it.videoId }, key = { it.videoId }) { item ->
                    Box(Modifier.animateItem()) {
                        SearchResultRow(item = item, conTipo = true, onPlay = {
                            playerViewModel.playSong(item, origenBusqueda)
                            playerViewModel.abrirReproductor()
                        })
                    }
                }
            }
        }
    }
}

// Resultado más probable, como en YT Music: un artista (foto, oyentes, Aleatorio,
// Mix y sus 3 canciones top dentro de la tarjeta) o una canción.
@Composable
private fun TarjetaResultadoPrincipal(
    p: ResultadoPrincipal, navController: NavController, playerViewModel: PlayerViewModel
) {
    val origenBusqueda = stringResource(R.string.cola_origen_busqueda)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        when (p) {
            is ResultadoPrincipal.Artista -> Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { navController.navigate(Screen.Artist.createRoute(p.artista.nombre, p.artista.browseId)) }
                ) {
                    AsyncImage(conReintento(mejorarCaratula(p.artista.fotoUrl)), null,
                        Modifier.size(60.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.artista.nombre, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.buscar_tipo_artista, p.artista.subtitulo),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
                // El reproductor se abre cuando la lista ya cargó; mientras, el botón
                // muestra una ruedita y no admite más toques.
                val cargandoLista by playerViewModel.cargandoListaYtm.collectAsStateWithLifecycle()
                val abrirReproductor = { playerViewModel.abrirReproductor() }
                @Composable
                fun IconoLista(lista: PlaylistRef, icono: ImageVector) {
                    if (cargandoLista == lista.playlistId)
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    else Icon(icono, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Row(Modifier.padding(top = 14.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    p.aleatorio?.let { lista ->
                        Button(onClick = { playerViewModel.reproducirListaYtm(lista, p.artista.nombre) { abrirReproductor() } },
                            modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp)) {
                            IconoLista(lista, Icons.Default.Shuffle)
                            Text(stringResource(R.string.accion_aleatorio))
                        }
                    }
                    p.mix?.let { lista ->
                        OutlinedButton(onClick = { playerViewModel.reproducirListaYtm(lista, p.artista.nombre) { abrirReproductor() } },
                            modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp)) {
                            IconoLista(lista, Icons.Default.Sensors)
                            Text(stringResource(R.string.accion_mix))
                        }
                    }
                }
                p.canciones.forEachIndexed { i, c ->
                    SearchResultRow(item = c, enTarjeta = true, onPlay = {
                        playerViewModel.reproducirCola(p.canciones.map { it.toSongEntity() }, i, p.artista.nombre)
                        playerViewModel.abrirReproductor()
                    })
                }
            }
            is ResultadoPrincipal.Cancion -> SearchResultRow(item = p.item, enTarjeta = true, onPlay = {
                playerViewModel.playSong(p.item, origenBusqueda)
                playerViewModel.abrirReproductor()
            })
        }
    }
}

// Fila del chip "Artistas": foto redonda, nombre y oyentes; abre su página.
@Composable
private fun FilaArtistaBusqueda(a: ArtistaItem, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(a.nombre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(stringResource(R.string.buscar_tipo_artista, a.subtitulo), maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingContent = {
            AsyncImage(conReintento(mejorarCaratula(a.fotoUrl)), null,
                Modifier.size(56.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun FilaArtistas(artistas: List<ArtistaItem>, onClick: (ArtistaItem) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(artistas) { a ->
            Column(
                Modifier.width(84.dp).clip(RoundedCornerShape(12.dp)).clickable { onClick(a) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(conReintento(mejorarCaratula(a.fotoUrl)), null,
                    Modifier.size(80.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Text(a.nombre, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
internal fun AvisoBusqueda(aviso: SearchViewModel.Aviso, onReintentar: () -> Unit) {
    val (icono, texto) = when (aviso) {
        SearchViewModel.Aviso.SIN_CONEXION   -> Icons.Outlined.WifiOff to R.string.error_sin_conexion
        SearchViewModel.Aviso.SIN_RESULTADOS -> Icons.Outlined.SearchOff to R.string.buscar_sin_resultados
        else                                 -> Icons.Outlined.ErrorOutline to R.string.buscar_error
    }
    Box(Modifier.fillMaxWidth().padding(top = 80.dp, start = 32.dp, end = 32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icono, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(texto), color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
            if (aviso != SearchViewModel.Aviso.SIN_RESULTADOS) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onReintentar) { Text(stringResource(R.string.accion_reintentar)) }
            }
        }
    }
}

// ─── LIBRARY ──────────────────────────────────────────────────────────────
@Composable
fun LibraryScreen(navController: NavController, playerViewModel: PlayerViewModel) {
    val playlistVm: PlaylistViewModel = hiltViewModel()
    val playlists  by playlistVm.playlists.collectAsStateWithLifecycle(emptyList())
    val downloaded by playerViewModel.downloadedSongs.collectAsStateWithLifecycle(emptyList())
    val liked      by playerViewModel.likedSongs.collectAsStateWithLifecycle(emptyList())
    var showDialog by remember { mutableStateOf(false) }
    var newName    by remember { mutableStateOf("") }
    var dlgImportar by remember { mutableStateOf(false) }
    // Borrar no se puede deshacer: antes un toque suelto en la papelera la borraba.
    var plABorrar   by remember { mutableStateOf<PlaylistEntity?>(null) }

    if (dlgImportar) DialogoImportarPlaylist(playlistVm) { dlgImportar = false }

    plABorrar?.let { pl ->
        AlertDialog(onDismissRequest = { plABorrar = null },
            title = { Text(stringResource(R.string.playlist_borrar_titulo, pl.name)) },
            text = { Text(stringResource(R.string.playlist_borrar_texto)) },
            confirmButton = {
                TextButton(onClick = { playlistVm.deletePlaylist(pl); plABorrar = null }) {
                    Text(stringResource(R.string.playlist_borrar), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { plABorrar = null }) { Text(stringResource(R.string.accion_cancelar)) } }
        )
    }

    if (showDialog) {
        AlertDialog(onDismissRequest = { showDialog = false }, title = { Text(stringResource(R.string.playlist_nueva)) },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it },
                placeholder = { Text(stringResource(R.string.playlist_nombre)) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) { playlistVm.createPlaylist(newName); newName = ""; showDialog = false }
                }) { Text(stringResource(R.string.accion_crear)) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.accion_cancelar)) } })
    }

    LazyColumn(contentPadding = PaddingValues(top = 60.dp, bottom = 160.dp, start = 16.dp, end = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(stringResource(R.string.nav_biblioteca), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                Row {
                    IconButton(onClick = { dlgImportar = true }) {
                        Icon(Icons.Default.Link, stringResource(R.string.importar_titulo))
                    }
                    IconButton(onClick = { showDialog = true }) { Icon(Icons.Default.Add, null) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        // Mejora 8: "Música del dispositivo" se quitó de aquí; ya tiene su propia
        // pestaña "Local" en la barra inferior (era un acceso redundante).
        if (playlists.isNotEmpty() || liked.isNotEmpty() || downloaded.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.biblioteca_playlists)) }
            // Playlists automáticas (como Favorite/Downloaded de SimpMusic): aparecen
            // con el primer me gusta o la primera descarga y no se pueden borrar.
            if (liked.isNotEmpty()) item {
                PlaylistAutomatica(stringResource(R.string.biblioteca_me_gusta), liked.size, Icons.Filled.Favorite) {
                    navController.navigate(Screen.PlaylistDetail.createRoute(Screen.PlaylistDetail.ME_GUSTA))
                }
            }
            if (downloaded.isNotEmpty()) item {
                PlaylistAutomatica(stringResource(R.string.biblioteca_descargas), downloaded.size, Icons.Filled.DownloadDone) {
                    navController.navigate(Screen.PlaylistDetail.createRoute(Screen.PlaylistDetail.DESCARGAS))
                }
            }
            items(playlists, key = { it.id }) { pl ->
                ListItem(
                    headlineContent = { Text(pl.name) },
                    leadingContent = {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(0.18f)),
                            Alignment.Center) {
                            Icon(Icons.Filled.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { plABorrar = pl }) {
                            Icon(Icons.Default.DeleteOutline, null)
                        }
                    },
                    // Mejora 9: tocar la playlist abre su vista interna (lista + botones
                    // reproducir/aleatorio, reordenar y compartir).
                    modifier = Modifier.animateItem().clickable {
                        navController.navigate(Screen.PlaylistDetail.createRoute(pl.id))
                    }
                )
            }
        }
        if (playlists.isEmpty() && downloaded.isEmpty() && liked.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 80.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.LibraryMusic, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.biblioteca_vacia), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { showDialog = true }) { Text(stringResource(R.string.playlist_crear)) }
                    }
                }
            }
        }
    }
}

// ─── ARTISTS (avatares circulares) ────────────────────────────────────────
@Composable
fun ArtistsScreen(navController: NavController, playerViewModel: PlayerViewModel) {
    val songs by playerViewModel.recentlyPlayed.collectAsStateWithLifecycle(emptyList())
    val artists = remember(songs) {
        songs.groupBy { extractMainArtist(it.artist) }
            .map { (name, list) -> Pair(name, list) }.sortedBy { it.first }
    }
    LazyColumn(contentPadding = PaddingValues(top = 60.dp, bottom = 160.dp, start = 16.dp, end = 16.dp)) {
        item {
            Text(stringResource(R.string.nav_artistas), style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        }
        if (artists.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 80.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Person, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.artistas_vacio),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        items(artists) { (name, list) ->
            ListItem(
                headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) },
                supportingContent = { Text(pluralStringResource(R.plurals.n_canciones, list.size, list.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = {
                    // Avatar CIRCULAR con borde sutil — diferente a portadas cuadradas
                    Box(Modifier.size(52.dp)) {
                        Box(Modifier.size(52.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(0.15f))) {
                            AsyncImage(conReintento(list.first().thumbnailUrl), null,
                                Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop)
                        }
                        // Borde circular morado
                        Box(Modifier.size(52.dp).clip(CircleShape)
                            .background(Color.Transparent)
                        )
                    }
                },
                modifier = Modifier.clickable {
                    navController.navigate(Screen.Artist.createRoute(name, list.firstOrNull { it.artistId.isNotBlank() }?.artistId))
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(0.06f))
        }
    }
}

// ─── NOW PLAYING (con visor de letras y badge de calidad) ──────────────────
@Composable
fun NowPlayingScreen(navController: NavController, playerViewModel: PlayerViewModel) {
    val downloadVm: DownloadViewModel = hiltViewModel()
    val playlistVm: PlaylistViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val contexto = LocalContext.current

    val song        by playerViewModel.currentSong.collectAsStateWithLifecycle()
    val isPlaying   by playerViewModel.isPlaying.collectAsStateWithLifecycle()
    // progressMs cambia hasta 10 veces por segundo: aquí solo se guarda el State,
    // sin leerlo, o recompondría toda la pantalla (98 % de fotogramas lentos). Lo
    // leen BarraProgreso y las letras (solo al cambiar de línea).
    val posicion    = playerViewModel.progressMs.collectAsStateWithLifecycle()
    val durationMs  by playerViewModel.durationMs.collectAsStateWithLifecycle()
    val lyrics      by playerViewModel.lyrics.collectAsStateWithLifecycle()
    val isLoading   by playerViewModel.isLoading.collectAsStateWithLifecycle()
    val badge       by playerViewModel.qualityBadge.collectAsStateWithLifecycle()
    val playlists   by playlistVm.playlists.collectAsStateWithLifecycle(emptyList())
    val settings    by settingsVm.settings.collectAsStateWithLifecycle()
    // Estado de descarga de la canción actual (Mejora X) + temporizador (AA).
    val descargadas by downloadVm.downloadedSongs.collectAsStateWithLifecycle(emptyList())
    val descargas   by downloadVm.downloads.collectAsStateWithLifecycle()
    val temporizadorHasta by playerViewModel.temporizadorActivoHasta.collectAsStateWithLifecycle()

    var showMenu             by remember { mutableStateOf(false) }
    var showSleepTimer       by remember { mutableStateOf(false) }
    var showAddToPlaylist    by remember { mutableStateOf(false) }
    var showLyricsFullscreen by remember { mutableStateOf(false) }
    var showCola             by remember { mutableStateOf(false) }
    val cola        by playerViewModel.cola.collectAsStateWithLifecycle()
    val indiceCola  by playerViewModel.indiceActual.collectAsStateWithLifecycle()
    val origen      by playerViewModel.origenCola.collectAsStateWithLifecycle()
    val aleatorio   by playerViewModel.aleatorio.collectAsStateWithLifecycle()
    val modoRepetir by playerViewModel.modoRepetir.collectAsStateWithLifecycle()
    val artista     by playerViewModel.artistaActual.collectAsStateWithLifecycle()
    // Sentido del último cambio de canción (1 = siguiente, -1 = anterior) para que
    // portada y título entren por el lado correcto. El índice cambia antes que la
    // canción pintada, así que ya está listo cuando empieza la animación.
    var direccionCambio by remember { mutableIntStateOf(1) }
    var indicePrevio by remember { mutableIntStateOf(indiceCola) }
    LaunchedEffect(indiceCola) {
        if (indiceCola != indicePrevio) direccionCambio = if (indiceCola < indicePrevio) -1 else 1
        indicePrevio = indiceCola
    }
    if (showCola) ColaSheet(playerViewModel, settingsVm) { showCola = false }

    if (showSleepTimer) SleepTimerDialog(
        temporizadorHasta = temporizadorHasta,
        onActivar = { min -> playerViewModel.iniciarTemporizador(min); showSleepTimer = false },
        onCancelar = { playerViewModel.cancelarTemporizador(); showSleepTimer = false },
        onDismiss = { showSleepTimer = false }
    )
    if (showAddToPlaylist && song != null) {
        AddToPlaylistDialog(
            playlists = playlists,
            onSelect = { id ->
                val nombre = playlists.firstOrNull { it.id == id }?.name.orEmpty()
                playlistVm.addSongToPlaylist(id, song!!.id) { anadida ->
                    Toast.makeText(contexto, contexto.getString(
                        if (anadida) R.string.playlist_anadida else R.string.playlist_ya_estaba, nombre), Toast.LENGTH_SHORT).show()
                }
                showAddToPlaylist = false
            },
            onCrear = { nombre ->
                playlistVm.crearYAgregar(nombre, song!!.id)
                Toast.makeText(contexto, contexto.getString(R.string.playlist_anadida, nombre), Toast.LENGTH_SHORT).show()
                showAddToPlaylist = false
            },
            onDismiss = { showAddToPlaylist = false }
        )
    }

    // Fondo teñido con el color de la portada (idea de SimpMusic; ajuste "Fondo con
    // el color de la portada"). Con tema claro el tinte es el fondo del tema.
    val fondo = MaterialTheme.colorScheme.background
    val tinte by rememberTintePortada(mejorarCaratula(song?.thumbnailUrl), settings.blurPlayerBg)
    val tarjeta = if (tinte != fondo) lerp(tinte, Color.White, 0.08f) else MaterialTheme.colorScheme.surfaceContainer
    // Sin preparar (la app se abrió con la cola en pausa) ExoPlayer aún no sabe la
    // duración y se veía "0:00": se usa la guardada de la canción o de la cola.
    val duracion = durationMs.takeIf { it > 0 } ?: song?.durationMs?.takeIf { it > 0 }
        ?: cola.getOrNull(indiceCola)?.duracionMs?.takeIf { it > 0 } ?: 0L
    LaunchedEffect(song?.id) { playerViewModel.cargarArtistaActual() }
    val listaReproductor = rememberLazyListState()
    // Al cambiar de canción con la lista bajada, sube suave hasta la portada. Las
    // tarjetas Letra y Artista se van mientras cargan las nuevas y la lista, más
    // corta que la pantalla, saltaba arriba de golpe: mientras sube se reserva su
    // altura con un hueco al final (desde el MISMO fotograma del cambio).
    val idCancion = playerViewModel.currentSong.collectAsStateWithLifecycle().value?.id
    var idAnterior by remember { mutableStateOf(idCancion) }
    val subiendo = idAnterior != null && idCancion != idAnterior
    var alturaLetra by remember { mutableIntStateOf(0) }
    var alturaArtista by remember { mutableIntStateOf(0) }
    LaunchedEffect(idCancion) {
        if (subiendo && (listaReproductor.firstVisibleItemIndex > 0 || listaReproductor.firstVisibleItemScrollOffset > 0))
            listaReproductor.animateScrollToItem(0)
        idAnterior = idCancion
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tinte, 0.55f to lerp(tinte, fondo, 0.6f), 1f to fondo))) {
        // ── Banner de error ────────────────────────────────────────────────
        // Bug E: el banner vivía AQUÍ, dentro del Box, antes del LazyColumn, así
        // que el contenido se dibujaba ENCIMA y tapaba el error. Ahora el error se
        // muestra como un diálogo GLOBAL (ver AuraMusicApp en MainActivity), que se
        // pinta en su propia ventana, siempre encima y desde cualquier pantalla.

        // Posición de la letra corregida con el retraso de Ajustes (auriculares BT).
        val retrasoLetra = settings.retrasoLetrasMs
        val posLetra = { posicion.value - retrasoLetra }
        val hayLetra = lyrics != null && lyrics!!.lines.isNotEmpty()
        val letraVisible = hayLetra && (settings.showLyrics || showLyricsFullscreen)
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
            VisorLetrasCompleto(
                letra = letraVisor,
                titulo = song?.title ?: "", artista = song?.artist ?: "",
                portada = mejorarCaratula(song?.thumbnailUrl),
                posMs = posLetra, duracionMs = duracion, reproduciendo = isPlaying,
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
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { playerViewModel.cerrarReproductor() }) {
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
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.player_mas_opciones)) }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                // Mejora X: botón de descarga como toggle según estado. Las
                                // canciones del teléfono ya están en él: no se descargan.
                                val sid = song?.id
                                val estaDescargada = sid != null && descargadas.any { it.id == sid }
                                val prog = sid?.let { descargas[it] }
                                val descargando = prog?.status == DownloadStatus.DOWNLOADING ||
                                    prog?.status == DownloadStatus.PENDING
                                val delTelefono = song?.origen == OrigenCancion.LOCAL.name && !estaDescargada
                                if (song != null && !delTelefono) DropdownMenuItem(
                                    text = {
                                        Text(when {
                                            descargando    -> stringResource(R.string.desc_descargando, prog?.progress ?: 0)
                                            estaDescargada -> stringResource(R.string.desc_eliminar)
                                            else           -> stringResource(R.string.accion_descargar)
                                        })
                                    },
                                    leadingIcon = {
                                        Icon(when {
                                            descargando    -> Icons.Default.Downloading
                                            estaDescargada -> Icons.Default.DeleteOutline
                                            else           -> Icons.Default.Download
                                        }, null)
                                    },
                                    enabled = !descargando,
                                    onClick = {
                                        showMenu = false
                                        song?.let { s ->
                                            if (estaDescargada) downloadVm.deleteDownload(s.id)
                                            else downloadVm.downloadSong(s.toSearchItem())
                                        }
                                    }
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_anadir_playlist)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                                    enabled = song != null,
                                    onClick = { showMenu = false; showAddToPlaylist = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_temporizador)) },
                                    leadingIcon = { Icon(Icons.Default.Timer, null) },
                                    onClick = { showMenu = false; showSleepTimer = true })
                                // Con la tarjeta Letra a la vista esto la repetiría: solo aparece
                                // si "Mostrar letras" está apagado y la canción tiene letra.
                                if (hayLetra && !settings.showLyrics)
                                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_ver_letras)) },
                                        leadingIcon = { Icon(Icons.Default.Lyrics, null) },
                                        onClick = { showMenu = false; showLyricsFullscreen = true })
                            }
                        }
                    }
                }
                // Portada
                item(key = "portada") {
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
                            transitionSpec = { cambioDeCancion(direccionCambio, conEscala = true) },
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
                // La línea de letra que suena, bajo la portada (idea de SimpMusic)
                // Con las letras activadas el hueco se reserva siempre: al cambiar de
                // canción la letra tarda en llegar y todo lo de abajo daba un salto.
                item(key = "lineaLetra") {
                    // Sin semántica, como la barra de progreso (ver BarraProgreso): la letra
                    // entera sigue accesible en el visor a pantalla completa.
                    if (settings.showLyrics) Box(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp).clearAndSetSemantics {}) {
                        if (hayLetra && lyrics!!.isSynced) LineaLetraActual(lyrics!!, posLetra)
                        else Text(" ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    else Spacer(Modifier.height(18.dp))
                }
                // Info canción + badge
                item(key = "info") {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        // Título y artista acompañan a la portada, con un desplazamiento menor.
                        AnimatedContent(
                            targetState = song, contentKey = { it?.id },
                            transitionSpec = { cambioDeCancion(direccionCambio, fraccion = 4) },
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
                                    if (a.isNotBlank()) { playerViewModel.cerrarReproductor(); navController.navigate(Screen.Artist.createRoute(a, song?.artistId)) }
                                })
                        }
                        }
                        CorazonMeGusta(song?.isLiked == true, song?.id, stringResource(R.string.player_me_gusta),
                            MaterialTheme.colorScheme.onSurfaceVariant) { playerViewModel.toggleLike() }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                // Barra de progreso
                item(key = "progreso") {
                    BarraProgreso(posicion, duracion) { playerViewModel.seekTo(it) }
                    Spacer(Modifier.height(4.dp))
                }
                // Controles: aleatorio, anterior, play, siguiente, repetir
                item(key = "controles") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        val activo = MaterialTheme.colorScheme.primary
                        val inactivo = MaterialTheme.colorScheme.onSurface
                        IconButton(onClick = { playerViewModel.toggleAleatorio() }) {
                            IconoConEstado(Icons.Default.Shuffle,
                                stringResource(if (aleatorio) R.string.player_aleatorio_si else R.string.player_aleatorio_no),
                                if (aleatorio) activo else inactivo)
                        }
                        IconButton(onClick = { playerViewModel.skipPrevious() }, Modifier.size(56.dp)) {
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
                            onClick = { if (!isLoading) playerViewModel.togglePlayPause() },
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
                        IconButton(onClick = { playerViewModel.skipNext() }, Modifier.size(56.dp)) {
                            Icon(Icons.Default.SkipNext, stringResource(R.string.player_siguiente), Modifier.size(36.dp))
                        }
                        IconButton(onClick = { playerViewModel.cambiarRepetir() }) {
                            IconoConEstado(if (modoRepetir == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                stringResource(when (modoRepetir) {
                                    Player.REPEAT_MODE_ALL -> R.string.player_repetir_todo
                                    Player.REPEAT_MODE_ONE -> R.string.player_repetir_una
                                    else -> R.string.player_repetir_no
                                }),
                                if (modoRepetir == Player.REPEAT_MODE_OFF) inactivo else activo)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
                // Asa "A continuación" (como en YT Music): abre la hoja de la cola.
                item(key = "asaCola") {
                    val siguiente = cola.getOrNull(indiceCola + 1)
                    TarjetaReproductor(tarjeta, onClick = { showCola = true }) {
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
                                // Sin semántica, como la línea bajo la portada.
                                Box(Modifier.clearAndSetSemantics {}) {
                                    LetraPrevia(
                                        letra = lyrics!!, posMs = posLetra,
                                        tamanoSp = settings.tamanoFuenteLetras
                                    )
                                }
                                Text(stringResource(R.string.player_letra_de, lyrics!!.source),
                                    style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
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
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(tinte))
            }
        }
        }
    }
}

// Tarjeta del reproductor (cola, letra, artista) con el color del tinte, un poco más claro.
@Composable
private fun TarjetaReproductor(color: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
        .background(color).clickable(onClick = onClick)) { content() }
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

@Composable
private fun PlaylistAutomatica(nombre: String, n: Int, icono: ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(nombre) },
        supportingContent = { Text(pluralStringResource(R.plurals.n_canciones, n, n)) },
        leadingContent = {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(0.18f)),
                Alignment.Center) {
                Icon(icono, null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

// ─── DOWNLOADS ────────────────────────────────────────────────────────────
@Composable
fun DownloadsScreen(navController: NavController, playerViewModel: PlayerViewModel) {
    val downloaded by playerViewModel.downloadedSongs.collectAsStateWithLifecycle(emptyList())
    val origenDescargas = stringResource(R.string.biblioteca_descargas)
    LazyColumn(contentPadding = PaddingValues(top = 60.dp, bottom = 160.dp, start = 16.dp, end = 16.dp)) {
        item { Text(stringResource(R.string.biblioteca_descargas), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)) }
        if (downloaded.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 80.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.descargas_vacio), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        itemsIndexed(downloaded, key = { _, s -> s.id }) { index, song ->
            Box(Modifier.animateItem()) {
                SongRow(song = song, showDownloadIcon = true, onPlay = {
                    playerViewModel.reproducirCola(downloaded, index, origenDescargas)
                    playerViewModel.abrirReproductor()
                })
            }
        }
    }
}

// ─── COMPONENTES ──────────────────────────────────────────────────────────
// Función AA: temporizador con slider libre de 1 min a 10 h (600 min). Si ya hay
// un timer activo, muestra el tiempo restante y permite Modificar o Cancelar.
@Composable
fun SleepTimerDialog(
    temporizadorHasta: Long?,
    onActivar: (Int) -> Unit,
    onCancelar: () -> Unit,
    onDismiss: () -> Unit
) {
    val activo = temporizadorHasta != null && temporizadorHasta > System.currentTimeMillis()
    val restanteMin = if (activo)
        ((temporizadorHasta!! - System.currentTimeMillis() + 59_999L) / 60000L).toInt().coerceAtLeast(0) else 0   // hacia arriba: recién puesto en 5 decía "Quedan 4m"
    var minutos by remember { mutableFloatStateOf(30f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_temporizador)) },
        text = {
            Column {
                if (activo) {
                    Text(
                        stringResource(R.string.sleep_restante, formatoHorasMin(restanteMin)),
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    formatoHorasMin(minutos.roundToInt()),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                )
                // De 5 en 5 hasta 3 h: con 1–600 min elegir 15 o 45 era casi imposible.
                Slider(value = minutos, onValueChange = { minutos = it }, valueRange = 5f..180f, steps = 34)
            }
        },
        confirmButton = {
            TextButton(onClick = { onActivar(minutos.roundToInt()) }) {
                Text(stringResource(if (activo) R.string.sleep_modificar else R.string.sleep_activar))
            }
        },
        dismissButton = {
            if (activo) TextButton(onClick = onCancelar) { Text(stringResource(R.string.sleep_cancelar_timer)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.accion_cancelar)) }
        }
    )
}

private fun formatoHorasMin(min: Int): String {
    val h = min / 60; val m = min % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

// BB: además de elegir una playlist existente, permite crear una nueva y añadir
// la canción de una vez (antes solo decía "crea una primera" sin forma de crearla).
@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistEntity>,
    onSelect: (String) -> Unit,
    onCrear: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var nuevoNombre by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_anadir_playlist)) },
        text = {
            Column {
                OutlinedTextField(
                    value = nuevoNombre, onValueChange = { nuevoNombre = it },
                    placeholder = { Text(stringResource(R.string.playlist_nombre)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { if (nuevoNombre.isNotBlank()) onCrear(nuevoNombre.trim()) }) {
                            Icon(Icons.Default.Add, stringResource(R.string.playlist_crear))
                        }
                    }
                )
                if (playlists.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    playlists.forEach { pl ->
                        ListItem(headlineContent = { Text(pl.name) },
                            modifier = Modifier.clickable { onSelect(pl.id) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.accion_cancelar)) } }
    )
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(vertical = 8.dp))
}

@Composable
fun SongCard(song: SongEntity, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(onClick = onClick)) {
        Card(modifier = Modifier.size(140.dp), shape = RoundedCornerShape(12.dp)) {
            AsyncImage(conReintento(mejorarCaratula(song.thumbnailUrl)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(6.dp))
        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(extractMainArtist(song.artist), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SongRow(song: SongEntity, showDownloadIcon: Boolean = false, onPlay: () -> Unit) {
    ListItem(
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(extractMainArtist(song.artist), maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = {
            Card(modifier = Modifier.size(48.dp), shape = RoundedCornerShape(8.dp)) {
                AsyncImage(conReintento(mejorarCaratula(song.thumbnailUrl)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        },
        trailingContent = if (showDownloadIcon) ({
            Icon(Icons.Default.DownloadDone, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }) else null,
        modifier = Modifier.clickable(onClick = onPlay)
    )
}

@Composable
fun SearchResultRow(item: SearchItem, enTarjeta: Boolean = false, conTipo: Boolean = false, onPlay: () -> Unit) {
    ListItem(
        // Dentro de una tarjeta, el fondo lo pone la tarjeta.
        colors = if (enTarjeta) ListItemDefaults.colors(containerColor = Color.Transparent) else ListItemDefaults.colors(),
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // En la búsqueda, como en YT Music: "Canción • Artista • 3:40".
                val tipo = if (!conTipo) "" else
                    stringResource(if (item.isSong) R.string.tipo_cancion else R.string.tipo_video) + " • "
                Text(tipo + extractMainArtist(item.artist), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, false))
                if (item.duration.isNotBlank())
                    Text(" • ${item.duration}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
            }
        },
        leadingContent = {
            Card(modifier = Modifier.size(52.dp), shape = RoundedCornerShape(8.dp)) {
                AsyncImage(conReintento(mejorarCaratula(item.thumbnailUrl)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        },
        trailingContent = {
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.clickable(onClick = onPlay)
    )
}

// ─── Helpers ──────────────────────────────────────────────────────────────
fun SongEntity.toSearchItem() = SearchItem(
    videoId = id, title = title, artist = extractMainArtist(artist),
    thumbnailUrl = thumbnailUrl, duration = "", isSong = true, artistId = artistId
)

// "3:42" o "1:02:05" → milisegundos (0 si no se entiende).
private fun duracionEnMs(texto: String): Long =
    texto.trim().split(":").mapNotNull { it.toLongOrNull() }
        .takeIf { it.size in 2..3 && texto.count { c -> c == ':' } == it.size - 1 }
        ?.fold(0L) { acc, n -> acc * 60 + n }?.times(1000) ?: 0L

// Inverso: arma un SongEntity de YouTube a partir de un resultado de búsqueda
// (para encolar el catálogo del artista, Bug 3).
fun SearchItem.toSongEntity() = SongEntity(
    id = videoId, title = title, artist = artist, artistId = artistId, album = "",
    thumbnailUrl = thumbnailUrl, durationMs = duracionEnMs(duration),
    origen = OrigenCancion.YOUTUBE.name, fuente = videoId, explicit = explicit
)

fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

fun extractMainArtist(raw: String): String =
    raw.split(Regex(",|feat\\.|ft\\.|&|×| x |\\+| and ", RegexOption.IGNORE_CASE))
        .firstOrNull()?.trim()?.take(60) ?: raw.take(60)

// Mejora 10: sube la resolución de las miniaturas. YouTube Music/Google sirven
// thumbnails diminutos (=w120-h120); se piden en 544px. Las miniaturas de video
// de YouTube pasan a hqdefault (siempre existe, mejor que default/mqdefault).
// Las URI locales (content://) no se tocan.
fun mejorarCaratula(url: String?): String? {
    if (url.isNullOrBlank()) return url
    return when {
        url.contains("googleusercontent.com") || url.contains("ggpht.com") ->
            url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
        url.contains("ytimg.com") ->
            url.replace(Regex("/(default|mqdefault|sddefault)\\.jpg"), "/hqdefault.jpg")
        else -> url
    }
}

// Importar una playlist pública de YouTube / YouTube Music pegando su enlace.
// Si el portapapeles ya tiene un enlace de YouTube, se rellena solo.
@Composable
private fun DialogoImportarPlaylist(playlistVm: PlaylistViewModel, onCerrar: () -> Unit) {
    val estado by playlistVm.importacion.collectAsStateWithLifecycle()
    val portapapeles = LocalClipboardManager.current
    var enlace by remember {
        mutableStateOf(portapapeles.getText()?.text?.takeIf { "youtu" in it && "list=" in it }.orEmpty())
    }
    val cerrar = { playlistVm.cerrarImportacion(); onCerrar() }
    AlertDialog(
        onDismissRequest = { if (estado != PlaylistViewModel.EstadoImportacion.Importando) cerrar() },
        title = { Text(stringResource(R.string.importar_titulo)) },
        text = {
            when (val e = estado) {
                PlaylistViewModel.EstadoImportacion.Importando -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.importar_cargando))
                }
                is PlaylistViewModel.EstadoImportacion.Hecho ->
                    Text(stringResource(R.string.importar_hecho, e.nombre, e.canciones))
                else -> Column {
                    Text(stringResource(R.string.importar_ayuda), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = enlace, onValueChange = { enlace = it }, singleLine = true,
                        placeholder = { Text("music.youtube.com/playlist?list=…") }
                    )
                    if (e is PlaylistViewModel.EstadoImportacion.Error) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(if (e.enlaceNoValido) R.string.importar_enlace_malo else R.string.importar_error),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (estado) {
                PlaylistViewModel.EstadoImportacion.Importando -> {}
                is PlaylistViewModel.EstadoImportacion.Hecho ->
                    TextButton(onClick = cerrar) { Text(stringResource(R.string.importar_listo)) }
                else -> TextButton(
                    onClick = { playlistVm.importarDeYoutube(enlace) },
                    enabled = enlace.isNotBlank()
                ) { Text(stringResource(R.string.importar_accion)) }
            }
        },
        dismissButton = {
            if (estado !is PlaylistViewModel.EstadoImportacion.Importando &&
                estado !is PlaylistViewModel.EstadoImportacion.Hecho
            ) TextButton(onClick = cerrar) { Text(stringResource(R.string.accion_cancelar)) }
        }
    )
}
