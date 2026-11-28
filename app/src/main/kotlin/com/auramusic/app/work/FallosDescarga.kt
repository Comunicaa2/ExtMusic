package com.auramusic.app.work

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.auramusic.app.MainActivity
import com.auramusic.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Por qué no se pudo descargar una canción. */
enum class MotivoFallo { ESPACIO, OTRO }

data class FalloDescarga(val titulo: String, val motivo: MotivoFallo, val cuando: Long)

/** No cabe lo que falta por bajar (se comprueba antes de escribir). */
class SinEspacio : IOException("Sin espacio")

/** true si el error (o alguna de sus causas) es falta de espacio en el teléfono. */
fun esFaltaDeEspacio(e: Throwable?): Boolean {
    var t = e
    var vueltas = 0
    while (t != null && vueltas++ < 10) {
        if (t is SinEspacio) return true
        if (t is ErrnoException && t.errno == OsConstants.ENOSPC) return true
        if (t.message?.contains("ENOSPC") == true) return true
        t = t.cause
    }
    return false
}

/**
 * Canciones cuya última descarga falló. Se guarda en disco: el menú ⋮ sigue
 * ofreciendo "Reintentar descarga" aunque se cierre la app, hasta que se vuelva a
 * pedir o se descargue bien. Una cancelación no pasa por aquí.
 *
 * Al fallar avisa con UNA notificación que va contando (una colección entera no
 * llena la barra). Sin notificaciones, si la app está a la vista, un aviso breve.
 */
@Singleton
class RegistroFallosDescarga @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _fallos = MutableStateFlow(leer())
    val fallos: StateFlow<Map<String, FalloDescarga>> = _fallos

    private val principal = Handler(Looper.getMainLooper())
    private val pendientesAviso = mutableListOf<FalloDescarga>()

    @Synchronized
    fun marcar(videoId: String, titulo: String, motivo: MotivoFallo) {
        val f = FalloDescarga(titulo, motivo, System.currentTimeMillis())
        prefs.edit().putString(videoId, "${motivo.name}|${f.cuando}|$titulo").apply()
        _fallos.value = _fallos.value + (videoId to f)
        avisar(f)
    }

    @Synchronized
    fun quitar(videoId: String) {
        if (videoId !in _fallos.value) return
        prefs.edit().remove(videoId).apply()
        _fallos.value = _fallos.value - videoId
    }

    private fun leer(): Map<String, FalloDescarga> = prefs.all.mapNotNull { (id, v) ->
        val partes = (v as? String)?.split('|', limit = 3) ?: return@mapNotNull null
        if (partes.size < 3) return@mapNotNull null
        val motivo = runCatching { MotivoFallo.valueOf(partes[0]) }.getOrDefault(MotivoFallo.OTRO)
        id to FalloDescarga(partes[2], motivo, partes[1].toLongOrNull() ?: 0L)
    }.toMap()

    private fun avisar(nuevo: FalloDescarga) {
        val nm = NotificationManagerCompat.from(ctx)
        // Canal propio, con importancia normal (sale entre las notificaciones y suena): el
        // de "Descargas" es silencioso y un fallo quedaba escondido entre las silenciadas.
        val gestor = ctx.getSystemService(NotificationManager::class.java)
        gestor?.createNotificationChannel(
            NotificationChannel(CANAL_FALLOS, ctx.getString(R.string.desc_canal_fallos), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val canal = gestor?.getNotificationChannel(CANAL_FALLOS)
        val seVe = nm.areNotificationsEnabled() &&
            (canal == null || canal.importance != NotificationManager.IMPORTANCE_NONE)
        if (seVe) notificar(nm) else avisoBreve(nuevo)
    }

    /** Una sola notificación con los fallos de los últimos minutos. */
    private fun notificar(nm: NotificationManagerCompat) {
        val desde = System.currentTimeMillis() - VENTANA_MS
        val recientes = _fallos.value.values.filter { it.cuando >= desde }
        if (recientes.isEmpty()) return
        val todosEspacio = recientes.all { it.motivo == MotivoFallo.ESPACIO }
        val motivo = ctx.getString(if (todosEspacio) R.string.desc_fallo_espacio else R.string.desc_notif_menu)
        val (titulo, texto) = if (recientes.size == 1)
            ctx.getString(R.string.desc_notif_fallo) to "${recientes[0].titulo} · $motivo"
        else ctx.getString(R.string.desc_notif_fallos, recientes.size) to motivo
        val abrir = PendingIntent.getActivity(
            ctx, ID_NOTIFICACION,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CANAL_FALLOS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        try { nm.notify(ID_NOTIFICACION, n) } catch (_: SecurityException) { }
    }

    private var primerPendiente = 0L
    private val mostrarAviso = Runnable {
        val juntos = synchronized(this) { pendientesAviso.toList().also { pendientesAviso.clear() } }
        if (juntos.isEmpty() || !aLaVista()) return@Runnable
        val espacio = juntos.all { it.motivo == MotivoFallo.ESPACIO }
        val texto = if (juntos.size == 1) ctx.getString(
            if (espacio) R.string.desc_aviso_fallo_espacio else R.string.desc_aviso_fallo, juntos[0].titulo)
        else ctx.getString(
            if (espacio) R.string.desc_aviso_fallos_espacio else R.string.desc_aviso_fallos, juntos.size)
        Toast.makeText(ctx, texto, Toast.LENGTH_LONG).show()
    }

    /**
     * Sin notificaciones: un Toast si la app está a la vista. Espera a que pasen
     * [JUNTAR_MS] sin fallos nuevos (como mucho [MAX_ESPERA_MS]) y los cuenta juntos:
     * una colección que falla entera no encadena un aviso por canción.
     */
    private fun avisoBreve(f: FalloDescarga) {
        val ahora = SystemClock.uptimeMillis()
        if (pendientesAviso.isEmpty()) primerPendiente = ahora
        pendientesAviso += f
        principal.removeCallbacks(mostrarAviso)
        principal.postAtTime(mostrarAviso, minOf(ahora + JUNTAR_MS, primerPendiente + MAX_ESPERA_MS))
    }

    private fun aLaVista(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private companion object {
        const val PREFS = "fallos_descarga"
        const val CANAL_FALLOS = "descargas_fallidas"
        const val ID_NOTIFICACION = 7_402
        const val VENTANA_MS = 15 * 60 * 1000L
        const val JUNTAR_MS = 2_000L
        const val MAX_ESPERA_MS = 10_000L
    }
}
