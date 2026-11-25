package com.auramusic.app.actualizador

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Entrega la APK verificada al instalador de Android. Antes comprueba lo que de
 * verdad decide si la actualización es buena: mismo paquete, versionCode mayor
 * y la misma firma que la app instalada (el SHA-256 solo dice que llegó entera).
 */
object InstaladorApk {

    /** null si se puede instalar; si no, el motivo. Lee la APK: llamar fuera del hilo principal. */
    fun verificar(ctx: Context, apk: File): ErrorActualizacion? {
        val datos = FirmaPropia.leerApk(ctx, apk) ?: return ErrorActualizacion.APK_NO_VALIDA
        val propias = FirmaPropia.huellasInstaladas(ctx)
        return when {
            datos.paquete != ctx.packageName -> ErrorActualizacion.APK_NO_VALIDA
            datos.versionCode <= FirmaPropia.versionInstalada(ctx) -> ErrorActualizacion.APK_NO_VALIDA
            datos.huellas.isEmpty() || datos.huellas != propias -> ErrorActualizacion.APK_NO_VALIDA
            FirmaPropia.HUELLA_GITHUB !in datos.huellas -> ErrorActualizacion.APK_NO_VALIDA
            else -> null
        }
    }

    /** ¿El usuario ya dejó a ExtMusic instalar apps ("Permitir de esta fuente")? */
    fun tienePermiso(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun abrirAjustesPermiso(activity: Activity) {
        val pantalla = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
        try { activity.startActivity(pantalla) }
        catch (_: ActivityNotFoundException) {
            // Alguna capa del fabricante no tiene la pantalla por app: la general.
            runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
        }
    }

    /** Abre el instalador del sistema; Android pide la confirmación. */
    fun instalar(activity: Activity, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.actualizacion", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { activity.startActivity(intent) }.isSuccess
    }

    /**
     * Borra lo que ya no sirve: APKs de una versión igual o anterior a la instalada
     * (la actualización ya se hizo) y `.part` huérfanos, salvo [descargando].
     */
    fun limpiar(ctx: Context, descargando: Boolean) {
        val instalada = FirmaPropia.versionInstalada(ctx)
        DescargaActualizacionWorker.carpeta(ctx).listFiles()?.forEach { f ->
            val sobra = when {
                f.name.endsWith(".part") -> !descargando
                f.name.endsWith(".apk") -> (FirmaPropia.leerApk(ctx, f)?.versionCode ?: 0L) <= instalada
                else -> true
            }
            if (sobra) f.delete()
        }
    }
}
