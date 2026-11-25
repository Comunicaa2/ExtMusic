package com.auramusic.app.actualizador

import android.content.Context
import android.util.Log
import com.auramusic.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pregunta a GitHub por la última release (la API ya da tamaño y SHA-256 de la APK,
 * así que no hace falta un catálogo propio). Como mucho una vez al día de forma
 * automática; el botón de Ajustes pregunta cuando se pulsa.
 *
 * Todo lo suyo va en SharedPreferences propias ("actualizador"): el DataStore de la
 * app solo puede tener una instancia (ver RepositorioPreferencias).
 */
@Singleton
class ComprobadorActualizaciones @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    data class Info(
        /** null mientras no se sabe; false en copias de F-Droid o debug. */
        val activo: Boolean? = null,
        val nueva: VersionPublicada? = null,
        val comprobando: Boolean = false,
        val error: ErrorActualizacion? = null,
        val ultimaComprobacion: Long = 0L,
        val automatico: Boolean = true
    )

    private val _info = MutableStateFlow(Info())
    val info: StateFlow<Info> = _info.asStateFlow()

    private val prefs by lazy { ctx.getSharedPreferences("actualizador", Context.MODE_PRIVATE) }
    private val json = Json { ignoreUnknownKeys = true }
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val mutex = Mutex()
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // La firma y lo guardado se leen fuera del hilo principal.
        alcance.launch { mutex.withLock {
            val activo = FirmaPropia.esDeGithub(ctx)
            val guardada = leerGuardada(prefs.getString(K_RELEASE, null))
            _info.update {
                it.copy(
                    activo = activo,
                    nueva = guardada?.takeIf { v -> ValidarRelease.esMasNueva(v.version, BuildConfig.VERSION_NAME) },
                    ultimaComprobacion = prefs.getLong(K_ULTIMA, 0L),
                    automatico = prefs.getBoolean(K_AUTO, true)
                )
            }
        } }
    }

    fun cambiarAutomatico(si: Boolean) {
        _info.update { it.copy(automatico = si) }
        alcance.launch { prefs.edit().putBoolean(K_AUTO, si).apply() }
    }

    /** ¿Ya se enseñó el aviso de esta versión al abrir la app? Solo se enseña una vez. */
    fun yaAvisada(v: VersionPublicada): Boolean = prefs.getString(K_AVISADA, null) == v.version
    fun marcarAvisada(v: VersionPublicada) { prefs.edit().putString(K_AVISADA, v.version).apply() }

    /**
     * "Instalar en cuanto haya permiso". Se guarda en disco porque Android (8–11 al
     * menos) mata la app al cambiar "Instalar apps desconocidas": al volver es otra.
     */
    fun marcarInstalarTrasPermiso(si: Boolean) { prefs.edit().putBoolean(K_TRAS_PERMISO, si).apply() }
    fun instalarTrasPermiso(): Boolean = prefs.getBoolean(K_TRAS_PERMISO, false)

    /**
     * [manual]: el usuario pulsó "Buscar actualizaciones". Si no, solo pregunta si
     * está activado, pasó el intervalo y GitHub no pidió esperar.
     */
    suspend fun comprobar(manual: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_info.value.activo == null) _info.update { it.copy(activo = FirmaPropia.esDeGithub(ctx)) }
            if (_info.value.activo != true) return@withLock
            val ahora = System.currentTimeMillis()
            val esperarHasta = prefs.getLong(K_ESPERAR, 0L)
            if (!manual) {
                if (!prefs.getBoolean(K_AUTO, true)) return@withLock
                if (ahora - prefs.getLong(K_ULTIMA, 0L) < INTERVALO_MS) return@withLock
                if (ahora < esperarHasta) return@withLock
            } else if (ahora < esperarHasta) {
                _info.update { it.copy(error = ErrorActualizacion.LIMITE) }
                return@withLock
            }

            _info.update { it.copy(comprobando = true, error = null) }
            val (version, error) = pedir(ahora)
            _info.update {
                it.copy(
                    comprobando = false,
                    error = error,
                    nueva = if (error == null) version?.takeIf { v -> ValidarRelease.esMasNueva(v.version, BuildConfig.VERSION_NAME) }
                            else it.nueva,
                    ultimaComprobacion = if (error == null) ahora else it.ultimaComprobacion
                )
            }
        }
    }

    private fun pedir(ahora: Long): Pair<VersionPublicada?, ErrorActualizacion?> {
        val guardada = prefs.getString(K_RELEASE, null)
        val peticion = Request.Builder().url(URL_ULTIMA)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "ExtMusic-actualizador")
            .apply {
                // Con el ETag, si no cambió nada GitHub responde 304 y no gasta cupo.
                val etag = prefs.getString(K_ETAG, null)
                if (etag != null && guardada != null) header("If-None-Match", etag)
            }
            .build()
        return try {
            http.newCall(peticion).execute().use { r ->
                when {
                    r.code == 304 -> {
                        // Lo guardado se vuelve a leer y validar: si está dañado, se descarta
                        // junto con el ETag y la próxima vez se pide entero (no se cierra la app).
                        val v = leerGuardada(guardada)
                        if (v == null) {
                            prefs.edit().remove(K_RELEASE).remove(K_ETAG).apply()
                            null to ErrorActualizacion.CATALOGO
                        } else {
                            prefs.edit().putLong(K_ULTIMA, ahora).apply()
                            v to null
                        }
                    }
                    r.code == 403 || r.code == 429 -> {
                        // Retry-After (segundos) o x-ratelimit-reset (época); si no hay, un día.
                        val hasta = r.header("Retry-After")?.toLongOrNull()?.let { ahora + it * 1000 }
                            ?: r.header("x-ratelimit-reset")?.toLongOrNull()?.let { it * 1000 }
                            ?: (ahora + INTERVALO_MS)
                        prefs.edit().putLong(K_ESPERAR, maxOf(hasta, ahora + 60_000)).apply()
                        null to ErrorActualizacion.LIMITE
                    }
                    r.code == 404 -> {   // aún no hay ninguna release
                        prefs.edit().putLong(K_ULTIMA, ahora).remove(K_RELEASE).remove(K_ETAG).apply()
                        null to null
                    }
                    !r.isSuccessful -> null to ErrorActualizacion.RED
                    else -> {
                        val texto = leerConTope(r.body) ?: return@use null to ErrorActualizacion.CATALOGO
                        val release = runCatching { json.decodeFromString<ReleaseGithub>(texto) }.getOrNull()
                        val version = release?.let { ValidarRelease.validar(it) }
                            ?: return@use null to ErrorActualizacion.CATALOGO
                        prefs.edit()
                            .putLong(K_ULTIMA, ahora)
                            .putString(K_RELEASE, json.encodeToString(VersionPublicada.serializer(), version))
                            .putString(K_ETAG, r.header("ETag"))
                            .apply()
                        version to null
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "No se pudo comprobar: ${e.javaClass.simpleName}")
            null to ErrorActualizacion.RED
        } catch (e: Exception) {
            // Cualquier otra cosa (respuesta rara, datos dañados): falla cerrado, sin cerrar la app.
            Log.w(TAG, "Respuesta no válida: ${e.javaClass.simpleName}")
            null to ErrorActualizacion.CATALOGO
        }
    }

    /** Lo guardado en las preferencias, leído y validado otra vez; null si no sirve. */
    private fun leerGuardada(texto: String?): VersionPublicada? =
        texto?.let { runCatching { json.decodeFromString<VersionPublicada>(it) }.getOrNull() }
            ?.takeIf { ValidarRelease.esValida(it) }

    /** El cuerpo entero, salvo que pase de [TOPE_BYTES]: una release normal ocupa unos pocos KB. */
    private fun leerConTope(body: okhttp3.ResponseBody): String? {
        if (body.contentLength() > TOPE_BYTES) return null
        val fuente = body.source()
        if (fuente.request(TOPE_BYTES + 1)) return null
        return fuente.buffer.readUtf8()
    }

    companion object {
        private const val TAG = "Actualizador"
        private const val URL_ULTIMA = "https://api.github.com/repos/Comunicaa2/ExtMusic/releases/latest"
        private const val INTERVALO_MS = 20L * 60 * 60 * 1000   // ~1 vez al día, aunque se abra a horas distintas
        private const val TOPE_BYTES = 256L * 1024
        private const val K_ULTIMA = "ultima_comprobacion"
        private const val K_ETAG = "etag"
        private const val K_RELEASE = "release"
        private const val K_ESPERAR = "esperar_hasta"
        private const val K_AUTO = "automatico"
        private const val K_AVISADA = "avisada"
        private const val K_TRAS_PERMISO = "instalar_tras_permiso"
    }
}
