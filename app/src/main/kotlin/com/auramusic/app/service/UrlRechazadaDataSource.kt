package com.auramusic.app.service

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Si YouTube rechaza la URL de una canción (403/410), avisa con [onRechazada] para
 * que se descarte la URL guardada de ese videoId (precarga) antes de que ExoPlayer
 * reintente. Sin esto, ExoPlayer reintentaba la MISMA URL caducada para siempre y
 * el reproductor se quedaba "cargando" hasta 10 minutos (StuckPlayerException).
 *
 * Va por encima del ResolvingDataSource: ve la uri "ytstream:VIDEOID" original y,
 * como el siguiente reintento vuelve a pasar por el resolver, obtiene una URL nueva.
 */
@OptIn(UnstableApi::class)
class UrlRechazadaDataSource(
    private val upstream: DataSource,
    private val onRechazada: (videoId: String) -> Unit
) : DataSource {

    class Factory(
        private val upstream: DataSource.Factory,
        private val onRechazada: (videoId: String) -> Unit
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            UrlRechazadaDataSource(upstream.createDataSource(), onRechazada)
    }

    private var videoId: String? = null

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        videoId = dataSpec.uri.takeIf { it.scheme == MusicService.ESQUEMA_YT }?.schemeSpecificPart
        return vigilando { upstream.open(dataSpec) }
    }

    // Los trozos siguientes se abren dentro de read() (TrozosDataSource), así que
    // un 403 también puede llegar aquí.
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        vigilando { upstream.read(buffer, offset, length) }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    private inline fun <T> vigilando(bloque: () -> T): T =
        try {
            bloque()
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 403 || e.responseCode == 410) videoId?.let(onRechazada)
            throw e
        }
}
