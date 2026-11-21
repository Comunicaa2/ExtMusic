package com.auramusic.app.ui.theme

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tinte de fondo sacado de una portada o foto (idea tomada de SimpMusic): un
 * color de la imagen con saturación, oscurecido lo justo para que el texto
 * blanco se lea (contraste ≥ 4.5:1). Sin imagen, con [activo] en false o con un
 * tema claro (el texto es oscuro y no se leería), es el fondo del tema (Monet).
 * Cambia con una transición suave.
 *
 * Rendimiento (medido en un ZTE Blade A31 Lite): el reproductor se compone de
 * nuevo cada vez que se abre, y antes el tinte empezaba en el color base y se
 * animaba 600 ms hasta el de la portada justo durante la apertura. Ahora el color
 * de cada portada se recuerda ([coloresCalculados]) y se empieza en él: al reabrir
 * no hay que calcular ni animar nada. Quien lo use debe leer el State al DIBUJAR
 * (drawBehind), no al componer, para que la animación no recomponga la pantalla.
 * Con [animar] en false el color cambia de golpe (p. ej. el reproductor oculto: su
 * fundido de 600 ms pedía fotogramas que nadie veía).
 */
@Composable
fun rememberTintePortada(url: String?, activo: Boolean = true, animar: Boolean = true): State<Color> {
    val base = MaterialTheme.colorScheme.background
    val contexto = LocalContext.current
    val temaOscuro = base.luminance() < 0.5f
    val usaPortada = activo && temaOscuro && !url.isNullOrBlank()
    var objetivo by remember { mutableStateOf(if (usaPortada) coloresCalculados[url] ?: base else base) }
    LaunchedEffect(url, activo, base) {
        objetivo = if (!usaPortada) base else coloresCalculados[url] ?: withContext(Dispatchers.Default) {
            val peticion = ImageRequest.Builder(contexto).data(url).size(128).allowHardware(false).build()
            val bitmap = (contexto.imageLoader.execute(peticion) as? SuccessResult)
                ?.drawable?.let { it as? BitmapDrawable }?.bitmap
            bitmap?.let { b ->
                val p = Palette.from(b).maximumColorCount(16).generate()
                // Primero los tonos con color (el "apagado oscuro" solo salía casi negro);
                // oscurecerParaTexto los baja lo justo para que el blanco se lea.
                val color = p.darkVibrantSwatch?.rgb ?: p.mutedSwatch?.rgb ?: p.darkMutedSwatch?.rgb ?: p.dominantSwatch?.rgb
                color?.let { Color(oscurecerParaTexto(it)).also { c -> coloresCalculados.put(url!!, c) } }
            } ?: base
        }
    }
    // Algo más lento que el cambio de portada: el color acompaña sin distraer.
    return animateColorAsState(objetivo, if (animar) tween(600, easing = Movimiento.Estandar) else snap(), label = "tintePortada")
}

/** Tinte ya calculado por portada (las últimas 64): reabrir no repite Palette ni anima. */
private val coloresCalculados = android.util.LruCache<String, Color>(64)

// Baja el brillo hasta que el blanco tenga contraste suficiente encima.
private fun oscurecerParaTexto(argb: Int): Int {
    var c = argb
    var paso = 0
    while (ColorUtils.calculateContrast(Color.White.toArgb(), c) < 4.5 && paso < 12) {
        c = ColorUtils.blendARGB(c, android.graphics.Color.BLACK, 0.15f)
        paso++
    }
    return c
}
