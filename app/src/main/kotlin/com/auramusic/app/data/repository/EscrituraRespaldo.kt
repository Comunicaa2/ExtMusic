package com.auramusic.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Escribe un backup sin tocar el archivo elegido mientras se genera. Hasta la 1.4.2
 * el destino se abría (y se vaciaba) antes de generar el contenido: si algo fallaba
 * o Android cerraba la app, la copia anterior quedaba en 10 bytes.
 *
 * 1. Todo va a un temporal de la caché, que se lee entero (gzip comprueba su CRC).
 * 2. Solo entonces se abre el destino y se copian los bytes.
 * 3. Se relee el destino y se compara.
 *
 * Queda un hueco que no se puede cerrar con el selector de Android: si la app muere
 * durante esa copia final, el destino puede quedar cortado.
 */
object EscrituraRespaldo {
    private const val TAG = "Respaldo"
    private const val PREFIJO = "respaldo-"
    private const val SUFIJO = ".tmp"

    /** [generar] escribe el contenido sin comprimir; aquí se comprime con gzip. */
    fun escribir(contexto: Context, destino: Uri, generar: (OutputStream) -> Unit) {
        var temporal: File? = null
        var destinoAbierto = false
        try {
            // Restos de una exportación que no terminó (la app se cerró a mitad).
            contexto.cacheDir.listFiles { f -> f.name.startsWith(PREFIJO) && f.name.endsWith(SUFIJO) }
                ?.forEach { it.delete() }
            val listo = File.createTempFile(PREFIJO, SUFIJO, contexto.cacheDir).also { temporal = it }
            GZIPOutputStream(listo.outputStream()).use(generar)
            GZIPInputStream(listo.inputStream()).use { huella(it) }   // falla si no se puede leer entero
            val esperada = listo.inputStream().use { huella(it) }

            val salida = contexto.contentResolver.openOutputStream(destino, "wt")
                ?: throw IOException("No se pudo abrir el archivo de destino")
            destinoAbierto = true
            salida.use { s -> listo.inputStream().use { it.copyTo(s) } }

            // Algunos destinos (nube) no dejan releer: entonces no hay con qué comparar.
            val escrita = runCatching { contexto.contentResolver.openInputStream(destino)?.use { huella(it) } }
                .onFailure { Log.w(TAG, "No se pudo releer la copia: ${it.javaClass.simpleName}") }
                .getOrNull()
            if (escrita != null && escrita != esperada) throw IOException("La copia guardada no coincide")
        } catch (e: Throwable) {
            if (!destinoAbierto) borrarSiEstaVacio(contexto, destino)
            throw e
        } finally {
            temporal?.delete()
        }
    }

    // El selector crea el archivo antes de que la app escriba: si era nuevo y no se
    // llegó a abrir, no se deja un backup vacío. Uno con contenido no se toca nunca.
    private fun borrarSiEstaVacio(contexto: Context, destino: Uri) {
        runCatching {
            val vacio = contexto.contentResolver.openFileDescriptor(destino, "r")?.use { it.statSize == 0L } == true
            if (vacio && DocumentsContract.isDocumentUri(contexto, destino)) {
                DocumentsContract.deleteDocument(contexto.contentResolver, destino)
            }
        }
    }

    private fun huella(entrada: InputStream): String {
        val resumen = MessageDigest.getInstance("SHA-256")
        val bloque = ByteArray(64 * 1024)
        while (true) {
            val n = entrada.read(bloque)
            if (n < 0) break
            resumen.update(bloque, 0, n)
        }
        return resumen.digest().joinToString("") { "%02x".format(it) }
    }
}
