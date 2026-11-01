package com.auramusic.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest

/**
 * Número de veces que ha vuelto la conexión (lo cuenta PlayerViewModel). Las
 * portadas lo incluyen en su petición: si una falló por estar sin red, al volver
 * la conexión la petición cambia y se vuelve a intentar sola (antes se quedaba
 * gris hasta cambiar de pantalla).
 */
val LocalIntentoRed = compositionLocalOf { 0 }

/**
 * Modelo para AsyncImage que se reintenta al volver la red. El parámetro no
 * forma parte de la clave de la caché en memoria, así que las portadas que ya
 * cargaron no se vuelven a descargar ni parpadean.
 */
@Composable
fun conReintento(data: Any?): Any? {
    val intento = LocalIntentoRed.current
    val contexto = LocalContext.current
    return remember(data, intento) {
        data?.let { ImageRequest.Builder(contexto).data(it).setParameter("intentoRed", intento, null).build() }
    }
}

/**
 * Como [conReintento], pero siempre al mismo tamaño (544 px, el de las portadas de
 * YT Music): el mini reproductor y el reproductor piden así la MISMA imagen de la
 * caché en memoria. Si no, al volar la portada de uno a otro se veía un círculo
 * gris mientras se decodificaba la grande.
 *
 * Además usan una clave fija en esa caché y la toman como placeholder: la portada
 * que se crea de nuevo (el mini al cerrar el reproductor) pinta la imagen ya en el
 * primer fotograma, en vez de una tarjeta gris mientras Coil la busca.
 */
@Composable
fun portadaCompartidaModelo(data: Any?): Any? {
    val intento = LocalIntentoRed.current
    val contexto = LocalContext.current
    return remember(data, intento) {
        data?.let {
            val clave = "portada:$it"
            ImageRequest.Builder(contexto).data(it).size(544)
                .memoryCacheKey(clave).placeholderMemoryCacheKey(clave)
                .setParameter("intentoRed", intento, null).build()
        }
    }
}
