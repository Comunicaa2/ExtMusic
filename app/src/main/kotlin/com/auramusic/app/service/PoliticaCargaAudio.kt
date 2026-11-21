package com.auramusic.app.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException

/**
 * Lo guardado en la caché de una canción es de otro archivo que YouTube ya no entrega.
 * Seguir leyendo mezclaría bytes de dos archivos: hay que vaciar esa caché y empezar
 * de nuevo (lo hace MusicService.onPlayerError).
 */
class FormatoCambiado(videoId: String) : IOException("El archivo de $videoId cambió")

/**
 * NewPipe no pudo dar el audio de la canción (ya lo intentó 3 veces). Suele ser una
 * canción que YouTube no entrega (borrada, bloqueada en el país…): ExoPlayer la
 * reintentaba 12 veces más, ~23 s por canción antes de pasar a la siguiente.
 */
class AudioNoDisponible(videoId: String) : IOException("No se pudo resolver el audio de $videoId")

/** Como la política normal, pero estos dos errores no se reintentan: repetir no los arregla. */
@OptIn(UnstableApi::class)
class PoliticaCargaAudio : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (esFormatoCambiado(info.exception) || esAudioNoDisponible(info.exception)) C.TIME_UNSET
        else super.getRetryDelayMsFor(info)
}

private inline fun <reified T : Throwable> contiene(error: Throwable?): Boolean =
    generateSequence(error) { it.cause }.take(8).any { it is T }

fun esFormatoCambiado(error: Throwable?): Boolean = contiene<FormatoCambiado>(error)
fun esAudioNoDisponible(error: Throwable?): Boolean = contiene<AudioNoDisponible>(error)
