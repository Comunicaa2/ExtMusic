package com.auramusic.app.service

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * "Normalizar volumen": compresor + limitador sobre la sesión de audio del
 * reproductor, con DynamicsProcessing (Android 9+).
 *
 * No conoce el volumen de cada canción (NewPipe no da el loudnessDb de YouTube),
 * así que iguala en tiempo real: comprime lo que pasa del umbral, sube el conjunto
 * con ganancia de compensación y el limitador evita que algo sature. Las canciones
 * flojas suenan más fuertes y las fuertes no destacan tanto. Vale igual para
 * streaming, descargas y archivos locales.
 *
 * En Android < 9 no hace nada (el ajuste ni se muestra).
 */
class NormalizadorVolumen {

    private var efecto: Any? = null   // DynamicsProcessing; Any para no cargar la clase en API < 28
    private var sesion = 0
    private var activo = false

    /** Llamar cuando ExoPlayer cree o cambie su sesión de audio. */
    fun setSesion(audioSessionId: Int) {
        if (audioSessionId == sesion) return
        sesion = audioSessionId
        reconstruir()
    }

    fun setActivo(valor: Boolean) {
        if (valor == activo) return
        activo = valor
        reconstruir()
    }

    fun liberar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) liberarEfecto()
    }

    private fun reconstruir() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        liberarEfecto()
        if (!activo || sesion == 0) return
        efecto = runCatching { crear(sesion) }
            .onFailure { Log.w(TAG, "No se pudo activar la normalización: ${it.message}") }
            .getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun liberarEfecto() {
        (efecto as? DynamicsProcessing)?.let { runCatching { it.enabled = false; it.release() } }
        efecto = null
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun crear(sesion: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CANALES,
            false, 0,   // pre-EQ: no
            true, 1,    // compresor: una sola banda (todo el espectro)
            false, 0,   // post-EQ: no
            true        // limitador: sí
        )
            .setMbcAllChannelsTo(DynamicsProcessing.Mbc(true, true, 1).apply {
                setBand(0, DynamicsProcessing.MbcBand(
                    true,
                    20_000f,   // corte: la banda cubre todo lo audible
                    10f,       // ataque (ms)
                    300f,      // relajación (ms): lenta para no "bombear"
                    3f,        // ratio 3:1
                    -24f,      // umbral (dBFS)
                    6f,        // rodilla (dB)
                    -90f,      // puerta de ruido: desactivada en la práctica
                    1f,        // expansor: desactivado
                    0f,        // ganancia previa (dB)
                    6f         // ganancia de compensación (dB): sube lo flojo
                ))
            })
            .setLimiterAllChannelsTo(DynamicsProcessing.Limiter(
                true, true, 0,
                1f,     // ataque (ms)
                60f,    // relajación (ms)
                10f,    // ratio
                -1f,    // umbral (dBFS): nada pasa de -1 dB
                0f      // ganancia posterior (dB)
            ))
            .build()
        return DynamicsProcessing(0, sesion, config).apply { enabled = true }
    }

    private companion object {
        const val TAG = "NormalizadorVolumen"
        const val CANALES = 2
    }
}
