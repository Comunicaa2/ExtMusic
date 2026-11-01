package com.auramusic.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auramusic.app.data.db.PlaylistDao
import com.auramusic.app.data.db.PlaylistEntity
import com.auramusic.app.data.db.PlaylistSongCrossRef
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.data.repository.ImportadorPlaylists
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val importador: ImportadorPlaylists
) : ViewModel() {

    val playlists = playlistDao.getAll()

    // ── Importar playlist pública de YouTube por enlace ─────────────────────
    sealed interface EstadoImportacion {
        data object Inactivo : EstadoImportacion
        data object Importando : EstadoImportacion
        data class Hecho(val nombre: String, val canciones: Int) : EstadoImportacion
        data class Error(val enlaceNoValido: Boolean) : EstadoImportacion
    }

    private val _importacion = MutableStateFlow<EstadoImportacion>(EstadoImportacion.Inactivo)
    val importacion: StateFlow<EstadoImportacion> = _importacion

    fun importarDeYoutube(enlace: String) {
        if (_importacion.value == EstadoImportacion.Importando) return
        _importacion.value = EstadoImportacion.Importando
        viewModelScope.launch {
            _importacion.value = runCatching { importador.importar(enlace) }.fold(
                onSuccess = { EstadoImportacion.Hecho(it.nombre, it.canciones) },
                onFailure = { EstadoImportacion.Error(it is ImportadorPlaylists.EnlaceNoValido) }
            )
        }
    }

    fun cerrarImportacion() { _importacion.value = EstadoImportacion.Inactivo }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            playlistDao.insert(PlaylistEntity(id = UUID.randomUUID().toString(), name = name))
        }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch { playlistDao.delete(playlist) }
    }

    /** [alTerminar] recibe false si la canción ya estaba en la playlist. */
    fun addSongToPlaylist(playlistId: String, songId: String, alTerminar: (Boolean) -> Unit = {}) {
        viewModelScope.launch { alTerminar(playlistDao.anadirAlFinal(playlistId, songId)) }
    }

    /** BB: crea una playlist nueva y le añade la canción de una vez. */
    fun crearYAgregar(nombre: String, songId: String) {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            playlistDao.insert(PlaylistEntity(id = id, name = nombre))
            playlistDao.anadirAlFinal(id, songId)
        }
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch { playlistDao.removeSongFromPlaylist(playlistId, songId) }
    }

    fun getPlaylistSongs(playlistId: String) = playlistDao.getPlaylistSongs(playlistId)

    /**
     * Carga las canciones de una playlist UNA sola vez (no como Flow) y las
     * entrega por callback para encolarlas en el reproductor (Bug 2).
     */
    fun cargarCanciones(playlistId: String, onCargado: (List<SongEntity>) -> Unit) {
        viewModelScope.launch {
            val canciones = playlistDao.getPlaylistSongs(playlistId).first()
            onCargado(canciones)
        }
    }

    /** Nombre de la playlist (para la cabecera y el texto a compartir). Mejora 9. */
    fun nombrePlaylist(playlistId: String, onNombre: (String) -> Unit) {
        viewModelScope.launch { onNombre(playlistDao.getById(playlistId)?.name ?: "") }
    }

    /**
     * Reordena la playlist persistiendo la nueva posición de cada canción (Mejora 9).
     * El crossref tiene PK (playlistId, songId) con REPLACE, así que reinsertar con
     * la posición nueva actualiza el orden.
     */
    fun reordenar(playlistId: String, cancionesEnOrden: List<SongEntity>) {
        viewModelScope.launch {
            cancionesEnOrden.forEachIndexed { i, c ->
                playlistDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, c.id, i))
            }
        }
    }
}
