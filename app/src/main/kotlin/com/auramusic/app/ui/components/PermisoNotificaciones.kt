package com.auramusic.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pide el permiso de notificaciones (Android 13+) la primera vez que se abre la app:
 * sin él no salen las notificaciones de descargas ni la de "Actualización lista" (la
 * del reproductor sí, Android la exime). Solo se pide una vez; si se rechaza, no se
 * insiste (se puede dar luego desde los ajustes del sistema).
 *
 * Devuelve true cuando ya no hay que esperar por él (concedido, ya pedido antes o
 * Android < 13), para que el diálogo de "Reproducción en segundo plano" salga después
 * y no los dos a la vez.
 */
@Composable
fun permisoNotificacionesResuelto(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    val ctx = LocalContext.current
    var resuelto by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resuelto = true }
    LaunchedEffect(Unit) {
        if (resuelto) return@LaunchedEffect
        // Preferencias propias (el DataStore de la app solo admite una instancia), leídas
        // fuera del hilo principal.
        val yaPedido = withContext(Dispatchers.IO) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getBoolean(K_PEDIDO, false).also { if (!it) prefs.edit().putBoolean(K_PEDIDO, true).apply() }
        }
        if (yaPedido) resuelto = true
        else pedir.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    return resuelto
}

private const val PREFS = "primera_vez"
private const val K_PEDIDO = "notificaciones_pedido"
