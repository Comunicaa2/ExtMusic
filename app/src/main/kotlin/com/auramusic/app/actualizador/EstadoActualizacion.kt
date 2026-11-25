package com.auramusic.app.actualizador

/** Lo que se ve en la fila de Ajustes y en la hoja del actualizador. */
sealed interface EstadoActualizacion {
    /** Copia de F-Droid o debug, o aún sin saberlo: no se muestra nada. */
    data object Oculto : EstadoActualizacion
    data class AlDia(val ultimaComprobacion: Long) : EstadoActualizacion
    data object Comprobando : EstadoActualizacion
    data class Disponible(val version: VersionPublicada) : EstadoActualizacion
    data class Descargando(val version: VersionPublicada, val bajados: Long) : EstadoActualizacion
    data class Lista(val version: VersionPublicada) : EstadoActualizacion
    /** [enDescarga]: falló la descarga o la APK (tocar reintenta); si no, la comprobación. */
    data class Fallo(val tipo: ErrorActualizacion, val version: VersionPublicada?, val enDescarga: Boolean) : EstadoActualizacion
}

enum class ErrorActualizacion {
    /** Sin conexión o GitHub no respondió. */
    RED,
    /** GitHub pidió esperar (403/429). */
    LIMITE,
    /** La release no tiene lo necesario (APK, tamaño, huella). */
    CATALOGO,
    /** La descarga no coincide con la publicada (tamaño o SHA-256). */
    DANADA,
    /** La APK no es de ExtMusic, no es más nueva o no lleva la misma firma. */
    APK_NO_VALIDA
}
