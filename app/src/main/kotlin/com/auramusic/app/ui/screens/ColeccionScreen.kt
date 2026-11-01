package com.auramusic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import com.auramusic.app.data.api.PaginaColeccion
import com.auramusic.app.ui.components.conReintento
import com.auramusic.app.ui.navigation.Screen
import com.auramusic.app.ui.theme.rememberTintePortada

// ─── ÁLBUM / PLAYLIST de YouTube Music ───────────────────────────────────────
// Portada a sangre, título, artista (se puede tocar) • tipo • año; Descargar,
// Guardar (como playlist local), Aleatorio y Play; pistas sin números y con su
// miniatura (decisión del usuario); al pie, "N canciones • M minutos".
@Composable
fun ColeccionScreen(browseId: String, navController: NavController, playerViewModel: PlayerViewModel) {
    val vm: ColeccionViewModel = hiltViewModel()
    val downloadVm: DownloadViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    LaunchedEffect(browseId) { vm.cargar(browseId) }
    val estado by vm.estado.collectAsStateWithLifecycle()
    val guardada by vm.guardadaComo.collectAsStateWithLifecycle()
    val descargadas by downloadVm.downloadedSongs.collectAsStateWithLifecycle(emptyList())
    val ajustes by settingsVm.settings.collectAsStateWithLifecycle()
    val pagina = (estado as? ColeccionViewModel.Estado.Listo)?.pagina
    val tinte by rememberTintePortada(pagina?.portadaUrl, ajustes.blurPlayerBg)
    val fondo = MaterialTheme.colorScheme.background
    val abrirReproductor = { playerViewModel.abrirReproductor() }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tinte, 0.8f to fondo))) {
        LazyColumn(contentPadding = PaddingValues(bottom = 160.dp)) {
            item(key = "cabecera") {
                Box(Modifier.fillMaxWidth().height(330.dp)) {
                    pagina?.portadaUrl?.takeIf { it.isNotBlank() }?.let {
                        AsyncImage(conReintento(mejorarCaratula(it)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.2f), 0.35f to Color.Transparent, 1f to tinte)))
                }
            }
            when (val e = estado) {
                ColeccionViewModel.Estado.Cargando -> item(key = "cargando") {
                    Box(Modifier.animateItem().fillMaxWidth().padding(40.dp), Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                is ColeccionViewModel.Estado.Error -> item(key = "error") {
                    Box(Modifier.animateItem()) {
                        AvisoBusqueda(if (e.sinConexion) SearchViewModel.Aviso.SIN_CONEXION else SearchViewModel.Aviso.ERROR) {
                            vm.reintentar()
                        }
                    }
                }
                is ColeccionViewModel.Estado.Listo -> {
                    val p = e.pagina
                    item(key = "titulo") { Box(Modifier.animateItem()) { TituloColeccion(p, navController) } }
                    item(key = "botones") {
                        val todasDescargadas = p.canciones.isNotEmpty() &&
                            p.canciones.all { c -> descargadas.any { it.id == c.videoId } }
                        BotonesColeccion(
                            descargada = todasDescargadas, guardada = guardada != null,
                            onDescargar = { p.canciones.forEach { downloadVm.downloadSong(it) } },
                            onGuardar = { vm.guardarComoPlaylist(p) },
                            onAleatorio = {
                                playerViewModel.reproducirCola(p.canciones.shuffled().map { it.toSongEntity() }, 0, p.titulo)
                                abrirReproductor()
                            },
                            onPlay = {
                                playerViewModel.reproducirCola(p.canciones.map { it.toSongEntity() }, 0, p.titulo)
                                abrirReproductor()
                            }
                        )
                    }
                    guardada?.let { nombre ->
                        item(key = "guardada") {
                            Text(stringResource(R.string.coleccion_guardada, nombre), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                    itemsIndexed(p.canciones, key = { _, c -> c.videoId }) { i, c ->
                        Box(Modifier.animateItem()) {
                            SearchResultRow(item = c, enTarjeta = true, onPlay = {
                                playerViewModel.reproducirCola(p.canciones.map { it.toSongEntity() }, i, p.titulo)
                                abrirReproductor()
                            })
                        }
                    }
                    if (p.pie.isNotBlank()) item(key = "pie") {
                        Text(p.pie, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
                    }
                }
            }
        }
        // Fondo oscuro translúcido: la flecha blanca se ve también sobre portadas claras.
        IconButton(onClick = { navController.popBackStack() },
            modifier = Modifier.statusBarsPadding().padding(4.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.35f))) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accion_atras), tint = Color.White)
        }
    }
}

@Composable
private fun TituloColeccion(p: PaginaColeccion, navController: NavController) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(p.titulo, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, color = Color.White)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.autor.isNotBlank()) {
                // El artista se puede tocar: abre su página.
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = p.autorId != null) {
                        navController.navigate(Screen.Artist.createRoute(p.autor, p.autorId))
                    }.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (p.autorFotoUrl.isNotBlank()) {
                        AsyncImage(conReintento(p.autorFotoUrl), null, Modifier.size(24.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop)
                    }
                    Text(p.autor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (p.subtitulo.isNotBlank()) {
                Text((if (p.autor.isNotBlank()) "• " else "") + p.subtitulo, style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BotonesColeccion(
    descargada: Boolean, guardada: Boolean,
    onDescargar: () -> Unit, onGuardar: () -> Unit, onAleatorio: () -> Unit, onPlay: () -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BotonRedondo(if (descargada) Icons.Default.DownloadDone else Icons.Default.Download,
            R.string.coleccion_descargar, enabled = !descargada, onClick = onDescargar)
        Spacer(Modifier.width(10.dp))
        BotonRedondo(if (guardada) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
            R.string.coleccion_guardar, enabled = !guardada, onClick = onGuardar)
        Spacer(Modifier.width(10.dp))
        BotonRedondo(Icons.Default.Shuffle, R.string.accion_aleatorio, onClick = onAleatorio)
        Spacer(Modifier.weight(1f))
        FilledIconButton(onClick = onPlay, shape = RoundedCornerShape(20.dp), modifier = Modifier.size(64.dp)) {
            Icon(Icons.Default.PlayArrow, stringResource(R.string.coleccion_reproducir), Modifier.size(30.dp))
        }
    }
}

@Composable
private fun BotonRedondo(icono: ImageVector, descripcion: Int, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icono, stringResource(descripcion), Modifier.size(22.dp))
    }
}
