package com.auramusic.app.service

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Pide el audio en trozos de [tamanoTrozo] bytes en vez de en una sola petición.
 *
 * YouTube limita una petición abierta (bytes=0-) a ~la velocidad de reproducción
 * (~32 KB/s medido: 108 s para una canción de 3,5 MB). Con peticiones acotadas
 * no hay límite (la misma canción en trozos de 2 MB: <0,1 s). Es lo que hacen
 * NewPipe y similares.
 *
 * Para ExoPlayer sigue siendo un único stream: al agotarse un trozo se abre el
 * siguiente de forma transparente. La URL ya llega resuelta (ResolvingDataSource
 * está por encima), así que cambiar de trozo no vuelve a llamar a NewPipe.
 */
@OptIn(UnstableApi::class)
class TrozosDataSource(
    private val upstream: HttpDataSource,
    private val tamanoTrozo: Long = TAMANO_TROZO
) : DataSource {

    class Factory(
        private val upstream: HttpDataSource.Factory,
        private val tamanoTrozo: Long = TAMANO_TROZO
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            TrozosDataSource(upstream.createDataSource(), tamanoTrozo)
    }

    private var spec: DataSpec? = null
    private var posicion = 0L                  // próximo byte (absoluto) a leer
    private var fin = C.LENGTH_UNSET.toLong()  // byte final exclusivo, si se conoce
    private var pedidoTrozo = 0L               // bytes pedidos en el trozo actual
    private var leidoTrozo = 0L                // bytes recibidos del trozo actual
    private var abierto = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        posicion = dataSpec.position
        fin = if (dataSpec.length != C.LENGTH_UNSET.toLong()) posicion + dataSpec.length
              else C.LENGTH_UNSET.toLong()
        abrirTrozo()
        // Con el total del archivo (Content-Range) se sabe dónde parar sin pedir
        // un trozo más allá del final (eso daría 416).
        if (fin == C.LENGTH_UNSET.toLong()) totalDelArchivo()?.let { fin = it }
        return if (fin == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else fin - dataSpec.position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (fin != C.LENGTH_UNSET.toLong() && posicion >= fin) return C.RESULT_END_OF_INPUT
        var n = upstream.read(buffer, offset, length)
        if (n == C.RESULT_END_OF_INPUT) {
            // Sin total conocido: un trozo incompleto es el final del archivo, y uno
            // más largo de lo pedido es un servidor que ignoró el Range (ya mandó todo).
            val trozoDistinto = leidoTrozo != pedidoTrozo
            if (fin == C.LENGTH_UNSET.toLong() && trozoDistinto) return C.RESULT_END_OF_INPUT
            if (fin != C.LENGTH_UNSET.toLong() && posicion >= fin) return C.RESULT_END_OF_INPUT
            cerrarUpstream()
            abrirTrozo()
            n = upstream.read(buffer, offset, length)
            if (n == C.RESULT_END_OF_INPUT) return n
        }
        posicion += n
        leidoTrozo += n
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        cerrarUpstream()
        spec = null
    }

    private fun abrirTrozo() {
        val base = spec ?: error("TrozosDataSource sin abrir")
        pedidoTrozo = if (fin == C.LENGTH_UNSET.toLong()) tamanoTrozo
                      else minOf(tamanoTrozo, fin - posicion)
        leidoTrozo = 0
        upstream.open(base.subrange(posicion - base.position, pedidoTrozo))
        abierto = true
    }

    private fun cerrarUpstream() {
        if (abierto) { abierto = false; upstream.close() }
    }

    // "Content-Range: bytes 0-2097151/3500000" → 3500000
    private fun totalDelArchivo(): Long? =
        upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.firstOrNull()
            ?.substringAfterLast('/')?.trim()?.toLongOrNull()

    companion object {
        /** 2 MB: medido como el más rápido (512 KB también evita el límite). */
        const val TAMANO_TROZO = 2L * 1024 * 1024
    }
}
