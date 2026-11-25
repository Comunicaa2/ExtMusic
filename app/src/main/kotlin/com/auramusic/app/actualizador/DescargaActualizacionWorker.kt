package com.auramusic.app.actualizador

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.auramusic.app.MainActivity
import com.auramusic.app.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Descarga la APK de una versión a `filesDir/actualizacion/ExtMusic-X.apk.part`,
 * calculando su SHA-256 mientras baja. Solo si el tamaño y la huella coinciden con
 * los publicados se renombra a `.apk`. No instala: eso lo pide el usuario desde
 * una pantalla visible (Android no deja abrir el instalador desde segundo plano).
 *
 * Aparte de DescargaWorker a propósito: son otras reglas, otra carpeta y otro ciclo de vida.
 */
class DescargaActualizacionWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val version = inputData.getString(K_VERSION) ?: return Result.failure()
        val url = inputData.getString(K_URL) ?: return Result.failure()
        val sha256 = inputData.getString(K_SHA256) ?: return Result.failure()
        val tamano = inputData.getLong(K_TAMANO, -1L)
        if (!url.startsWith(ValidarRelease.PREFIJO_DESCARGA) || tamano <= 0) return Result.failure(fallo(ErrorActualizacion.CATALOGO))

        val carpeta = carpeta(applicationContext)
        // Solo una actualización guardada a la vez.
        carpeta.listFiles()?.forEach { it.delete() }
        val parcial = File(carpeta, "ExtMusic-$version.apk.part")
        val final = archivoApk(applicationContext, version)

        var terminado = false
        try {
            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val resumen = MessageDigest.getInstance("SHA-256")
            var bajados = 0L
            http.newCall(Request.Builder().url(url).header("User-Agent", "ExtMusic-actualizador").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                // GitHub redirige al almacén de archivos: tiene que seguir siendo HTTPS de GitHub.
                val destino = r.request.url
                if (!destino.isHttps || !(destino.host == "github.com" || destino.host.endsWith(".githubusercontent.com")))
                    throw IOException("Destino inesperado: ${destino.host}")
                val cuerpo = r.body
                parcial.outputStream().use { salida ->
                    cuerpo.byteStream().use { entrada ->
                        val buf = ByteArray(64 * 1024)
                        var ultimoAviso = -1L
                        while (true) {
                            val n = entrada.read(buf)
                            if (n < 0) break
                            bajados += n
                            if (bajados > tamano) return Result.failure(fallo(ErrorActualizacion.DANADA))
                            resumen.update(buf, 0, n)
                            salida.write(buf, 0, n)
                            // Progreso cada ~2 %, no en cada bloque.
                            if (bajados - ultimoAviso >= tamano / 50) {
                                ultimoAviso = bajados
                                setProgress(workDataOf(K_BAJADOS to bajados))
                            }
                        }
                    }
                }
            }
            val huella = resumen.digest().joinToString("") { "%02x".format(it) }
            if (bajados != tamano || huella != sha256) {
                Log.w(TAG, "La descarga no coincide: $bajados/$tamano bytes")
                return Result.failure(fallo(ErrorActualizacion.DANADA))
            }
            if (!parcial.renameTo(final)) throw IOException("No se pudo guardar la APK")
            terminado = true
            avisarLista(version)
            return Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Descarga fallida: ${e.javaClass.simpleName}")
            return Result.failure(fallo(ErrorActualizacion.RED))
        } finally {
            // Cancelada, fallida o dañada: no queda nada a medias.
            if (!terminado) parcial.delete()
        }
    }

    // Con la versión: un fallo viejo no se muestra en otra versión más nueva.
    private fun fallo(e: ErrorActualizacion) =
        workDataOf(K_ERROR to e.name, K_VERSION to inputData.getString(K_VERSION))

    /** "Lista para instalar": al tocarla se abre la app con la hoja del actualizador. */
    private fun avisarLista(version: String) {
        val ctx = applicationContext
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        // minSdk 26: los canales existen siempre.
        val canal = NotificationChannel(CANAL, ctx.getString(R.string.act_canal), NotificationManager.IMPORTANCE_DEFAULT)
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(canal)
        val abrir = PendingIntent.getActivity(
            ctx, ID_NOTIFICACION,
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_ABRIR, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(ctx.getString(R.string.act_notif_lista, version))
            .setContentText(ctx.getString(R.string.act_notif_lista_texto))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        try { nm.notify(ID_NOTIFICACION, n) } catch (_: SecurityException) { }
    }

    companion object {
        private const val TAG = "Actualizador"
        const val NOMBRE = "actualizacion-apk"
        const val K_VERSION = "version"
        const val K_URL = "url"
        const val K_SHA256 = "sha256"
        const val K_TAMANO = "tamano"
        const val K_BAJADOS = "bajados"
        const val K_ERROR = "error"
        const val EXTRA_ABRIR = "abrir_actualizacion"
        private const val CANAL = "actualizaciones"
        const val ID_NOTIFICACION = 7_401

        fun carpeta(ctx: Context): File = File(ctx.filesDir, "actualizacion").also { it.mkdirs() }
        fun archivoApk(ctx: Context, version: String): File = File(carpeta(ctx), "ExtMusic-$version.apk")
    }
}
