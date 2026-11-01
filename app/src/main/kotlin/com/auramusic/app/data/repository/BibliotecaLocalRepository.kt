package com.auramusic.app.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.auramusic.app.R
import com.auramusic.app.data.db.SongEntity
import com.auramusic.app.domain.model.OrigenCancion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Escanea la biblioteca de audio del teléfono con MediaStore (Fase 1).
 *
 * Devuelve cada pista como un [SongEntity] con origen LOCAL, de modo que el
 * resto de la app (cola, reproductor, UI) la trate igual que una de YouTube.
 * El `id` y la `fuente` son el mismo Uri de contenido: es estable y se le puede
 * pasar directo a ExoPlayer sin resolver nada por red.
 */
@Singleton
class BibliotecaLocalRepository @Inject constructor(
    @ApplicationContext private val contexto: Context,
    private val preferencias: RepositorioPreferencias
) {
    // Base de las carátulas de álbum que expone MediaStore.
    private val uriCaratulaAlbum = Uri.parse("content://media/external/audio/albumart")

    suspend fun escanearCanciones(): List<SongEntity> = withContext(Dispatchers.IO) {
        val canciones = mutableListOf<PistaLocal>()

        val columnas = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA   // ruta, solo para preferir copias fuera de Download
        )
        // Solo lo marcado como música (descarta tonos, grabaciones de voz, etc.) y,
        // si en Ajustes se eligió una carpeta, solo lo que está dentro de ella.
        val carpeta = rutaDeCarpeta(preferencias.ajustes.first().carpetasEscaneo)
        val seleccion = "${MediaStore.Audio.Media.IS_MUSIC} != 0" +
            if (carpeta != null) " AND ${MediaStore.Audio.Media.DATA} LIKE ?" else ""
        val argumentos = carpeta?.let { arrayOf("$it/%") }
        val orden = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        contexto.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            columnas, seleccion, argumentos, orden
        )?.use { cursor ->
            val colId       = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val colTitulo   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val colArtista  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val colAlbum    = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val colAlbumId  = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val colDuracion = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val colTamano   = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val colRuta     = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val idMediaStore = cursor.getLong(colId)
                // Uri de contenido reproducible directamente por ExoPlayer.
                val uriCancion = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, idMediaStore
                ).toString()

                val albumId = cursor.getLong(colAlbumId)
                val uriCaratula = ContentUris.withAppendedId(uriCaratulaAlbum, albumId).toString()

                // MediaStore usa "<unknown>" cuando no hay etiqueta de artista.
                val artistaRaw = cursor.getString(colArtista) ?: ""
                val artista = if (artistaRaw == "<unknown>")
                    contexto.getString(R.string.artista_desconocido) else artistaRaw

                canciones += PistaLocal(tamano = cursor.getLong(colTamano), ruta = cursor.getString(colRuta) ?: "", cancion = SongEntity(
                    id = uriCancion,
                    title = cursor.getString(colTitulo) ?: contexto.getString(R.string.cancion_sin_titulo),
                    artist = artista,
                    artistId = "",
                    album = cursor.getString(colAlbum) ?: "",
                    thumbnailUrl = uriCaratula,
                    durationMs = cursor.getLong(colDuracion),
                    origen = OrigenCancion.LOCAL.name,
                    fuente = uriCancion
                ))
            }
        }
        sinDuplicados(canciones)
    }

    /**
     * Ruta en disco de la carpeta elegida en Ajustes (un Uri de árbol del selector
     * del sistema), o null si es "todo el teléfono".
     * "…/tree/primary%3AMusic" → "/storage/emulated/0/Music";
     * "…/tree/1A2B-3C4D%3AMusica" (tarjeta SD) → "/storage/1A2B-3C4D/Musica".
     */
    private fun rutaDeCarpeta(uriCarpeta: String): String? {
        if (uriCarpeta.isBlank()) return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uriCarpeta)) }
            .getOrNull() ?: return null
        val volumen = docId.substringBefore(':')
        val relativa = docId.substringAfter(':', "").trim('/')
        val raiz = if (volumen.equals("primary", ignoreCase = true)) "/storage/emulated/0"
                   else "/storage/$volumen"
        return if (relativa.isEmpty()) raiz else "$raiz/$relativa"
    }

    private class PistaLocal(val tamano: Long, val ruta: String, val cancion: SongEntity)

    /**
     * Quita las copias de una misma canción (p. ej. el mismo FLAC en Music/ y en
     * Download/Music/). Son la misma si coinciden título, artista y duración (±2 s):
     * el título solo no basta, hay canciones distintas con el mismo nombre.
     * De cada grupo se queda la de mayor tamaño (suele ser la de más calidad) y, a
     * igual tamaño, la que no está en Download. Los archivos no se tocan.
     */
    private fun sinDuplicados(pistas: List<PistaLocal>): List<SongEntity> =
        pistas.groupBy { p ->
            Triple(
                p.cancion.title.trim().lowercase(),
                p.cancion.artist.trim().lowercase(),
                (p.cancion.durationMs + 1000) / 2000   // tramos de 2 s
            )
        }.values.map { copias ->
            copias.maxWith(
                compareBy<PistaLocal> { it.tamano }
                    .thenBy { !it.ruta.contains("/Download/", ignoreCase = true) }
            ).cancion
        }.sortedBy { it.title.lowercase() }
}
