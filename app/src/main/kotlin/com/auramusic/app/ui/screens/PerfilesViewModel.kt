package com.auramusic.app.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auramusic.app.data.db.MAX_PERFILES
import com.auramusic.app.data.db.PerfilDao
import com.auramusic.app.data.db.PerfilEntity
import com.auramusic.app.data.repository.ArchivosPerfil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Perfiles de gusto: elegir el activo, crear, renombrar y borrar (ver PerfilEntity). */
@HiltViewModel
class PerfilesViewModel @Inject constructor(
    @ApplicationContext private val contexto: Context,
    private val perfilDao: PerfilDao,
    private val songDao: com.auramusic.app.data.db.SongDao
) : ViewModel() {

    val perfiles: StateFlow<List<PerfilEntity>> =
        perfilDao.todos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activo: StateFlow<PerfilEntity?> =
        perfilDao.activo().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** "Canciones no recomendadas" del perfil activo (Ajustes), para devolverlas. */
    val noRecomendadas: StateFlow<List<com.auramusic.app.data.db.SongEntity>> =
        songDao.noRecomendadas().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun volverARecomendar(id: String) {
        viewModelScope.launch { songDao.setNoRecomendar(id, false) }
    }

    fun cambiarA(id: String) {
        viewModelScope.launch { perfilDao.cambiarA(id) }
    }

    /** Crea un perfil (vacío) y pasa a él. */
    fun crear(nombre: String) {
        viewModelScope.launch {
            if (perfilDao.lista().size >= MAX_PERFILES) return@launch
            val id = "p" + UUID.randomUUID().toString().replace("-", "").take(10)
            perfilDao.crear(PerfilEntity(id, limpiar(nombre), System.currentTimeMillis(), activo = false))
            perfilDao.cambiarA(id)
        }
    }

    fun renombrar(id: String, nombre: String) {
        viewModelScope.launch { perfilDao.renombrar(id, limpiar(nombre)) }
    }

    /** El principal y el activo no se borran (el DAO lo comprueba también). */
    fun borrar(id: String) {
        viewModelScope.launch { if (perfilDao.borrar(id)) ArchivosPerfil.borrar(contexto, id) }
    }

    private fun limpiar(nombre: String) = nombre.trim().take(40).ifBlank { "Perfil" }
}
