package com.auramusic.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.data.repository.BibliotecaLocalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estados posibles del escaneo de la biblioteca local del teléfono. */
sealed interface EstadoBibliotecaLocal {
    data object Inicial    : EstadoBibliotecaLocal   // aún no se pidió permiso ni se escaneó
    data object Cargando   : EstadoBibliotecaLocal
    data object SinPermiso : EstadoBibliotecaLocal
    data class  Lista(val canciones: List<SongEntity>) : EstadoBibliotecaLocal
}

@HiltViewModel
class BibliotecaLocalViewModel @Inject constructor(
    private val repositorio: BibliotecaLocalRepository
) : ViewModel() {

    private val _estado = MutableStateFlow<EstadoBibliotecaLocal>(EstadoBibliotecaLocal.Inicial)
    val estado: StateFlow<EstadoBibliotecaLocal> = _estado.asStateFlow()

    /** Escanea la biblioteca. Llamar solo cuando el permiso esté concedido. */
    fun escanear() {
        _estado.value = EstadoBibliotecaLocal.Cargando
        viewModelScope.launch {
            val canciones = repositorio.escanearCanciones()
            _estado.value = EstadoBibliotecaLocal.Lista(canciones)
        }
    }

    /** Marca que el usuario denegó el permiso (sin crashear). */
    fun marcarSinPermiso() { _estado.value = EstadoBibliotecaLocal.SinPermiso }
}
