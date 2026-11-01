package com.auramusic.app.ui.screens

import com.auramusic.app.ui.components.conReintento
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.auramusic.app.R
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.ui.navigation.Screen

/**
 * Vista interna de una playlist (Mejora 9): lista de canciones (carátula/título/
 * artista), botones de reproducir en orden y aleatorio, compartir (texto) y
 * reordenar por drag & drop (long-press en el asa y arrastrar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(playlistId: String, navController: NavController, playerViewModel: PlayerViewModel) {
    val vm: PlaylistViewModel = hiltViewModel()
    val ctx = LocalContext.current
    // Me gusta / Descargas son playlists automáticas: sus canciones salen de la
    // BD al momento, van por título y no se reordenan.
    val automatica = playlistId == Screen.PlaylistDetail.ME_GUSTA || playlistId == Screen.PlaylistDetail.DESCARGAS
    val origen = remember(playlistId) {
        when (playlistId) {
            Screen.PlaylistDetail.ME_GUSTA  -> playerViewModel.likedSongs
            Screen.PlaylistDetail.DESCARGAS -> playerViewModel.downloadedSongs
            else -> vm.getPlaylistSongs(playlistId)
        }
    }
    val cancionesBd by origen.collectAsStateWithLifecycle(emptyList())
    var nombreBd by remember { mutableStateOf("") }
    LaunchedEffect(playlistId) { if (!automatica) vm.nombrePlaylist(playlistId) { nombreBd = it } }
    val nombre = when (playlistId) {
        Screen.PlaylistDetail.ME_GUSTA  -> stringResource(R.string.biblioteca_me_gusta)
        Screen.PlaylistDetail.DESCARGAS -> stringResource(R.string.biblioteca_descargas)
        else -> nombreBd
    }

    // Lista local editable para reordenar; se resincroniza con la BD solo cuando
    // cambia el conjunto de canciones (no durante un arrastre).
    val canciones = remember { mutableStateListOf<SongEntity>() }
    LaunchedEffect(cancionesBd) {
        if (automatica || canciones.map { it.id }.toSet() != cancionesBd.map { it.id }.toSet()) {
            canciones.clear(); canciones.addAll(cancionesBd)
        }
    }

    val density = LocalDensity.current
    val alturaFilaPx = with(density) { 64.dp.toPx() }
    var arrastrando by remember { mutableIntStateOf(-1) }
    var desplazamiento by remember { mutableFloatStateOf(0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(nombre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accion_atras))
                    }
                },
                actions = {
                    IconButton(onClick = { compartirPlaylist(ctx, nombre, canciones) }) {
                        Icon(Icons.Default.Share, stringResource(R.string.accion_compartir))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 160.dp)) {
            // Botones: reproducir en orden / aleatoriamente.
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (canciones.isNotEmpty()) {
                                playerViewModel.reproducirCola(canciones.toList(), 0, nombre)
                                playerViewModel.abrirReproductor()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.playlist_reproducir_orden), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            if (canciones.isNotEmpty()) {
                                playerViewModel.reproducirCola(canciones.shuffled(), 0, nombre)
                                playerViewModel.abrirReproductor()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.playlist_reproducir_aleatorio), maxLines = 1)
                    }
                }
            }
            if (canciones.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                        Text(stringResource(R.string.playlist_vacia),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(canciones, key = { _, s -> s.id }) { index, song ->
                Row(
                    Modifier
                        .animateItem(placementSpec = null)
                        .fillMaxWidth()
                        .height(64.dp)
                        .zIndex(if (index == arrastrando) 1f else 0f)
                        .graphicsLayer { translationY = if (index == arrastrando) desplazamiento else 0f }
                        .background(
                            if (index == arrastrando) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable {
                            playerViewModel.reproducirCola(canciones.toList(), index, nombre)
                            playerViewModel.abrirReproductor()
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.size(44.dp)) {
                        AsyncImage(
                            conReintento(mejorarCaratula(song.thumbnailUrl)), null,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(
                            song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Asa de arrastre: mantener pulsado y arrastrar para reordenar.
                    if (!automatica) Icon(
                        Icons.Default.DragHandle, stringResource(R.string.reordenar),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.pointerInput(song.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { arrastrando = canciones.indexOf(song); desplazamiento = 0f },
                                onDragEnd = {
                                    vm.reordenar(playlistId, canciones.toList())
                                    arrastrando = -1; desplazamiento = 0f
                                },
                                onDragCancel = { arrastrando = -1; desplazamiento = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    desplazamiento += amount.y
                                    val actual = arrastrando
                                    if (actual >= 0) {
                                        if (desplazamiento > alturaFilaPx && actual < canciones.lastIndex) {
                                            canciones.add(actual + 1, canciones.removeAt(actual))
                                            arrastrando = actual + 1; desplazamiento -= alturaFilaPx
                                        } else if (desplazamiento < -alturaFilaPx && actual > 0) {
                                            canciones.add(actual - 1, canciones.removeAt(actual))
                                            arrastrando = actual - 1; desplazamiento += alturaFilaPx
                                        }
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

/** Comparte la playlist como texto (nombre + lista numerada) por cualquier app. */
private fun compartirPlaylist(ctx: Context, nombre: String, canciones: List<SongEntity>) {
    val texto = buildString {
        appendLine(nombre)
        canciones.forEachIndexed { i, s -> appendLine("${i + 1}. ${s.title} — ${s.artist}") }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, nombre)
        putExtra(Intent.EXTRA_TEXT, texto)
    }
    ctx.startActivity(Intent.createChooser(intent, null))
}
