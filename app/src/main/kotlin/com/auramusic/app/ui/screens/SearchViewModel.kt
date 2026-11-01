package com.auramusic.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auramusic.app.data.api.ArtistaItem
import com.auramusic.app.data.api.InnertubeClient
import com.auramusic.app.data.api.ResultadoPrincipal
import com.auramusic.app.data.api.SearchItem
import com.auramusic.app.data.repository.RepositorioPreferencias
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val innertube: InnertubeClient,
    private val prefs: RepositorioPreferencias
) : ViewModel() {

    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<SearchItem>>(emptyList())
    val suggestions = MutableStateFlow<List<String>>(emptyList())
    val isLoading = MutableStateFlow(false)
    // Chips de la búsqueda. TODO = la mezcla (tarjeta + artistas + canciones); al
    // tocar otra vez el chip elegido se vuelve a TODO. Los videos no se muestran nunca.
    enum class Filtro { TODO, CANCIONES, ARTISTAS }
    val filtro = MutableStateFlow(Filtro.TODO)
    // Encima de las canciones, como en YT Music: el resultado más probable
    // (artista con sus 3 canciones top, o una canción) y una fila de artistas.
    val principal = MutableStateFlow<ResultadoPrincipal?>(null)
    val artistas = MutableStateFlow<List<ArtistaItem>>(emptyList())
    // Por qué no hay resultados: antes, sin conexión, la pantalla quedaba en blanco.
    enum class Aviso { NINGUNO, SIN_CONEXION, SIN_RESULTADOS, ERROR }
    val aviso = MutableStateFlow(Aviso.NINGUNO)

    private var searchJob: Job? = null

    init {
        // Sugerencias mientras escribe
        viewModelScope.launch {
            query.debounce(300).collectLatest { q ->
                if (q.length >= 2) {
                    suggestions.value = innertube.getSearchSuggestions(q)
                } else {
                    suggestions.value = emptyList()
                }
            }
        }
    }

    fun search(q: String = query.value) {
        if (q.isBlank()) return
        query.value = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            isLoading.value = true
            aviso.value = Aviso.NINGUNO
            suggestions.value = emptyList()
            val ocultarExplicito = prefs.ajustes.first().ocultarExplicito   // Q
            val f = filtro.value
            if (f == Filtro.ARTISTAS) {
                results.value = emptyList()
                principal.value = null
                innertube.buscarArtistas(q)
                    .onSuccess { artistas.value = it; if (it.isEmpty()) aviso.value = Aviso.SIN_RESULTADOS }
                    .onFailure { e -> artistas.value = emptyList(); aviso.value = avisoDe(e) }
                isLoading.value = false
                return@launch
            }
            // TODO: las dos búsquedas a la vez; si la completa falla, quedan las canciones.
            val todo = if (f == Filtro.TODO) async { innertube.buscarTodo(q).getOrNull() } else null
            innertube.buscar(q)
                .onSuccess { all ->
                    val soloMusica = all.filter { it.isSong }
                    results.value = if (ocultarExplicito) soloMusica.filter { !it.explicit } else soloMusica
                    val extra = todo?.await()
                    principal.value = when (val p = extra?.principal) {
                        is ResultadoPrincipal.Cancion -> when {
                            ocultarExplicito && p.item.explicit -> null
                            p.item.isSong -> p
                            // Casi siempre YT Music pone arriba el videoclip: se cambia
                            // por la canción de la lista que lo iguala.
                            else -> results.value.firstOrNull { esLaCancionDe(it, p.item) }
                                ?.let { ResultadoPrincipal.Cancion(it) }
                        }
                        is ResultadoPrincipal.Artista ->
                            if (ocultarExplicito) p.copy(canciones = p.canciones.filter { !it.explicit }) else p
                        null -> null
                    }
                    // La canción de la tarjeta no se repite en la lista (YT Music a veces
                    // la trae dos veces con distinto id: misma pista, mismo álbum).
                    (principal.value as? ResultadoPrincipal.Cancion)?.item?.let { c ->
                        results.value = results.value.filterNot {
                            it.videoId == c.videoId || (it.title.equals(c.title, ignoreCase = true) &&
                                it.artist.equals(c.artist, ignoreCase = true) && it.duration == c.duration)
                        }
                    }
                    artistas.value = extra?.artistas.orEmpty()
                    if (results.value.isEmpty() && principal.value == null) aviso.value = Aviso.SIN_RESULTADOS
                }
                .onFailure { e ->
                    todo?.cancel()
                    results.value = emptyList()
                    principal.value = null
                    artistas.value = emptyList()
                    aviso.value = avisoDe(e)
                }
            isLoading.value = false
        }
    }

    // "drivers license" es la canción de "drivers license (Official Video)" si
    // además coincide el artista.
    private fun esLaCancionDe(cancion: SearchItem, video: SearchItem): Boolean {
        val mismoArtista = if (cancion.artistId.isNotBlank() && video.artistId.isNotBlank())
            cancion.artistId == video.artistId
        else cancion.artist.equals(video.artist, ignoreCase = true)
        return mismoArtista && video.title.trim().startsWith(cancion.title.trim(), ignoreCase = true)
    }

    // UnknownHost/Connect/Timeout son IOException: no hay red o no llega.
    private fun avisoDe(e: Throwable) = if (e is java.io.IOException) Aviso.SIN_CONEXION else Aviso.ERROR

    /** Toca un chip: lo elige, o si ya estaba elegido vuelve a la mezcla. */
    fun elegirFiltro(f: Filtro) {
        filtro.value = if (filtro.value == f) Filtro.TODO else f
        // Sin buscar todavía no queda nada viejo a la vista con el filtro anterior.
        results.value = emptyList(); principal.value = null; artistas.value = emptyList()
        if (query.value.isNotBlank()) search()
    }

    fun clear() {
        // Si se borra mientras carga, la búsqueda ya no debe pintar resultados.
        searchJob?.cancel()
        isLoading.value = false
        query.value = ""
        results.value = emptyList()
        principal.value = null
        artistas.value = emptyList()
        aviso.value = Aviso.NINGUNO
        suggestions.value = emptyList()
    }
}
