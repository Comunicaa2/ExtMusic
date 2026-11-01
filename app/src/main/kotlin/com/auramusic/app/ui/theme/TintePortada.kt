package com.auramusic.app.ui.theme

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
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
 */
@Composable
fun rememberTintePortada(url: String?, activo: Boolean = true): State<Color> {
    val base = MaterialTheme.colorScheme.background
    val contexto = LocalContext.current
    var objetivo by remember { mutableStateOf(base) }
    val temaOscuro = base.luminance() < 0.5f
    LaunchedEffect(url, activo, base) {
        objetivo = if (!activo || !temaOscuro || url.isNullOrBlank()) base else withContext(Dispatchers.Default) {
            val peticion = ImageRequest.Builder(contexto).data(url).size(128).allowHardware(false).build()
            val bitmap = (contexto.imageLoader.execute(peticion) as? SuccessResult)
                ?.drawable?.let { it as? BitmapDrawable }?.bitmap
            bitmap?.let { b ->
                val p = Palette.from(b).maximumColorCount(16).generate()
                // Primero los tonos con color (el "apagado oscuro" solo salía casi negro);
                // oscurecerParaTexto los baja lo justo para que el blanco se lea.
                val color = p.darkVibrantSwatch?.rgb ?: p.mutedSwatch?.rgb ?: p.darkMutedSwatch?.rgb ?: p.dominantSwatch?.rgb
                color?.let { Color(oscurecerParaTexto(it)) }
            } ?: base
        }
    }
    // Algo más lento que el cambio de portada: el color acompaña sin distraer.
    return animateColorAsState(objetivo, tween(600, easing = Movimiento.Estandar), label = "tintePortada")
}

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
