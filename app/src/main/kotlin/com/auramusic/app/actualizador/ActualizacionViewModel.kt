package com.auramusic.app.actualizador

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Une la comprobación (ComprobadorActualizaciones), la descarga (WorkManager) y la
 * APK ya verificada en un solo [EstadoActualizacion]. Se pide con el ámbito de la
 * Activity, así la fila de Ajustes y la hoja comparten la misma instancia.
 */
@HiltViewModel
class ActualizacionViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val comprobador: ComprobadorActualizaciones
) : ViewModel() {

    private val workManager by lazy { WorkManager.getInstance(ctx) }

    // Antes del init: el init ya lanza prepararApk() y lo usa en el acto.
    private val preparando = Mutex()
    /** Ruta, tamaño, fecha y huella de la última APK comprobada con éxito. */
    private var ultimaComprobada: String? = null

    /** La APK descargada y verificada, si hay. */
    private val apkLista = MutableStateFlow<Pair<String, File>?>(null)
    /** Fallo de la descarga o de la verificación de la APK; se borra al reintentar. */
    private val falloLocal = MutableStateFlow<ErrorActualizacion?>(null)

    private val _hojaVisible = MutableStateFlow(false)
    val hojaVisible: StateFlow<Boolean> = _hojaVisible.asStateFlow()

    /** La hoja enseña "Falta un permiso" en vez de la APK lista. */
    private val _pidePermiso = MutableStateFlow(false)
    val pidePermiso: StateFlow<Boolean> = _pidePermiso.asStateFlow()

    val automatico: StateFlow<Boolean> = comprobador.info.map { it.automatico }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * Estado de la descarga. WorkManager se inicializa al pedirlo por primera vez
     * (Room, planificadores): se hace en segundo plano y solo en copias de GitHub, no en
     * el hilo principal en cada arranque (en F-Droid y debug ni se toca).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val trabajo: Flow<WorkInfo?> = comprobador.info.map { it.activo == true }.distinctUntilChanged()
        .flatMapLatest { activo ->
            if (!activo) flowOf(null)
            else flow { emitAll(workManager.getWorkInfosForUniqueWorkFlow(DescargaActualizacionWorker.NOMBRE)) }
                .map { it.firstOrNull() }
                .flowOn(Dispatchers.IO)
        }
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    val estado: StateFlow<EstadoActualizacion> = combine(
        comprobador.info, trabajo, apkLista, falloLocal
    ) { info, t, apk, fallo ->
        val nueva = info.nueva
        when {
            info.activo != true -> EstadoActualizacion.Oculto
            nueva != null && t != null && (t.state == WorkInfo.State.RUNNING || t.state == WorkInfo.State.ENQUEUED) ->
                EstadoActualizacion.Descargando(nueva, t.progress.getLong(DescargaActualizacionWorker.K_BAJADOS, 0L))
            nueva != null && apk?.first == nueva.version -> EstadoActualizacion.Lista(nueva)
            fallo != null -> EstadoActualizacion.Fallo(fallo, nueva, enDescarga = true)
            info.comprobando -> EstadoActualizacion.Comprobando
            info.error != null -> EstadoActualizacion.Fallo(info.error, nueva, enDescarga = false)
            nueva != null -> EstadoActualizacion.Disponible(nueva)
            else -> EstadoActualizacion.AlDia(info.ultimaComprobacion)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EstadoActualizacion.Oculto)

    init {
        // Al terminar la descarga: verificar la APK. Si falló: anotar por qué.
        viewModelScope.launch {
            trabajo.collect { t ->
                when (t?.state) {
                    WorkInfo.State.SUCCEEDED -> prepararApk()
                    WorkInfo.State.FAILED -> {
                        val deEsta = t.outputData.getString(DescargaActualizacionWorker.K_VERSION) ==
                            comprobador.info.value.nueva?.version
                        val e = t.outputData.getString(DescargaActualizacionWorker.K_ERROR)
                        if (deEsta) falloLocal.value =
                            runCatching { ErrorActualizacion.valueOf(e ?: "") }.getOrDefault(ErrorActualizacion.RED)
                    }
                    else -> {}
                }
            }
        }
        // Lo guardado se carga en segundo plano: al llegar la versión, mirar si su APK ya está.
        viewModelScope.launch {
            comprobador.info.map { it.nueva?.version }.distinctUntilChanged().collect { prepararApk() }
        }
    }

    /** Al abrir la app (y al volver a ella): limpiar, buscar si toca y avisar una vez por versión. */
    fun alIniciar() {
        viewModelScope.launch {
            prepararApk()
            comprobador.comprobar(manual = false)
            val nueva = comprobador.info.value.nueva ?: return@launch
            if (!comprobador.yaAvisada(nueva)) {
                comprobador.marcarAvisada(nueva)
                _hojaVisible.value = true
            }
        }
    }

    /** Al volver a primer plano: la notificación de "lista" o el regreso de Ajustes de Android. */
    fun alReanudar(activity: Activity) {
        val intent: Intent? = activity.intent
        if (intent?.getBooleanExtra(DescargaActualizacionWorker.EXTRA_ABRIR, false) == true) {
            intent.removeExtra(DescargaActualizacionWorker.EXTRA_ABRIR)
            _hojaVisible.value = true
        }
        // Vuelta de "Instalar apps desconocidas". Android suele haber matado la app al
        // dar el permiso, así que lo pendiente se lee de disco y se espera a lo guardado.
        viewModelScope.launch {
            val pendiente = _pidePermiso.value || withContext(Dispatchers.IO) { comprobador.instalarTrasPermiso() }
            if (!pendiente || !InstaladorApk.tienePermiso(ctx)) return@launch
            _pidePermiso.value = false
            comprobador.marcarInstalarTrasPermiso(false)
            comprobador.info.first { it.activo != null }
            prepararApk()
            instalar(activity)
        }
    }

    /** La fila de Ajustes: busca si no hay nada en curso; si lo hay, abre la hoja. */
    fun tocarFila() {
        when (estado.value) {
            is EstadoActualizacion.Disponible, is EstadoActualizacion.Descargando, is EstadoActualizacion.Lista ->
                _hojaVisible.value = true
            is EstadoActualizacion.Fallo -> if (falloLocal.value != null) reintentar() else buscarAhora()
            else -> buscarAhora()
        }
    }

    private fun buscarAhora() {
        viewModelScope.launch {
            comprobador.comprobar(manual = true)
            if (comprobador.info.value.nueva != null) _hojaVisible.value = true
        }
    }

    fun cambiarAutomatico(si: Boolean) = comprobador.cambiarAutomatico(si)

    fun descargar() {
        val v = comprobador.info.value.nueva ?: return
        falloLocal.value = null
        val peticion = OneTimeWorkRequestBuilder<DescargaActualizacionWorker>()
            .setInputData(workDataOf(
                DescargaActualizacionWorker.K_VERSION to v.version,
                DescargaActualizacionWorker.K_URL to v.url,
                DescargaActualizacionWorker.K_SHA256 to v.sha256,
                DescargaActualizacionWorker.K_TAMANO to v.tamano
            ))
            // Sin exigir red "validada": la pide el usuario con la app abierta, y Android no
            // valida algunas redes que sí funcionan (DNS privado tipo AdGuard en el J7): el
            // trabajo se quedaba esperando para siempre en 0 %. Sin red, falla y se reintenta.
            .build()
        // KEEP: dos pulsaciones no lanzan dos descargas.
        workManager.enqueueUniqueWork(DescargaActualizacionWorker.NOMBRE, ExistingWorkPolicy.KEEP, peticion)
    }

    private fun reintentar() {
        falloLocal.value = null
        if (comprobador.info.value.nueva != null) { descargar(); _hojaVisible.value = true } else buscarAhora()
    }

    fun cancelarDescarga() {
        workManager.cancelUniqueWork(DescargaActualizacionWorker.NOMBRE)
    }

    fun ocultarHoja() {
        _hojaVisible.value = false
        cancelarPermiso()
    }

    /** "Cancelar" en "Falta un permiso": vuelve a la APK lista. */
    fun cancelarPermiso() {
        _pidePermiso.value = false
        viewModelScope.launch(Dispatchers.IO) { comprobador.marcarInstalarTrasPermiso(false) }
    }

    fun instalar(activity: Activity) {
        val (_, apk) = apkLista.value ?: return
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { InstaladorApk.verificar(ctx, apk) }
            if (error != null) {
                withContext(Dispatchers.IO) { apk.delete() }
                apkLista.value = null
                falloLocal.value = error
                return@launch
            }
            if (!InstaladorApk.tienePermiso(ctx)) {
                _pidePermiso.value = true
                withContext(Dispatchers.IO) { comprobador.marcarInstalarTrasPermiso(true) }
                return@launch
            }
            InstaladorApk.instalar(activity, apk)
        }
    }

    fun abrirAjustesPermiso(activity: Activity) = InstaladorApk.abrirAjustesPermiso(activity)

    /**
     * Limpia lo que sobra y comprueba la APK guardada, siempre en ese orden y de una en
     * una: se llama desde varios sitios a la vez (al abrir, al llegar la versión
     * guardada, al terminar la descarga) y así ni se cruzan ni se calcula dos veces la huella.
     */
    private suspend fun prepararApk() = preparando.withLock {
        val descargando = estado.value is EstadoActualizacion.Descargando
        withContext(Dispatchers.IO) { InstaladorApk.limpiar(ctx, descargando) }
        buscarApkLista()
    }

    /**
     * "Lista para instalar" solo con la APK ya comprobada entera: misma huella que la
     * publicada y mismo paquete, versión mayor y misma firma que la app instalada (la
     * hoja lo afirma). Si algo no cuadra, se borra. Al instalar se vuelve a comprobar.
     */
    private suspend fun buscarApkLista() {
        val v = comprobador.info.value.nueva ?: run { apkLista.value = null; return }
        val apk = DescargaActualizacionWorker.archivoApk(ctx, v.version)
        // Se llama en cada vuelta a la app: si la APK no cambió desde la última
        // comprobación buena, no se vuelve a leer (5 MB de SHA-256 y el análisis de la APK).
        val huellaArchivo = withContext(Dispatchers.IO) {
            if (apk.isFile) "${apk.path}|${apk.length()}|${apk.lastModified()}|${v.sha256}" else null
        }
        val yaComprobada = huellaArchivo != null && huellaArchivo == ultimaComprobada
        val error: ErrorActualizacion? = if (yaComprobada) null else withContext(Dispatchers.IO) {
            when {
                huellaArchivo == null -> null
                FirmaPropia.sha256(apk) != v.sha256 -> ErrorActualizacion.DANADA
                else -> InstaladorApk.verificar(ctx, apk)
            }
        }
        val valida = huellaArchivo != null && error == null
        ultimaComprobada = if (valida) huellaArchivo else null
        if (error != null) withContext(Dispatchers.IO) { apk.delete() }
        apkLista.value = if (valida) v.version to apk else null
        when {
            valida -> falloLocal.value = null
            error != null -> falloLocal.value = error
        }
    }
}
