package com.auramusic.app.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule

/**
 * Liquid glass con la librería Backdrop (Kyant0, Apache-2.0), la misma que usa
 * SimpMusic. El contenido de la pantalla se graba en un [Backdrop]
 * (Modifier.layerBackdrop en MainActivity) y cada pieza de cristal lo dibuja
 * debajo de sí: desenfocado, con más saturación (vibrancy) y refractado como una
 * lupa cerca de los bordes (lens), más un brillo fino en el borde.
 *
 * La refracción usa RuntimeShader (Android 13+); por debajo se usa la versión
 * sólida, con la misma forma, para que el diseño no cambie.
 */
fun cristalDisponible(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Cápsula de cristal. Con [fondo] = null (efecto desactivado o Android < 13) es
 * una cápsula sólida de color [solido] con un borde sutil.
 *
 * @param tinte velo de color sobre el cristal: cuanto más opaco, más legible el
 *   texto encima y menos se ve el fondo.
 */
@Composable
fun Modifier.capsulaCristal(
    fondo: Backdrop?,
    tinte: Color,
    solido: Color,
    borde: Color,
    desenfoque: Dp = 8.dp,
    alturaRefraccion: Dp = 20.dp,
    refraccion: Dp = 28.dp
): Modifier =
    if (fondo == null) {
        this.background(solido, Capsule()).border(0.5.dp, borde, Capsule())
    } else {
        this.drawBackdrop(
            backdrop = fondo,
            shape = { Capsule() },
            effects = {
                vibrancy()
                blur(desenfoque.toPx())
                lens(alturaRefraccion.toPx(), refraccion.toPx())
            },
            onDrawSurface = { drawRect(tinte) }
        )
    }
