package com.auramusic.app.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import com.auramusic.app.domain.model.CACHE_AUDIO_POCO_ESPACIO_MB
import com.auramusic.app.domain.model.ESPACIO_LIBRE_MIN_MB
import java.io.File
import java.util.TreeSet

/**
 * Como LeastRecentlyUsedCacheEvictor de Media3 (se va lo que hace más tiempo que no
 * suena), pero con dos cambios:
 *  - El tope se puede cambiar con la app abierta ([cambiarMaximo]) y se aplica al
 *    momento; antes hacía falta reiniciar.
 *  - Si al teléfono le queda poco espacio libre, el audio temporal baja a 32 MB.
 * Al abrir la caché se recorre lo guardado y, si pasa del tope, se recorta ya.
 *
 * SimpleCache llama a estos métodos con su propio candado; [cambiarMaximo] lo toma
 * también, así que la lista nunca se toca desde dos hilos a la vez.
 */
@OptIn(UnstableApi::class)
class LimiteCacheAudio(maximoBytes: Long, private val carpeta: File) : CacheEvictor {

    @Volatile private var maximo = maximoBytes
    private val porUso = TreeSet<CacheSpan> { a, b ->
        val d = a.lastTouchTimestamp - b.lastTouchTimestamp
        if (d == 0L) a.compareTo(b) else if (d < 0) -1 else 1
    }
    private var ocupado = 0L

    fun cambiarMaximo(cache: Cache, bytes: Long) {
        synchronized(cache) {
            maximo = bytes
            recortar(cache, 0)
        }
    }

    private fun limite(): Long {
        val libre = runCatching { carpeta.usableSpace }.getOrDefault(Long.MAX_VALUE)
        return if (libre < ESPACIO_LIBRE_MIN_MB * MB) minOf(maximo, CACHE_AUDIO_POCO_ESPACIO_MB * MB) else maximo
    }

    private fun recortar(cache: Cache, necesario: Long) {
        val tope = limite()
        while (ocupado + necesario > tope && porUso.isNotEmpty()) {
            cache.removeSpan(porUso.first())
        }
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {}

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) recortar(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        porUso.add(span)
        ocupado += span.length
        recortar(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (porUso.remove(span)) ocupado -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private companion object { const val MB = 1024L * 1024 }
}
