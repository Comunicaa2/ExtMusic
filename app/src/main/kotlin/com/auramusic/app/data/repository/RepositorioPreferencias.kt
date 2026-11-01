package com.auramusic.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.auramusic.app.domain.model.AppSettings
import com.auramusic.app.domain.model.AudioQuality
import com.auramusic.app.domain.model.CodecAudio
import com.auramusic.app.domain.model.FormatoDescarga
import com.auramusic.app.domain.model.TemaApp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// UNA SOLA instancia de DataStore para TODA la app.
// El delegate `preferencesDataStore` garantiza una única instancia por nombre de
// archivo en el proceso. Crear varias para el mismo archivo crashea en runtime,
// por eso vive aquí (en el repositorio) y nadie más la declara.
// Conserva el mismo nombre "aura_settings" de antes → no se pierden ajustes.
// ─────────────────────────────────────────────────────────────────────────────
private val Context.dataStorePreferencias: DataStore<Preferences> by
    preferencesDataStore(name = "aura_settings")

/**
 * Única fuente de verdad de los ajustes (Fase 2).
 * Expone el estado como Flow y ofrece funciones suspend para guardarlo.
 * La UI nunca toca DataStore directamente: pasa siempre por aquí.
 */
@Singleton
class RepositorioPreferencias @Inject constructor(
    @ApplicationContext private val contexto: Context
) {
    private val dataStore get() = contexto.dataStorePreferencias

    private object Claves {
        // Apariencia / interfaz
        val TEMA             = stringPreferencesKey("tema")
        val DESENFOQUE_FONDO = booleanPreferencesKey("blur_player_bg")
        val LIQUID_GLASS     = booleanPreferencesKey("liquid_glass")
        // Reproducción
        val SEGUNDO_PLANO    = booleanPreferencesKey("rep_segundo_plano")
        val CROSSFADE        = intPreferencesKey("rep_crossfade_seg")
        val NORMALIZAR       = booleanPreferencesKey("rep_normalizar")
        val AHORRO_AUDIO     = booleanPreferencesKey("rep_ahorro_audio")
        val COLA_ALEATORIA   = booleanPreferencesKey("rep_aleatoria")
        val OCULTAR_EXPLICITO = booleanPreferencesKey("rep_ocultar_explicito")  // Q
        val REPRODUCCION_AUTOMATICA = booleanPreferencesKey("rep_automatica")
        // YouTube Music
        val DATOS_MOVILES    = booleanPreferencesKey("yt_datos_moviles")
        val CODEC            = stringPreferencesKey("yt_codec")
        val PRECARGA         = booleanPreferencesKey("yt_precarga")
        val REGION           = stringPreferencesKey("yt_region")
        val CACHE_STREAMS    = booleanPreferencesKey("yt_cache_streams")
        // Calidad
        val CALIDAD_AUDIO    = stringPreferencesKey("audio_quality")
        val CALIDAD_DESCARGA = stringPreferencesKey("download_quality")
        // Descargas
        val FORMATO_DESCARGA = stringPreferencesKey("desc_formato")
        val DESCARGAR_AL_LIKE = booleanPreferencesKey("desc_al_like")   // P
        val CARPETA_DESCARGA = stringPreferencesKey("desc_carpeta")
        val INCRUSTAR_CARAT  = booleanPreferencesKey("desc_caratula")
        val DESCARGAR_LETRAS = booleanPreferencesKey("desc_letras")
        val DESCARGA_SOLO_WIFI = booleanPreferencesKey("desc_solo_wifi")
        val DESCARGAS_SIMULT = intPreferencesKey("desc_simultaneas")
        // Biblioteca local
        val CARPETAS_ESCANEO = stringPreferencesKey("bib_carpetas")
        // Caché
        val CACHE_MAX_MB     = intPreferencesKey("cache_max_mb")
        // Letras
        val MOSTRAR_LETRAS   = booleanPreferencesKey("show_lyrics")
        val LETRAS_AUTO      = booleanPreferencesKey("letras_auto")
        val LETRAS_TAMANO    = intPreferencesKey("letras_tamano_sp")
        val LETRAS_RETRASO   = intPreferencesKey("letras_retraso_ms")
    }

    /** Estado completo de los ajustes, reactivo. Se recolecta con collectAsState. */
    val ajustes: Flow<AppSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }   // si el archivo falla, valores por defecto
        .map { prefs ->
            AppSettings(
                // Apariencia / interfaz
                tema                     = leerTema(prefs[Claves.TEMA]),
                blurPlayerBg             = prefs[Claves.DESENFOQUE_FONDO] ?: true,
                liquidGlassEnabled       = prefs[Claves.LIQUID_GLASS] ?: true,
                // Reproducción
                reproduccionSegundoPlano = prefs[Claves.SEGUNDO_PLANO] ?: true,
                crossfadeSegundos        = (prefs[Claves.CROSSFADE] ?: 0).coerceIn(0, 10),
                normalizarVolumen        = prefs[Claves.NORMALIZAR] ?: false,
                ahorroAudio              = prefs[Claves.AHORRO_AUDIO] ?: false,
                colaAleatoria            = prefs[Claves.COLA_ALEATORIA] ?: false,
                ocultarExplicito         = prefs[Claves.OCULTAR_EXPLICITO] ?: false,
                reproduccionAutomatica   = prefs[Claves.REPRODUCCION_AUTOMATICA] ?: true,
                // YouTube Music
                permitirDatosMoviles     = prefs[Claves.DATOS_MOVILES] ?: true,
                codecAudio               = leerCodec(prefs[Claves.CODEC]),
                precargarSiguiente       = prefs[Claves.PRECARGA] ?: true,
                regionPais               = prefs[Claves.REGION] ?: "",
                cachearStreams           = prefs[Claves.CACHE_STREAMS] ?: true,
                // Calidad
                audioQuality             = leerCalidad(prefs[Claves.CALIDAD_AUDIO]),
                downloadQuality          = leerCalidad(prefs[Claves.CALIDAD_DESCARGA]),
                // Descargas
                formatoDescarga          = leerFormato(prefs[Claves.FORMATO_DESCARGA]),
                descargarAlDarLike       = prefs[Claves.DESCARGAR_AL_LIKE] ?: false,
                carpetaDescargas         = prefs[Claves.CARPETA_DESCARGA] ?: "",
                incrustarCaratula        = prefs[Claves.INCRUSTAR_CARAT] ?: true,
                descargarLetras          = prefs[Claves.DESCARGAR_LETRAS] ?: true,
                descargarSoloWifi        = prefs[Claves.DESCARGA_SOLO_WIFI] ?: false,
                descargasSimultaneas     = (prefs[Claves.DESCARGAS_SIMULT] ?: 2).coerceIn(1, 3),
                // Biblioteca local
                carpetasEscaneo          = prefs[Claves.CARPETAS_ESCANEO] ?: "",
                // Caché
                cacheMaximoMb            = (prefs[Claves.CACHE_MAX_MB] ?: 256).coerceAtLeast(32),
                // Letras
                showLyrics               = prefs[Claves.MOSTRAR_LETRAS] ?: true,
                letrasAutomaticas        = prefs[Claves.LETRAS_AUTO] ?: true,
                tamanoFuenteLetras       = (prefs[Claves.LETRAS_TAMANO] ?: 16).coerceIn(12, 28),
                retrasoLetrasMs          = (prefs[Claves.LETRAS_RETRASO] ?: 0).coerceIn(-500, 1500),
            )
        }

    // ── Setters (suspend) ────────────────────────────────────────────────────
    // Apariencia / interfaz
    suspend fun guardarTema(t: TemaApp)                 = editar { it[Claves.TEMA] = t.name }
    suspend fun guardarDesenfoqueFondo(activo: Boolean) = editar { it[Claves.DESENFOQUE_FONDO] = activo }
    suspend fun guardarLiquidGlass(activo: Boolean)     = editar { it[Claves.LIQUID_GLASS] = activo }
    // Reproducción
    suspend fun guardarSegundoPlano(activo: Boolean)    = editar { it[Claves.SEGUNDO_PLANO] = activo }
    suspend fun guardarCrossfade(seg: Int)              = editar { it[Claves.CROSSFADE] = seg.coerceIn(0, 10) }
    suspend fun guardarNormalizar(activo: Boolean)      = editar { it[Claves.NORMALIZAR] = activo }
    suspend fun guardarAhorroAudio(activo: Boolean)     = editar { it[Claves.AHORRO_AUDIO] = activo }
    suspend fun guardarColaAleatoria(activo: Boolean)   = editar { it[Claves.COLA_ALEATORIA] = activo }
    suspend fun guardarOcultarExplicito(activo: Boolean) = editar { it[Claves.OCULTAR_EXPLICITO] = activo }
    suspend fun guardarReproduccionAutomatica(activo: Boolean) = editar { it[Claves.REPRODUCCION_AUTOMATICA] = activo }
    // YouTube Music
    suspend fun guardarDatosMoviles(activo: Boolean)    = editar { it[Claves.DATOS_MOVILES] = activo }
    suspend fun guardarCodec(c: CodecAudio)             = editar { it[Claves.CODEC] = c.name }
    suspend fun guardarPrecarga(activo: Boolean)        = editar { it[Claves.PRECARGA] = activo }
    suspend fun guardarRegion(codigo: String)           = editar { it[Claves.REGION] = codigo }
    suspend fun guardarCacheStreams(activo: Boolean)    = editar { it[Claves.CACHE_STREAMS] = activo }
    // Calidad
    suspend fun guardarCalidadAudio(q: AudioQuality)    = editar { it[Claves.CALIDAD_AUDIO] = q.name }
    suspend fun guardarCalidadDescarga(q: AudioQuality) = editar { it[Claves.CALIDAD_DESCARGA] = q.name }
    // Descargas
    suspend fun guardarFormatoDescarga(f: FormatoDescarga) = editar { it[Claves.FORMATO_DESCARGA] = f.name }
    suspend fun guardarDescargarAlDarLike(activo: Boolean) = editar { it[Claves.DESCARGAR_AL_LIKE] = activo }
    suspend fun guardarCarpetaDescargas(ruta: String)   = editar { it[Claves.CARPETA_DESCARGA] = ruta }
    suspend fun guardarIncrustarCaratula(activo: Boolean) = editar { it[Claves.INCRUSTAR_CARAT] = activo }
    suspend fun guardarDescargarLetras(activo: Boolean) = editar { it[Claves.DESCARGAR_LETRAS] = activo }
    suspend fun guardarDescargaSoloWifi(activo: Boolean) = editar { it[Claves.DESCARGA_SOLO_WIFI] = activo }
    suspend fun guardarDescargasSimultaneas(n: Int)     = editar { it[Claves.DESCARGAS_SIMULT] = n.coerceIn(1, 3) }
    // Biblioteca local
    suspend fun guardarCarpetasEscaneo(carpetas: String) = editar { it[Claves.CARPETAS_ESCANEO] = carpetas }
    // Caché
    suspend fun guardarCacheMaximoMb(mb: Int)           = editar { it[Claves.CACHE_MAX_MB] = mb.coerceAtLeast(32) }
    // Letras
    suspend fun guardarMostrarLetras(activo: Boolean)   = editar { it[Claves.MOSTRAR_LETRAS] = activo }
    suspend fun guardarLetrasAutomaticas(activo: Boolean) = editar { it[Claves.LETRAS_AUTO] = activo }
    suspend fun guardarTamanoFuenteLetras(sp: Int)      = editar { it[Claves.LETRAS_TAMANO] = sp.coerceIn(12, 28) }
    suspend fun guardarRetrasoLetras(ms: Int)           = editar { it[Claves.LETRAS_RETRASO] = ms.coerceIn(-500, 1500) }

    /**
     * Restaura los ajustes de un respaldo en una sola escritura. Las carpetas
     * (descargas y música del teléfono) NO se tocan: son permisos de carpeta del
     * teléfono donde se eligieron y en otro no sirven.
     */
    suspend fun restaurar(a: AppSettings) = editar {
        it[Claves.TEMA] = a.tema.name
        it[Claves.DESENFOQUE_FONDO] = a.blurPlayerBg
        it[Claves.LIQUID_GLASS] = a.liquidGlassEnabled
        it[Claves.SEGUNDO_PLANO] = a.reproduccionSegundoPlano
        it[Claves.CROSSFADE] = a.crossfadeSegundos.coerceIn(0, 10)
        it[Claves.NORMALIZAR] = a.normalizarVolumen
        it[Claves.AHORRO_AUDIO] = a.ahorroAudio
        it[Claves.COLA_ALEATORIA] = a.colaAleatoria
        it[Claves.OCULTAR_EXPLICITO] = a.ocultarExplicito
        it[Claves.REPRODUCCION_AUTOMATICA] = a.reproduccionAutomatica
        it[Claves.DATOS_MOVILES] = a.permitirDatosMoviles
        it[Claves.CODEC] = a.codecAudio.name
        it[Claves.PRECARGA] = a.precargarSiguiente
        it[Claves.REGION] = a.regionPais.take(8)
        it[Claves.CACHE_STREAMS] = a.cachearStreams
        it[Claves.CALIDAD_AUDIO] = a.audioQuality.name
        it[Claves.CALIDAD_DESCARGA] = a.downloadQuality.name
        it[Claves.FORMATO_DESCARGA] = a.formatoDescarga.name
        it[Claves.DESCARGAR_AL_LIKE] = a.descargarAlDarLike
        it[Claves.INCRUSTAR_CARAT] = a.incrustarCaratula
        it[Claves.DESCARGAR_LETRAS] = a.descargarLetras
        it[Claves.DESCARGA_SOLO_WIFI] = a.descargarSoloWifi
        it[Claves.DESCARGAS_SIMULT] = a.descargasSimultaneas.coerceIn(1, 3)
        it[Claves.CACHE_MAX_MB] = a.cacheMaximoMb.coerceIn(32, 16_384)
        it[Claves.MOSTRAR_LETRAS] = a.showLyrics
        it[Claves.LETRAS_AUTO] = a.letrasAutomaticas
        it[Claves.LETRAS_TAMANO] = a.tamanoFuenteLetras.coerceIn(12, 28)
        it[Claves.LETRAS_RETRASO] = a.retrasoLetrasMs.coerceIn(-500, 1500)
    }

    private suspend fun editar(bloque: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(bloque)
    }

    // Lee el enum de forma segura (si el guardado está corrupto, vuelve a HIGH).
    private fun leerCalidad(valor: String?): AudioQuality =
        runCatching { AudioQuality.valueOf(valor ?: AudioQuality.HIGH.name) }
            .getOrDefault(AudioQuality.HIGH)

    private fun leerCodec(valor: String?): CodecAudio =
        runCatching { CodecAudio.valueOf(valor ?: CodecAudio.AAC.name) }
            .getOrDefault(CodecAudio.AAC)

    private fun leerFormato(valor: String?): FormatoDescarga =
        runCatching { FormatoDescarga.valueOf(valor ?: FormatoDescarga.M4A.name) }
            .getOrDefault(FormatoDescarga.M4A)

    // Tema guardado; por defecto OSCURO (la app está diseñada dark-first).
    private fun leerTema(valor: String?): TemaApp =
        runCatching { TemaApp.valueOf(valor ?: TemaApp.OSCURO.name) }
            .getOrDefault(TemaApp.OSCURO)
}
