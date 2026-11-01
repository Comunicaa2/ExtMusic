package com.auramusic.app.service

import android.content.Context
import android.os.Build

/**
 * Recuerda que el audio offload ("Ahorro de batería") falló en este teléfono.
 *
 * Se guarda el fingerprint del firmware, no un simple sí/no: el fallo de MediaTek
 * llegó con una actualización del sistema (HyperOS OS3.0.307) y otra puede
 * arreglarlo. Si cambia el fingerprint, la marca deja de valer y se reintenta.
 */
object OffloadRoto {
    private const val ARCHIVO = "offload_roto"
    private const val CLAVE = "firmware"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    fun estaRoto(ctx: Context): Boolean = prefs(ctx).getString(CLAVE, null) == Build.FINGERPRINT

    fun marcar(ctx: Context) = prefs(ctx).edit().putString(CLAVE, Build.FINGERPRINT).apply()

    /** Al volver a encender el ajuste: el usuario quiere reintentar. */
    fun olvidar(ctx: Context) = prefs(ctx).edit().remove(CLAVE).apply()
}
