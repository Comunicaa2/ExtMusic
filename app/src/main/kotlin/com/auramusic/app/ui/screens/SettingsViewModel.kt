package com.auramusic.app.ui.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import com.auramusic.app.data.repository.BibliotecaLocalRepository
import com.auramusic.app.data.repository.RepositorioPreferencias
import com.auramusic.app.data.repository.RespaldoRepository
import com.auramusic.app.domain.model.AppSettings
import com.auramusic.app.domain.model.AudioQuality
import com.auramusic.app.domain.model.TemaApp
import com.auramusic.app.service.MusicService
import com.auramusic.app.service.OffloadRoto
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

// NOTA (Fase 2): la lógica de DataStore (delegate, claves, lectura/escritura)
// se trasladó a RepositorioPreferencias (única fuente de verdad). Este ViewModel
// ahora solo recolecta el estado y delega el guardado. La API pública no cambió.

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repositorio: RepositorioPreferencias,
    private val bibliotecaRepo: BibliotecaLocalRepository,
    private val respaldoRepo: RespaldoRepository,
    @ApplicationContext private val contexto: Context
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repositorio.ajustes
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // ── Apariencia / interfaz ────────────────────────────────────────────────
    fun setTema(t: TemaApp)  = lanzar { repositorio.guardarTema(t) }
    fun toggleBlurPlayerBg() = lanzar { repositorio.guardarDesenfoqueFondo(!settings.value.blurPlayerBg) }
    fun toggleLiquidGlass()  = lanzar { repositorio.guardarLiquidGlass(!settings.value.liquidGlassEnabled) }

    // ── Reproducción ─────────────────────────────────────────────────────────
    fun toggleSegundoPlano() = lanzar { repositorio.guardarSegundoPlano(!settings.value.reproduccionSegundoPlano) }
    fun setCrossfade(seg: Int) = lanzar { repositorio.guardarCrossfade(seg) }
    fun toggleNormalizar()   = lanzar { repositorio.guardarNormalizar(!settings.value.normalizarVolumen) }
    // Si el offload falló en este firmware (MusicService lo marca), se avisa en el
    // ajuste; al volver a encenderlo se borra la marca para reintentar.
    val offloadRoto = MutableStateFlow(OffloadRoto.estaRoto(contexto))
    fun toggleAhorroAudio()  = lanzar {
        val encender = !settings.value.ahorroAudio
        if (encender) { OffloadRoto.olvidar(contexto); offloadRoto.value = false }
        repositorio.guardarAhorroAudio(encender)
    }
    fun toggleColaAleatoria() = lanzar { repositorio.guardarColaAleatoria(!settings.value.colaAleatoria) }
    fun toggleOcultarExplicito() = lanzar { repositorio.guardarOcultarExplicito(!settings.value.ocultarExplicito) }  // Q
    fun toggleReproduccionAutomatica() = lanzar { repositorio.guardarReproduccionAutomatica(!settings.value.reproduccionAutomatica) }

    // ── YouTube Music ───────────────────────────
    fun toggleDatosMoviles() = lanzar { repositorio.guardarDatosMoviles(!settings.value.permitirDatosMoviles) }
    fun setRegion(codigo: String) = lanzar { repositorio.guardarRegion(codigo) }
    fun toggleCacheStreams() = lanzar { repositorio.guardarCacheStreams(!settings.value.cachearStreams) }

    // ── Calidad ──────────────────────────────────────────────────────────────
    fun setAudioQuality(q: AudioQuality)    = lanzar { repositorio.guardarCalidadAudio(q) }
    fun setDownloadQuality(q: AudioQuality) = lanzar { repositorio.guardarCalidadDescarga(q) }

    // ── Descargas ────────────────────────────────────────────────────────────
    fun toggleDescargarAlDarLike() = lanzar { repositorio.guardarDescargarAlDarLike(!settings.value.descargarAlDarLike) }  // P
    fun setCarpetaDescargas(ruta: String) = lanzar { repositorio.guardarCarpetaDescargas(ruta) }
    fun toggleIncrustarCaratula() = lanzar { repositorio.guardarIncrustarCaratula(!settings.value.incrustarCaratula) }
    fun toggleDescargarLetras() = lanzar { repositorio.guardarDescargarLetras(!settings.value.descargarLetras) }
    fun toggleDescargaSoloWifi() = lanzar { repositorio.guardarDescargaSoloWifi(!settings.value.descargarSoloWifi) }
    fun setDescargasSimultaneas(n: Int) = lanzar { repositorio.guardarDescargasSimultaneas(n) }

    // ── Biblioteca local ─────────────────────────────────────────────────────
    fun setCarpetasEscaneo(carpetas: String) = lanzar { repositorio.guardarCarpetasEscaneo(carpetas) }

    // ── Caché ────────────────────────────────────────────────────────────────
    // Se aplica al momento: lo que sobre se borra ya (ver LimiteCacheAudio).
    fun setCacheMaximoMb(mb: Int, alTerminar: () -> Unit = {}) = lanzar {
        repositorio.guardarCacheMaximoMb(mb)
        withContext(Dispatchers.IO) { MusicService.aplicarLimiteCache(com.auramusic.app.domain.model.limiteCacheAudioMb(mb)) }
        alTerminar()
    }

    // ── Letras ───────────────────────────────────────────────────────────────
    fun toggleShowLyrics()   = lanzar { repositorio.guardarMostrarLetras(!settings.value.showLyrics) }
    fun toggleLetrasAutomaticas() = lanzar { repositorio.guardarLetrasAutomaticas(!settings.value.letrasAutomaticas) }
    fun setTamanoFuenteLetras(sp: Int) = lanzar { repositorio.guardarTamanoFuenteLetras(sp) }
    fun setRetrasoLetras(ms: Int) = lanzar { repositorio.guardarRetrasoLetras(ms) }

    // ── Acciones (no son ajustes; ejecutan algo y devuelven resultado) ───────
    /** Re-escanea MediaStore y devuelve cuántas pistas locales hay. Solo lectura. */
    fun actualizarBiblioteca(onResultado: (Int) -> Unit) {
        viewModelScope.launch {
            val n = runCatching { bibliotecaRepo.escanearCanciones().size }.getOrDefault(0)
            onResultado(n)
        }
    }

    /**
     * "Liberar espacio": vacía el audio temporal y las portadas guardadas. Nunca
     * toca descargas, Me gusta ni playlists.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun limpiarCache(onHecho: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { MusicService.limpiarCache(contexto.cacheDir) }
                runCatching { contexto.imageLoader.diskCache?.clear() }
            }
            runCatching { contexto.imageLoader.memoryCache?.clear() }
            onHecho()
        }
    }

    /** Lo que ocupa la app, por partes (en bytes). */
    data class UsoAlmacenamiento(val audio: Long, val portadas: Long, val descargas: Long, val total: Long)

    fun usoAlmacenamiento(onResultado: (UsoAlmacenamiento) -> Unit) {
        viewModelScope.launch {
            val uso = withContext(Dispatchers.IO) {
                fun tamano(f: java.io.File) = runCatching {
                    f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }.getOrDefault(0L)
                UsoAlmacenamiento(
                    audio = runCatching { MusicService.usoCache(contexto.cacheDir) }.getOrDefault(0L),
                    portadas = tamano(contexto.cacheDir.resolve(com.auramusic.app.AuraMusicApplication.CARPETA_PORTADAS)),
                    descargas = tamano(java.io.File(contexto.filesDir, "downloads")),
                    total = tamano(contexto.dataDir)
                )
            }
            onResultado(uso)
        }
    }

    /** Exporta/importa el backup de la biblioteca (el fallo dice por qué, p. ej. demasiado grande). */
    fun exportarRespaldo(destino: Uri, onResultado: (Result<RespaldoRepository.Resumen>) -> Unit) {
        viewModelScope.launch { onResultado(runCatching { respaldoRepo.exportar(destino) }) }
    }

    fun importarRespaldo(origen: Uri, onResultado: (Result<RespaldoRepository.Resumen>) -> Unit) {
        viewModelScope.launch { onResultado(runCatching { respaldoRepo.importar(origen) }) }
    }

    private fun lanzar(bloque: suspend () -> Unit) {
        viewModelScope.launch { bloque() }
    }
}
