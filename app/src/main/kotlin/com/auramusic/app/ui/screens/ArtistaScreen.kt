package com.auramusic.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.text.TextAutoSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.auramusic.app.R
import com.auramusic.app.data.api.ItemColeccion
import com.auramusic.app.data.api.PaginaArtista
import com.auramusic.app.data.api.PlaylistRef
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.ui.components.conReintento
import com.auramusic.app.ui.navigation.Screen
import com.auramusic.app.ui.theme.rememberTintePortada

// ─── ARTISTA ── su página de YouTube Music (estructura de YT Music) ─────────
// Cabecera con la foto a sangre, Mix y Play; "Más populares" en cuadrícula de 4
// filas con "Reproducir todo"; carruseles con los títulos que da YT Music
// (álbumes, sencillos, playlists, artistas parecidos); lo de tu biblioteca y la
// biografía. Fondo teñido con el color de la foto (idea de SimpMusic).
@Composable
fun ArtistScreen(artistId: String, browseId: String?, navController: NavController, playerViewModel: PlayerViewModel) {
    val vm: ArtistViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    LaunchedEffect(artistId, browseId) { vm.cargar(artistId, browseId) }
    val estado by vm.estado.collectAsStateWithLifecycle()
    val biblioteca by vm.deTuBiblioteca.collectAsStateWithLifecycle()
    val ajustes by settingsVm.settings.collectAsStateWithLifecycle()
    val pagina = (estado as? ArtistViewModel.Estado.Listo)?.pagina
    val tinte by rememberTintePortada(pagina?.fotoUrl, ajustes.blurPlayerBg)
    val fondo = MaterialTheme.colorScheme.background
    val lista = rememberLazyListState()
    // La barra superior muestra el nombre cuando la cabecera ya pasó.
    val barraPlegada by remember { derivedStateOf { lista.firstVisibleItemIndex > 0 } }
    val abrirReproductor = { playerViewModel.abrirReproductor() }
    // "De tu biblioteca" enseña las 5 más escuchadas; el botón despliega el resto aquí.
    var bibliotecaEntera by rememberSaveable(artistId) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tinte, 0.7f to fondo))) {
        LazyColumn(state = lista, contentPadding = PaddingValues(bottom = 160.dp)) {
            item(key = "cabecera") {
                CabeceraArtista(pagina, artistId, tinte, playerViewModel, abrirReproductor)
            }
            when (val e = estado) {
                ArtistViewModel.Estado.Cargando -> item(key = "cargando") {
                    Box(Modifier.animateItem().fillMaxWidth().padding(40.dp), Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                is ArtistViewModel.Estado.Error -> item(key = "error") {
                    Box(Modifier.animateItem()) {
                        AvisoBusqueda(if (e.sinConexion) SearchViewModel.Aviso.SIN_CONEXION else SearchViewModel.Aviso.ERROR) {
                            vm.reintentar()
                        }
                    }
                }
                else -> {}
            }
            pagina?.let { p ->
                if (p.populares.isNotEmpty()) {
                    item(key = "populares") {
                        Column(Modifier.animateItem()) { MasPopulares(p, playerViewModel, navController, abrirReproductor) }
                    }
                }
                p.secciones.forEachIndexed { i, s ->
                    item(key = "seccion$i") {
                        Column(Modifier.animateItem()) {
                            TituloSeccion(s.titulo)
                            CarruselColecciones(s.items) { item ->
                                navController.navigate(
                                    if (item.esArtista) Screen.Artist.createRoute(item.titulo, item.browseId)
                                    else Screen.Coleccion.createRoute(item.browseId)
                                )
                            }
                        }
                    }
                }
            }
            if (biblioteca.isNotEmpty()) {
                item(key = "biblioteca") { TituloSeccion(stringResource(R.string.artista_de_tu_biblioteca)) }
                val visibles = if (bibliotecaEntera) biblioteca else biblioteca.take(5)
                itemsIndexed(visibles, key = { _, c -> "b" + c.id }) { i, c ->
                    SearchResultRow(item = c.toSearchItem(), enTarjeta = true, onPlay = {
                        playerViewModel.reproducirCola(biblioteca, i, pagina?.nombre ?: artistId)
                        abrirReproductor()
                    })
                }
                if (biblioteca.size > 5) item(key = "bibliotecaMas") {
                    TextButton(onClick = { bibliotecaEntera = !bibliotecaEntera }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(if (bibliotecaEntera) stringResource(R.string.artista_mostrar_menos)
                             else stringResource(R.string.artista_mostrar_todas, biblioteca.size))
                    }
                }
            }
            if (estado == ArtistViewModel.Estado.SinPagina && biblioteca.isEmpty()) item {
                AvisoBusqueda(SearchViewModel.Aviso.SIN_RESULTADOS) {}
            }
            pagina?.biografia?.takeIf { it.isNotBlank() }?.let { bio ->
                item(key = "bio") { MasInformacion(bio) }
            }
        }

        // Barra superior: ← siempre; al plegarse, fondo y nombre del artista.
        Row(
            Modifier.fillMaxWidth()
                .background(if (barraPlegada) tinte else Color.Transparent)
                .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (barraPlegada) Color.Transparent else Color.Black.copy(alpha = 0.35f))) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accion_atras), tint = Color.White)
            }
            AnimatedVisibility(barraPlegada, enter = fadeIn(), exit = fadeOut()) {
                Text(pagina?.nombre ?: artistId, color = Color.White, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CabeceraArtista(
    p: PaginaArtista?, nombre: String, tinte: Color,
    playerViewModel: PlayerViewModel, abrirReproductor: () -> Unit
) {
    Box(Modifier.fillMaxWidth().height(380.dp)) {
        if (p != null && p.fotoUrl.isNotBlank()) {
            AsyncImage(conReintento(p.fotoUrl), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.25f), 0.3f to Color.Transparent, 1f to tinte)))
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(Modifier.weight(1f)) {
                // Se encoge (hasta 24 sp) antes que partir una palabra: "beabadoobe / e".
                Text(p?.nombre ?: nombre, color = Color.White, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.displayMedium.copy(lineHeight = 1.1.em),
                    autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 40.sp),
                    // Una sola palabra va en una línea (si no, se partiría y "cabría" igual).
                    maxLines = if ((p?.nombre ?: nombre).trim().contains(' ')) 2 else 1,
                    overflow = TextOverflow.Ellipsis)
                if (!p?.oyentes.isNullOrBlank()) {
                    Text(p!!.oyentes, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            val cargando by playerViewModel.cargandoListaYtm.collectAsStateWithLifecycle()
            p?.mix?.let { mix ->
                BotonLista(mix, cargando, Icons.Default.Sensors, R.string.artista_mix, grande = false) {
                    playerViewModel.reproducirListaYtm(mix, p.nombre) { abrirReproductor() }
                }
                Spacer(Modifier.width(12.dp))
            }
            // Como en YT Music, el play grande del artista reproduce sus canciones en aleatorio.
            p?.aleatorio?.let { al ->
                BotonLista(al, cargando, Icons.Default.PlayArrow, R.string.artista_reproducir, grande = true) {
                    playerViewModel.reproducirListaYtm(al, p.nombre) { abrirReproductor() }
                }
            }
        }
    }
}

// Mix (círculo con borde) o Play (cuadrado redondeado con el acento); ruedita mientras carga.
@Composable
private fun BotonLista(
    lista: PlaylistRef, cargando: String?, icono: ImageVector, descripcion: Int,
    grande: Boolean, onClick: () -> Unit
) {
    val tam = if (grande) 64.dp else 48.dp
    val forma = if (grande) RoundedCornerShape(20.dp) else CircleShape
    val color = if (grande) MaterialTheme.colorScheme.onPrimary else Color.White
    Box(
        Modifier.size(tam).clip(forma)
            .then(if (grande) Modifier.background(MaterialTheme.colorScheme.primary)
                  else Modifier.border(1.5.dp, Color.White.copy(alpha = 0.35f), forma))
            .clickable(enabled = cargando == null, onClick = onClick),
        Alignment.Center
    ) {
        if (cargando == lista.playlistId) CircularProgressIndicator(Modifier.size(22.dp), color = color, strokeWidth = 2.dp)
        else Icon(icono, stringResource(descripcion), Modifier.size(if (grande) 30.dp else 22.dp), tint = color)
    }
}

@Composable
private fun TituloSeccion(titulo: String, extra: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        extra()
    }
}

// "Más populares": cuadrícula de 4 filas que se desliza en horizontal (como en YT
// Music), con "▶ Reproducir todo" (su lista completa) y › (abre esa lista).
@Composable
private fun MasPopulares(
    p: PaginaArtista, playerViewModel: PlayerViewModel,
    navController: NavController, abrirReproductor: () -> Unit
) {
    val cargando by playerViewModel.cargandoListaYtm.collectAsStateWithLifecycle()
    val clave = p.todasId ?: "respaldo"
    val origen = stringResource(R.string.cola_origen_populares, p.nombre)
    TituloSeccion(stringResource(R.string.artista_mas_populares)) {
        OutlinedButton(
            onClick = { playerViewModel.reproducirColeccionYtm(p.todasId, p.populares, origen) { abrirReproductor() } },
            enabled = cargando == null,
            contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp)
        ) {
            if (cargando == clave) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.artista_reproducir_todo), maxLines = 1, softWrap = false)
        }
        p.todasId?.let { id ->
            IconButton(onClick = { navController.navigate(Screen.Coleccion.createRoute(id)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.artista_ver_todas))
            }
        }
    }
    val ancho = LocalConfiguration.current.screenWidthDp.dp - 56.dp
    val filas = minOf(4, p.populares.size)
    LazyHorizontalGrid(
        rows = GridCells.Fixed(filas),
        modifier = Modifier.fillMaxWidth().height(72.dp * filas),
        contentPadding = PaddingValues(horizontal = 0.dp)
    ) {
        itemsIndexed(p.populares, key = { _, c -> c.videoId }) { i, c ->
            Box(Modifier.width(ancho)) {
                SearchResultRow(item = c, enTarjeta = true, onPlay = {
                    playerViewModel.reproducirCola(p.populares.map { it.toSongEntity() }, i, origen)
                    abrirReproductor()
                })
            }
        }
    }
}

// Carrusel de álbumes, sencillos o playlists (portada cuadrada) o de artistas (círculo).
@Composable
private fun CarruselColecciones(items: List<ItemColeccion>, onClick: (ItemColeccion) -> Unit) {
    val artistas = items.firstOrNull()?.esArtista == true
    val tam = if (artistas) 120.dp else 150.dp
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(if (artistas) 16.dp else 12.dp)) {
        items(items, key = { it.browseId }) { item ->
            Column(
                // Solo se recorta la imagen: recortar la columna se comía la esquina del texto.
                Modifier.width(tam).clickable { onClick(item) },
                horizontalAlignment = if (artistas) Alignment.CenterHorizontally else Alignment.Start
            ) {
                AsyncImage(conReintento(mejorarCaratula(item.portadaUrl)), null,
                    Modifier.size(tam).clip(if (artistas) CircleShape else RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop)
                Text(item.titulo, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(item.subtitulo, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MasInformacion(bio: String) {
    var abierta by rememberSaveable { mutableStateOf(false) }
    TituloSeccion(stringResource(R.string.artista_mas_informacion))
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(bio, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (abierta) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { abierta = !abierta }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(if (abierta) R.string.artista_leer_menos else R.string.artista_leer_mas))
        }
    }
}
