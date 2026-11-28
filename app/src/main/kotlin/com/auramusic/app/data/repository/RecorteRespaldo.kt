package com.auramusic.app.data.repository

import com.auramusic.app.data.db.SongEntity

/**
 * Lo que se puede dejar fuera de un backup que pasa de los límites de importar.
 * Hasta la 1.4.2 se exportaba igual y luego la app rechazaba su propia copia.
 * Solo se usa cuando se pasa: una biblioteca normal se exporta entera.
 */
object RecorteRespaldo {
    /**
     * Las canciones que no se pueden perder: las descargadas, las de alguna playlist
     * y las que tienen algo en algún perfil ([conEstado]: Me gusta, escuchas, última
     * vez o "No recomendar"). El resto es catálogo que la app puede volver a armar.
     */
    fun conUso(canciones: List<SongEntity>, conEstado: Set<String>, enPlaylists: Set<String>): List<SongEntity> =
        canciones.filter { it.isDownloaded || it.id in conEstado || it.id in enPlaylists }

    /** [maximo] elementos de la cola alrededor del actual, con la nueva posición de este. */
    fun <T> alrededorDe(cola: List<T>, indice: Int, maximo: Int): Pair<List<T>, Int> {
        if (cola.size <= maximo) return cola to indice
        val actual = indice.coerceIn(0, cola.lastIndex)
        val inicio = (actual - maximo / 2).coerceIn(0, cola.size - maximo)
        return cola.subList(inicio, inicio + maximo) to (actual - inicio)
    }
}
