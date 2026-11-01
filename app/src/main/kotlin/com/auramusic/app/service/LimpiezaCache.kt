package com.auramusic.app.service

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache

/**
 * Quita de la caché de audio lo que ya está guardado en el teléfono: copias de
 * descargas y de música local (cualquier clave que no sea "ytstream:") y el
 * streaming de canciones que después se descargaron. Solo toca la caché; las
 * descargas, los me gusta y los respaldos no se tocan.
 *
 * Se llama al crear el servicio, antes de que suene nada: borrar una canción de
 * la caché mientras ExoPlayer la escribe podría cortar la reproducción.
 * Devuelve los bytes liberados.
 */
@OptIn(UnstableApi::class)
object LimpiezaCache {
    fun quitarDuplicados(cache: Cache, descargadas: Set<String>): Long {
        val prefijo = "${MusicService.ESQUEMA_YT}:"
        var liberados = 0L
        for (clave in cache.keys.toList()) {
            val duplicada = !clave.startsWith(prefijo) || clave.removePrefix(prefijo) in descargadas
            if (!duplicada) continue
            val bytes = cache.getCachedSpans(clave).sumOf { it.length }
            if (runCatching { cache.removeResource(clave) }.isSuccess) liberados += bytes
        }
        return liberados
    }
}
