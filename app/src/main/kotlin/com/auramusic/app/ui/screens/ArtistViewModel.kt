package com.auramusic.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auramusic.app.data.api.InnertubeClient
import com.auramusic.app.data.api.PaginaArtista
import com.auramusic.app.data.db.SongDao
import com.auramusic.app.data.db.SongEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Página del artista con su catálogo real de YouTube Music (por su browseId). Antes
 * se buscaba el nombre como texto y salían canciones de otros artistas.
 * Si solo se sabe el nombre (biblioteca, reproductor), se busca su browseId; si no
 * aparece en YT Music, la página muestra solo lo que hay de él en la biblioteca.
 */
@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val innertube: InnertubeClient,
    songDao: SongDao
) : ViewModel() {

    sealed interface Estado {
        data object Cargando : Estado
        data class Listo(val pagina: PaginaArtista) : Estado
        data object SinPagina : Estado            // no está en YT Music: solo la biblioteca
        data class Error(val sinConexion: Boolean) : Estado
    }

    val estado = MutableStateFlow<Estado>(Estado.Cargando)
    private val nombre = MutableStateFlow("")
    private val idPagina = MutableStateFlow<String?>(null)

    /** "De tu biblioteca": canciones guardadas de este artista (por su id o su nombre). */
    val deTuBiblioteca: StateFlow<List<SongEntity>> =
        combine(songDao.getAll(), nombre, idPagina) { todas, n, id ->
            if (n.isBlank()) emptyList()
            else todas.filter { c ->
                (id != null && c.artistId == id) || extractMainArtist(c.artist).equals(n, ignoreCase = true)
            }.sortedByDescending { it.playCount }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var pedido: Pair<String, String?>? = null

    fun cargar(nombreArtista: String, browseId: String?, forzar: Boolean = false) {
        if (!forzar && pedido == nombreArtista to browseId) return
        pedido = nombreArtista to browseId
        nombre.value = nombreArtista
        viewModelScope.launch {
            estado.value = Estado.Cargando
            val id = browseId?.takeIf { it.isNotBlank() } ?: innertube.idArtista(nombreArtista).getOrElse { e ->
                estado.value = Estado.Error(e is java.io.IOException); return@launch
            }
            if (id == null) { estado.value = Estado.SinPagina; return@launch }
            idPagina.value = id
            innertube.artista(id)
                .onSuccess { estado.value = Estado.Listo(it); nombre.value = it.nombre }
                .onFailure { e -> estado.value = Estado.Error(e is java.io.IOException) }
        }
    }

    fun reintentar() {
        val (n, id) = pedido ?: return
        cargar(n, id, forzar = true)
    }
}
