package com.auramusic.app.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * LiquidGlass real — usa blur nativo de Compose (Modifier.blur)
 * disponible desde Compose 1.3 sin librerías externas.
 *
 * Capas:
 * 1. El fondo ya tiene su propio blur aplicado desde fuera (background)
 * 2. Overlay translúcido con gradiente para el efecto vidrio
 * 3. Borde difuminado que simula la refracción
 */
@Composable
fun LiquidGlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    glassAlpha: Float = 0.15f,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = glassAlpha + 0.05f),
                    0.4f to Color.White.copy(alpha = glassAlpha),
                    1.0f to Color.Black.copy(alpha = 0.15f)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.35f),
                    0.5f to Color.White.copy(alpha = 0.10f),
                    1f to Color.White.copy(alpha = 0.05f)
                ),
                shape = shape
            ),
        content = content
    )
}

/**
 * DarkGlassBox — panel oscuro semitransparente con borde sutil.
 * Úsalo para el mini player, menús flotantes y tarjetas sobre fondos.
 */
@Composable
fun DarkGlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable BoxScope.() -> Unit
) {
    // Panel translúcido que respeta el TEMA: en Claro claro, en Oscuro oscuro,
    // en AMOLED negro. (Antes usaba colores fijos oscuros — Bug del tema.)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                shape = shape
            ),
        content = content
    )
}

/**
 * BlurredBackground — aplica blur al contenido debajo.
 * Combínalo con LiquidGlassBox para el efecto completo.
 * Solo disponible en Compose 1.3+
 */
fun Modifier.blurBackground(radius: Dp = 20.dp): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        this.blur(radius)
    else this
