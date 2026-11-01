package com.auramusic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as itemsIndexedGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.auramusic.app.R
import com.auramusic.app.data.api.PlaylistRef
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.ui.components.MenuCancion
import com.auramusic.app.ui.components.conReintento
import com.auramusic.app.ui.navigation.Screen

// ─── INICIO ── estructura de YT Music (maqueta de la fase E) ─────────────────
// Barra con el nombre y Ajustes; "Selección rápida" (tus más escuchadas
// intercaladas con nuevas parecidas) con "Reproducir todo" y ⋮; "Escuchar de
// nuevo"; "Me gusta" con "Ver todo"; y carruseles armados con lo que escuchas
// (InicioViewModel): novedades y mixes de tus artistas, más canciones para ti,
// artistas parecidos y playlists donde aparecen. Sin chips ni feed genérico.
@Composable
fun InicioScreen(navController: NavController, playerViewModel: PlayerViewModel) {
    val vm: InicioViewModel = hiltViewModel()
    val estado by vm.estado.collectAsStateWithLifecycle()
    val recientes by vm.recientes.collectAsStateWithLifecycle()
    val meGusta by vm.meGusta.collectAsStateWithLifecycle()
    val recs by playerViewModel.recommendations.collectAsStateWithLifecycle()
    val mias by vm.masEscuchadas.collectAsStateWithLifecycle()
    // Hasta tener lo guardado (base de datos, inicio.json y recomendaciones) solo se
    // ve la barra: antes el primer fotograma decía "no hay música" y luego saltaba.
    val listo by vm.listo.collectAsStateWithLifecycle()
    val recsListas by playerViewModel.recomendacionesListas.collectAsStateWithLifecycle()
    val origenSeleccion = stringResource(R.string.home_seleccion_rapida)
    val origenRecientes = stringResource(R.string.home_escuchar_de_nuevo)
    val origenMeGusta = stringResource(R.string.home_me_gusta)
    val abrirReproductor = { playerViewModel.abrirReproductor() }

    val seleccion = remember(mias, recs) { mezclarSeleccion(mias, recs.map { it.toSongEntity() }) }
    // Las recomendaciones que no entraron en Selección rápida: "Más canciones para ti",
    // tercer carrusel (tras novedades y mixes) para no juntar cuatro de canciones seguidos.
    val masParaTi = stringResource(R.string.home_mas_para_ti)
    val carruseles = remember(estado.secciones, recs, seleccion) {
        val ids = seleccion.map { it.id }.toHashSet()
        val resto = recs.filter { it.videoId !in ids }
        if (resto.isEmpty()) estado.secciones
        else estado.secciones.toMutableList().apply { add(minOf(2, size), SeccionInicio(masParaTi, canciones = resto)) }
    }
    val acento = MaterialTheme.colorScheme.primary
    val recursos = LocalResources.current
    val cargandoMix by playerViewModel.cargandoListaYtm.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        // Degradado suave del acento (Monet) detrás de la barra, como en la maqueta.
        Box(Modifier.fillMaxWidth().height(260.dp)
            .background(Brush.verticalGradient(listOf(acento.copy(alpha = 0.16f), Color.Transparent))))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
            item(key = "barra", contentType = "barra") {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    // launchSingleTop evita apilar varias copias de Ajustes (ver Bug A).
                    IconButton(onClick = { navController.navigate(Screen.Settings.route) { launchSingleTop = true } }) {
                        Icon(Icons.Default.Settings, stringResource(R.string.accion_ajustes),
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (!listo || !recsListas) return@LazyColumn
            if (seleccion.isNotEmpty()) item(key = "seleccion", contentType = "seleccion") {
                Column(Modifier.animateItem()) {
                    TituloInicio(origenSeleccion) {
                        OutlinedButton(
                            onClick = { playerViewModel.reproducirCola(seleccion, 0, origenSeleccion); abrirReproductor() },
                            contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.artista_reproducir_todo), maxLines = 1, softWrap = false)
                        }
                    }
                    SeleccionRapida(seleccion, playerViewModel, navController) { i ->
                        playerViewModel.reproducirCola(seleccion, i, origenSeleccion); abrirReproductor()
                    }
                }
            }
            if (recientes.isNotEmpty()) item(key = "recientes", contentType = "canciones") {
                Column(Modifier.animateItem()) {
                    TituloInicio(origenRecientes)
                    CarruselCanciones(recientes) { i ->
                        playerViewModel.reproducirCola(recientes, i, origenRecientes); abrirReproductor()
                    }
                }
            }
            if (meGusta.isNotEmpty()) item(key = "megusta", contentType = "canciones") {
                Column(Modifier.animateItem()) {
                    TituloInicio(origenMeGusta) {
                        TextButton(onClick = {
                            navController.navigate(Screen.PlaylistDetail.createRoute(Screen.PlaylistDetail.ME_GUSTA))
                        }) { Text(stringResource(R.string.home_ver_todo)) }
                    }
                    val primeras = meGusta.take(MAX_CARRUSEL)
                    CarruselCanciones(primeras) { i ->
                        playerViewModel.reproducirCola(meGusta, i, origenMeGusta); abrirReproductor()
                    }
                }
            }
            carruseles.forEachIndexed { n, s ->
                val tipo = when { s.canciones.isNotEmpty() -> "canciones"; s.mixes.isNotEmpty() -> "mixes"; else -> "colecciones" }
                item(key = "seccion$n:${s.titulo}", contentType = tipo) {
                    Column(Modifier.animateItem()) {
                        TituloInicio(s.titulo)
                        if (s.mixes.isNotEmpty()) {
                            CarruselMixes(s.mixes, cargandoMix) { m ->
                                playerViewModel.reproducirListaYtm(PlaylistRef(m.playlistId, m.params),
                                    recursos.getString(R.string.home_mix_de, m.artista)) { abrirReproductor() }
                            }
                        } else if (s.canciones.isNotEmpty()) {
                            val canciones = remember(s) { s.canciones.map { it.toSongEntity() } }
                            CarruselCanciones(canciones) { i ->
                                playerViewModel.reproducirCola(canciones, i, s.titulo); abrirReproductor()
                            }
                        } else {
                            CarruselTarjetas(s.colecciones) { t ->
                                navController.navigate(
                                    if (t.esArtista) Screen.Artist.createRoute(t.titulo, t.browseId)
                                    else Screen.Coleccion.createRoute(t.browseId)
                                )
                            }
                        }
                    }
                }
            }
            if (estado.cargando && carruseles.isEmpty()) item(key = "cargando", contentType = "cargando") {
                Column(Modifier.animateItem()) {
                    Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            val aviso = estado.aviso
            if (aviso != null && carruseles.isEmpty()) item(key = "aviso", contentType = "aviso") {
                Column(Modifier.animateItem()) {
                    AvisoBusqueda(aviso) { vm.reintentar() }
                }
            }
            val vacio = !estado.cargando && aviso == null && carruseles.isEmpty() &&
                seleccion.isEmpty() && recientes.isEmpty() && meGusta.isEmpty()
            if (vacio) item(key = "vacio", contentType = "vacio") {
                Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.MusicNote, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.home_vacio), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { navController.navigate(Screen.Search.route) }) {
                            Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.home_buscar_musica))
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_CARRUSEL = 20

@Composable
private fun TituloInicio(titulo: String, extra: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        extra()
    }
}

// Cuadrícula de 4 filas que se desliza en horizontal; la columna siguiente asoma.
@Composable
private fun SeleccionRapida(
    canciones: List<SongEntity>, playerViewModel: PlayerViewModel,
    navController: NavController, onPlay: (Int) -> Unit
) {
    val ancho = LocalConfiguration.current.screenWidthDp.dp - 56.dp
    val filas = minOf(4, canciones.size)
    LazyHorizontalGrid(
        rows = GridCells.Fixed(filas),
        modifier = Modifier.fillMaxWidth().height(68.dp * filas),
        contentPadding = PaddingValues(start = 16.dp)
    ) {
        itemsIndexedGrid(canciones, key = { _, c -> c.id }, contentType = { _, _ -> "fila" }) { i, c ->
            Row(Modifier.width(ancho).height(68.dp).clickable { onPlay(i) },
                verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(conReintento(mejorarCaratula(c.thumbnailUrl)), null,
                    Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val artista = extractMainArtist(c.artist)
                    Text(if (c.durationMs > 0) "$artista • ${formatMs(c.durationMs)}" else artista,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MenuCancion(c, playerViewModel, navController)
            }
        }
    }
}

// Carrusel de canciones con portada cuadrada (Escuchar de nuevo, Me gusta y el feed).
@Composable
private fun CarruselCanciones(canciones: List<SongEntity>, onPlay: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(canciones, key = { _, c -> c.id }, contentType = { _, _ -> "cancion" }) { i, c ->
            Column(Modifier.width(144.dp).clickable { onPlay(i) }) {
                AsyncImage(conReintento(mejorarCaratula(c.thumbnailUrl)), null,
                    Modifier.size(144.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(extractMainArtist(c.artist), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Álbumes y playlists (portada cuadrada) o artistas (círculo) del feed.
@Composable
private fun CarruselTarjetas(tarjetas: List<TarjetaInicio>, onClick: (TarjetaInicio) -> Unit) {
    val artistas = tarjetas.firstOrNull()?.esArtista == true
    val tam = if (artistas) 120.dp else 144.dp
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (artistas) 16.dp else 12.dp)) {
        items(tarjetas, key = { it.browseId }, contentType = { "tarjeta" }) { t ->
            Column(Modifier.width(tam).clickable { onClick(t) },
                horizontalAlignment = if (artistas) Alignment.CenterHorizontally else Alignment.Start) {
                AsyncImage(conReintento(mejorarCaratula(t.portadaUrl)), null,
                    Modifier.size(tam).clip(if (artistas) CircleShape else RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop)
                Text(t.titulo, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(t.subtitulo, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Mixes de tus artistas: su foto en círculo y "Mix de <artista>"; al tocar, suena su radio.
@Composable
private fun CarruselMixes(mixes: List<MixInicio>, cargando: String?, onClick: (MixInicio) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items(mixes, key = { it.playlistId }, contentType = { "mix" }) { m ->
            Column(Modifier.width(120.dp).clickable(enabled = cargando == null) { onClick(m) },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(120.dp), Alignment.Center) {
                    AsyncImage(conReintento(mejorarCaratula(m.fotoUrl)), null,
                        Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                    if (cargando == m.playlistId) CircularProgressIndicator(Modifier.size(32.dp), color = Color.White)
                }
                Text(stringResource(R.string.home_mix_de, m.artista), style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
