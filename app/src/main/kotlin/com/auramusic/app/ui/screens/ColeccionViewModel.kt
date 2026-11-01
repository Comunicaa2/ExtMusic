package com.auramusic.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.auramusic.app.data.api.InnertubeClient
import com.auramusic.app.data.api.PaginaColeccion
import com.auramusic.app.data.db.AuraDatabase
import com.auramusic.app.data.db.PlaylistEntity
import com.auramusic.app.data.db.PlaylistSongCrossRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Álbum o playlist de YouTube Music: pistas, y guardarlo como playlist local. */
@HiltViewModel
class ColeccionViewModel @Inject constructor(
    private val innertube: InnertubeClient,
    private val db: AuraDatabase
) : ViewModel() {

    sealed interface Estado {
        data object Cargando : Estado
        data class Listo(val pagina: PaginaColeccion) : Estado
        data class Error(val sinConexion: Boolean) : Estado
    }

    val estado = MutableStateFlow<Estado>(Estado.Cargando)
    /** null = sin guardar en esta visita; si no, el nombre con que se guardó. */
    val guardadaComo = MutableStateFlow<String?>(null)
    private var cargado: String? = null

    fun cargar(browseId: String, forzar: Boolean = false) {
        if (!forzar && cargado == browseId) return
        cargado = browseId
        viewModelScope.launch {
            estado.value = Estado.Cargando
            innertube.coleccion(browseId)
                .onSuccess { estado.value = Estado.Listo(it); guardadaComo.value = yaGuardada(it) }
                .onFailure { e -> estado.value = Estado.Error(e is java.io.IOException) }
        }
    }

    // Si ya hay una playlist local con ese nombre y las mismas canciones, "Guardar"
    // sale como hecho: así volver a la pantalla no crea un duplicado.
    private suspend fun yaGuardada(p: PaginaColeccion): String? {
        val ids = p.canciones.map { it.videoId }
        if (ids.isEmpty()) return null
        val dao = db.playlistDao()
        val relaciones = dao.todasLasRelaciones().groupBy { it.playlistId }
        return dao.todas().firstOrNull { pl ->
            pl.name == p.titulo && relaciones[pl.id].orEmpty().sortedBy { it.position }.map { it.songId } == ids
        }?.name
    }

    fun reintentar() { cargado?.let { cargar(it, forzar = true) } }

    /**
     * "Guardar": playlist local con las pistas en su orden. Las canciones se
     * guardan antes (la relación lo exige) sin pisar las que ya estaban (likes,
     * descargas, conteos).
     */
    fun guardarComoPlaylist(p: PaginaColeccion) {
        if (guardadaComo.value != null || p.canciones.isEmpty()) return
        viewModelScope.launch {
            val songDao = db.songDao()
            val playlistDao = db.playlistDao()
            db.withTransaction {
                val existentes = songDao.todosLosIds().toSet()
                songDao.upsert(p.canciones.filter { it.videoId !in existentes }.map { it.toSongEntity() })
                val id = UUID.randomUUID().toString()
                playlistDao.insert(PlaylistEntity(id = id, name = p.titulo))
                playlistDao.relacionarSiNoExisten(p.canciones.mapIndexed { i, c ->
                    PlaylistSongCrossRef(playlistId = id, songId = c.videoId, position = i)
                })
            }
            guardadaComo.value = p.titulo
        }
    }
}
