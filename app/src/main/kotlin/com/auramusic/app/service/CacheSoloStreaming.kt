package com.auramusic.app.service

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Solo el streaming de YouTube ("ytstream:VIDEOID") pasa por la caché de audio.
 * Lo que ya está en el teléfono (descargas file://, música del teléfono content://)
 * se lee directo: antes CacheDataSource copiaba también esos archivos a la caché
 * al reproducirlos y cada canción descargada ocupaba el doble.
 */
@OptIn(UnstableApi::class)
class CacheSoloStreaming(
    private val conCache: DataSource,
    private val directo: DataSource
) : DataSource {

    class Factory(
        private val conCache: DataSource.Factory,
        private val directo: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            CacheSoloStreaming(conCache.createDataSource(), directo.createDataSource())
    }

    private var actual: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        conCache.addTransferListener(transferListener)
        directo.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val fuente = if (dataSpec.uri.scheme == MusicService.ESQUEMA_YT) conCache else directo
        actual = fuente
        return fuente.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(actual).read(buffer, offset, length)

    override fun getUri(): Uri? = actual?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = actual?.responseHeaders ?: emptyMap()

    override fun close() {
        try { actual?.close() } finally { actual = null }
    }
}
