package com.auramusic.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.auramusic.app.R
import com.auramusic.app.data.api.InnertubeClient
import com.auramusic.app.data.api.LyricsResult
import com.auramusic.app.data.db.SongDao
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.data.repository.RepositorioPreferencias
import com.auramusic.app.domain.model.codec
import com.auramusic.app.domain.model.formatoDescarga
import com.auramusic.app.domain.model.OrigenCancion
import com.auramusic.app.service.TrozosDataSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Descarga real con WorkManager (Bug 4 / D1). Corre en segundo plano (sobrevive al
 * cierre de la app), obtiene la URL con el mismo motor de la Fase 3 y escribe el
 * audio en la CACHÉ INTERNA privada (filesDir/downloads), invisible en el
 * explorador de archivos. Junto al audio guarda la letra de LRCLIB en un .lrc con
 * el mismo nombre, para que las letras funcionen OFFLINE (D1/D2). Al terminar deja
 * la canción con origen = LOCAL e isDownloaded = true para tratarla como local.
 */
@HiltWorker
class DescargaWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val innertube: InnertubeClient,
    private val songDao: SongDao,
    private val repositorioPreferencias: RepositorioPreferencias
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return Result.failure()
        val title   = inputData.getString(KEY_TITLE) ?: ""
        val artist  = inputData.getString(KEY_ARTIST) ?: ""
        val thumb   = inputData.getString(KEY_THUMB) ?: ""
        val durMs   = inputData.getLong(KEY_DURATION, 0L)

        val ajustes = repositorioPreferencias.ajustes.first()
        val calidad = ajustes.downloadQuality
        val formato = calidad.formatoDescarga

        // 1) URL del stream con el motor de F3, aplicando la calidad elegida.
        // El códec y el formato salen de la calidad (AudioQuality.codec), así un
        // .m4a es AAC y un .opus es Opus.
        val audioInfo = innertube.getAudioUrl(videoId, calidad, calidad.codec)
            ?: return Result.failure()

        // 2) Destino: caché interna privada filesDir/downloads (no visible al usuario).
        val dir = carpetaDescargas(applicationContext)
        val archivoAudio = File(dir, "$videoId.${formato.extension}")

        // Mejora Y: notificación de progreso del sistema.
        val nm = NotificationManagerCompat.from(applicationContext)
        crearCanal()
        val notifId = videoId.hashCode()
        val nombre = title.ifBlank { videoId }
        val builder = NotificationCompat.Builder(applicationContext, CANAL_DESCARGAS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(nombre)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, 0, true)
        notificar(nm, notifId, builder.build())

        // 3) Descarga en trozos de 2 MB, reportando progreso (WorkManager + notificación).
        // Una sola petición abierta va limitada por YouTube a ~la velocidad de
        // reproducción (~2 min por canción); en trozos acotados no hay límite.
        try {
            val okhttp = OkHttpClient()
            var total = -1L   // tamaño del archivo, sacado del primer Content-Range
            var hechos = 0L
            var ultimoPct = -5
            archivoAudio.outputStream().use { out ->
                while (total < 0 || hechos < total) {
                    val hasta = hechos + TrozosDataSource.TAMANO_TROZO - 1
                    val pedido = Request.Builder().url(audioInfo.url)
                        .header("Range", "bytes=$hechos-$hasta").build()
                    val recibidos = okhttp.newCall(pedido).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        if (total < 0) total = resp.header("Content-Range")
                            ?.substringAfterLast('/')?.toLongOrNull()
                            ?: (resp.body?.contentLength() ?: -1L).takeIf { resp.code == 200 } ?: -1L
                        var n = 0L
                        resp.body?.byteStream()?.use { n = it.copyTo(out) }
                        n
                    }
                    hechos += recibidos
                    if (total > 0) {
                        val pct = (hechos * 100 / total).toInt()
                        setProgress(workDataOf(KEY_PROGRESS to pct))
                        if (pct - ultimoPct >= 5) {   // throttle de la notificación
                            ultimoPct = pct
                            builder.setProgress(100, pct, false)
                            notificar(nm, notifId, builder.build())
                        }
                    }
                    // Sin total conocido: un trozo incompleto (o vacío) es el final.
                    if (recibidos == 0L || (total < 0 && recibidos < TrozosDataSource.TAMANO_TROZO)) break
                    // Si el servidor ignoró el Range (200 con todo), ya está completo.
                    if (total > 0 && hechos >= total) break
                }
            }
        } catch (e: Exception) {
            archivoAudio.delete()   // no dejar un archivo a medias que parezca válido
            nm.cancel(notifId)
            return Result.failure()
        }

        // Notificación final "Descarga completada".
        val fin = NotificationCompat.Builder(applicationContext, CANAL_DESCARGAS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(applicationContext.getString(R.string.desc_notif_completada))
            .setContentText(nombre)
            .setAutoCancel(true)
            .build()
        notificar(nm, notifId, fin)

        // 3.5) D1: letra de LRCLIB junto al audio (mismo nombre, .lrc) para offline.
        if (ajustes.descargarLetras) {
            runCatching {
                val res = innertube.getLyrics(title, artist, (durMs / 1000).toInt())
                if (res != null) File(dir, "$videoId.lrc").writeText(lyricsALrc(res))
            }
        }

        // 4) Guardar como LOCAL para que se reproduzca offline y aparezca en descargas.
        // Preserva la fila existente (like, conteos, explicit…) si ya estaba en BD;
        // así descargar al dar me gusta (P) no des-marca el like.
        val rutaGuardada = "file://${archivoAudio.absolutePath}"
        val previa = songDao.getById(videoId)
        val cancion = previa?.copy(
            isDownloaded = true, localPath = rutaGuardada, downloadQuality = audioInfo.bitrate,
            origen = OrigenCancion.LOCAL.name, fuente = rutaGuardada
        ) ?: SongEntity(
            id = videoId, title = title, artist = artist, artistId = "", album = "",
            thumbnailUrl = thumb, durationMs = durMs,
            isDownloaded = true, localPath = rutaGuardada, downloadQuality = audioInfo.bitrate,
            origen = OrigenCancion.LOCAL.name, fuente = rutaGuardada
        )
        songDao.upsert(cancion)
        return Result.success()
    }

    // Serializa la letra a formato .lrc. Si es sincronizada, antepone [mm:ss.xx].
    private fun lyricsALrc(r: LyricsResult): String = buildString {
        for (l in r.lines) {
            if (r.isSynced) {
                val min = l.timeMs / 60000
                val sec = (l.timeMs % 60000) / 1000
                val cent = (l.timeMs % 1000) / 10
                append("[%02d:%02d.%02d]".format(min, sec, cent))
            }
            append(l.text).append("\n")
        }
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(
                    NotificationChannel(
                        CANAL_DESCARGAS,
                        applicationContext.getString(R.string.sec_descargas),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    private fun notificar(nm: NotificationManagerCompat, id: Int, n: android.app.Notification) {
        runCatching { nm.notify(id, n) }   // no-op si falta el permiso POST_NOTIFICATIONS
    }

    companion object {
        private const val CANAL_DESCARGAS = "descargas"
        const val KEY_VIDEO_ID = "videoId"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_THUMB = "thumb"
        const val KEY_DURATION = "duration"
        const val KEY_PROGRESS = "progress"
        fun nombreUnico(videoId: String) = "descarga_$videoId"

        /** Carpeta de caché interna de descargas (audio + .lrc). Privada. */
        fun carpetaDescargas(ctx: Context): File =
            File(ctx.filesDir, "downloads").also { it.mkdirs() }

        /** Ruta del .lrc local de una canción descargada (si existe). */
        fun archivoLetra(ctx: Context, videoId: String): File =
            // El id puede venir de un respaldo: sin "/" ni "..", nunca sale de la carpeta.
            File(carpetaDescargas(ctx), videoId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".lrc")

        /**
         * El archivo de una ruta guardada ("file://…") solo si está dentro de la
         * carpeta de descargas; null para cualquier otra ruta (p. ej. de un
         * respaldo manipulado), que nunca se debe leer ni borrar como descarga.
         */
        fun archivoDescargado(ctx: Context, ruta: String?): File? {
            if (ruta == null || !ruta.startsWith("file://")) return null
            val archivo = runCatching { File(android.net.Uri.parse(ruta).path ?: return null).canonicalFile }
                .getOrNull() ?: return null
            val carpeta = carpetaDescargas(ctx).canonicalFile
            return archivo.takeIf { it.parentFile == carpeta }
        }
    }
}
