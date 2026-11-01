package com.auramusic.app.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.auramusic.app.data.api.SearchItem
import com.auramusic.app.data.db.SongDao
import com.auramusic.app.data.repository.RepositorioPreferencias
import com.auramusic.app.domain.model.OrigenCancion
import com.auramusic.app.work.DescargaWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadProgress(
    val songId: String,
    val progress: Int, // 0-100
    val status: DownloadStatus
)

enum class DownloadStatus { PENDING, DOWNLOADING, DONE, ERROR }

@HiltViewModel
class DownloadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val repositorioPreferencias: RepositorioPreferencias
) : ViewModel() {

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads

    val downloadedSongs = songDao.getDownloaded()

    private val workManager = WorkManager.getInstance(context)

    // Bug 4: la descarga real corre en un DescargaWorker (WorkManager). Sobrevive al
    // cierre de la app y respeta calidad/formato/carpeta de Ajustes; al terminar la
    // canción queda con origen = LOCAL.
    fun downloadSong(item: SearchItem) {
        val datos = workDataOf(
            DescargaWorker.KEY_VIDEO_ID to item.videoId,
            DescargaWorker.KEY_TITLE to item.title,
            DescargaWorker.KEY_ARTIST to item.artist,
            DescargaWorker.KEY_THUMB to item.thumbnailUrl,
            DescargaWorker.KEY_DURATION to parseDuracion(item.duration)
        )
        viewModelScope.launch {
            // Ajuste "Descargar solo con WiFi": WorkManager deja la descarga en
            // espera hasta que haya una red sin límite de datos.
            val soloWifi = repositorioPreferencias.ajustes.first().descargarSoloWifi
            val restricciones = Constraints.Builder()
                .setRequiredNetworkType(if (soloWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val solicitud = OneTimeWorkRequestBuilder<DescargaWorker>()
                .setInputData(datos).setConstraints(restricciones).build()
            workManager.enqueueUniqueWork(
                DescargaWorker.nombreUnico(item.videoId), ExistingWorkPolicy.KEEP, solicitud
            )
            observarDescarga(item.videoId)
        }
    }

    private fun observarDescarga(videoId: String) {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(DescargaWorker.nombreUnico(videoId))
                .collect { infos ->
                    val info = infos.lastOrNull() ?: return@collect
                    val pct = info.progress.getInt(DescargaWorker.KEY_PROGRESS, 0)
                    val estado = when (info.state) {
                        WorkInfo.State.SUCCEEDED -> DownloadStatus.DONE
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> DownloadStatus.ERROR
                        WorkInfo.State.RUNNING -> DownloadStatus.DOWNLOADING
                        else -> DownloadStatus.PENDING
                    }
                    updateProgress(videoId, if (estado == DownloadStatus.DONE) 100 else pct, estado)
                }
        }
    }

    fun deleteDownload(songId: String) {
        viewModelScope.launch {
            val cancion = songDao.getById(songId)
            // La ruta se lee de la BD (la canción que tiene la pantalla puede ser de
            // antes de descargarla, sin ruta) y solo se borra si está en la carpeta
            // de descargas: una ruta de un respaldo nunca decide qué archivo se borra.
            runCatching { DescargaWorker.archivoDescargado(context, cancion?.localPath)?.delete() }
            // Borra también la letra .lrc descargada junto al audio (D1/D2).
            runCatching { DescargaWorker.archivoLetra(context, songId).delete() }
            // Restaura como YOUTUBE para que vuelva a poder transmitirse online.
            cancion?.let {
                songDao.upsert(
                    it.copy(
                        isDownloaded = false, localPath = null, downloadQuality = 0,
                        origen = OrigenCancion.YOUTUBE.name, fuente = songId
                    )
                )
            }
        }
    }

    private fun updateProgress(id: String, pct: Int, status: DownloadStatus) {
        _downloads.value = _downloads.value.toMutableMap().apply {
            put(id, DownloadProgress(id, pct, status))
        }
    }

    private fun parseDuracion(d: String): Long {
        val p = d.split(":").mapNotNull { it.toLongOrNull() }
        return when (p.size) {
            2 -> (p[0] * 60 + p[1]) * 1000
            3 -> (p[0] * 3600 + p[1] * 60 + p[2]) * 1000
            else -> 0L
        }
    }
}
