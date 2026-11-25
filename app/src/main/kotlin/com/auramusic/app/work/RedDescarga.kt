package com.auramusic.app.work

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * Restricción de red de las descargas de canciones.
 *
 * Con `NetworkType.CONNECTED`/`UNMETERED`, Android (JobScheduler y el propio
 * WorkManager) exige además una red *validada*: si su comprobación de internet
 * falla, la red no cuenta aunque funcione. Pasa con DNS privado tipo AdGuard (el J7
 * de pruebas) y en redes que bloquean la comprobación de Google: la descarga se
 * quedaba en espera para siempre. Aquí se pide la misma red (con internet y, si está
 * "Descargar solo con WiFi", sin límite de datos) pero sin exigir que esté validada.
 * Si de verdad no hay internet, la descarga falla y se puede reintentar.
 */
fun restriccionesDescarga(soloWifi: Boolean): Constraints {
    val tipo = if (soloWifi) NetworkType.UNMETERED else NetworkType.CONNECTED
    // Pedir una red concreta solo es posible desde Android 9; antes, como siempre.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return Constraints.Builder().setRequiredNetworkType(tipo).build()
    }
    val red = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        // Por defecto el builder exige "no VPN": con una VPN activa no habría red válida.
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .apply { if (soloWifi) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
        .build()
    return Constraints.Builder().setRequiredNetworkRequest(red, tipo).build()
}
