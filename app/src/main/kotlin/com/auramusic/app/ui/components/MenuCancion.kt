package com.auramusic.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.auramusic.app.R
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.domain.model.OrigenCancion
import com.auramusic.app.ui.navigation.Screen
import com.auramusic.app.ui.screens.AddToPlaylistDialog
import com.auramusic.app.ui.screens.DownloadStatus
import com.auramusic.app.ui.screens.DownloadViewModel
import com.auramusic.app.ui.screens.PlayerViewModel
import com.auramusic.app.ui.screens.PlaylistViewModel
import com.auramusic.app.ui.screens.extractMainArtist
import com.auramusic.app.ui.screens.toSearchItem

/**
 * ⋮ de una canción (como en YT Music): reproducir a continuación, añadir a la cola,
 * descargar o quitar la descarga, añadir a una playlist e ir al artista.
 */
@Composable
fun MenuCancion(cancion: SongEntity, playerViewModel: PlayerViewModel, navController: NavController) {
    var abierto by remember { mutableStateOf(false) }
    var elegirPlaylist by remember { mutableStateOf(false) }
    val contexto = LocalContext.current
    val recursos = LocalResources.current
    val avisar = { texto: String -> Toast.makeText(contexto, texto, Toast.LENGTH_SHORT).show() }

    Box {
        IconButton(onClick = { abierto = true }) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.player_mas_opciones))
        }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_reproducir_despues)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                onClick = {
                    abierto = false
                    playerViewModel.reproducirDespues(cancion)
                    avisar(recursos.getString(R.string.menu_sonara_despues))
                })
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_anadir_cola)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                onClick = {
                    abierto = false
                    playerViewModel.anadirACola(cancion)
                    avisar(recursos.getString(R.string.menu_anadida_cola))
                })
            // Descargar como en el reproductor: las del teléfono no se descargan.
            val downloadVm: DownloadViewModel = hiltViewModel()
            val descargadas by downloadVm.downloadedSongs.collectAsStateWithLifecycle(emptyList())
            val descargas by downloadVm.downloads.collectAsStateWithLifecycle()
            val descargada = cancion.isDownloaded || descargadas.any { it.id == cancion.id }
            val prog = descargas[cancion.id]
            val descargando = prog?.status == DownloadStatus.DOWNLOADING || prog?.status == DownloadStatus.PENDING
            val delTelefono = cancion.origen == OrigenCancion.LOCAL.name && !descargada
            if (!delTelefono) DropdownMenuItem(
                text = {
                    Text(when {
                        descargando -> stringResource(R.string.desc_descargando, prog?.progress ?: 0)
                        descargada  -> stringResource(R.string.desc_eliminar)
                        else        -> stringResource(R.string.accion_descargar)
                    })
                },
                leadingIcon = {
                    Icon(when {
                        descargando -> Icons.Default.Downloading
                        descargada  -> Icons.Default.DeleteOutline
                        else        -> Icons.Default.Download
                    }, null)
                },
                enabled = !descargando,
                onClick = {
                    abierto = false
                    if (descargada) downloadVm.deleteDownload(cancion.id)
                    else downloadVm.downloadSong(cancion.toSearchItem())
                })
            DropdownMenuItem(text = { Text(stringResource(R.string.menu_anadir_playlist)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                onClick = { abierto = false; elegirPlaylist = true })
            if (cancion.artist.isNotBlank()) DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_ir_artista)) },
                leadingIcon = { Icon(Icons.Default.Person, null) },
                onClick = {
                    abierto = false
                    navController.navigate(Screen.Artist.createRoute(extractMainArtist(cancion.artist),
                        cancion.artistId.ifBlank { null }))
                })
        }
    }

    if (elegirPlaylist) {
        val playlistVm: PlaylistViewModel = hiltViewModel()
        val playlists by playlistVm.playlists.collectAsStateWithLifecycle(emptyList())
        // Una canción que nunca sonó aún no está en la BD: se guarda antes de añadirla.
        AddToPlaylistDialog(
            playlists = playlists,
            onSelect = { id ->
                val nombre = playlists.firstOrNull { it.id == id }?.name.orEmpty()
                playerViewModel.guardarSiFalta(cancion) {
                    playlistVm.addSongToPlaylist(id, cancion.id) { anadida ->
                        avisar(recursos.getString(
                            if (anadida) R.string.playlist_anadida else R.string.playlist_ya_estaba, nombre))
                    }
                }
                elegirPlaylist = false
            },
            onCrear = { nombre ->
                playerViewModel.guardarSiFalta(cancion) { playlistVm.crearYAgregar(nombre, cancion.id) }
                avisar(recursos.getString(R.string.playlist_anadida, nombre))
                elegirPlaylist = false
            },
            onDismiss = { elegirPlaylist = false }
        )
    }
}
