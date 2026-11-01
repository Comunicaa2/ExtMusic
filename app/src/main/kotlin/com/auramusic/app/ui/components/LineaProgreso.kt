package com.auramusic.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Línea de progreso fina con el aspecto de LinearProgressIndicator de M3 (extremos
 * redondeados y un hueco de 4 dp antes de la pista), pero sin semántica: el
 * progreso se lee solo al dibujar, así que un tic no recompone nada ni invalida la
 * semántica. Con un servicio de accesibilidad activo (en HyperOS viene uno de
 * serie), cada cambio de semántica hacía que Compose recorriera el árbol entero.
 * Para barras decorativas; donde el progreso deba ser accesible, usar la de M3.
 * La altura de la línea es la del [modifier].
 */
@Composable
fun LineaProgreso(progreso: () -> Float, color: Color, colorPista: Color, modifier: Modifier = Modifier) {
    Spacer(modifier.drawBehind {
        val p = progreso().coerceIn(0f, 1f)
        val grosor = size.height
        val hueco = (4.dp.toPx() + grosor) / size.width
        val inicioPista = p + minOf(p, hueco)
        if (inicioPista <= 1f) tramo(inicioPista, 1f, colorPista)
        tramo(0f, p, color)
    })
}

private fun DrawScope.tramo(desde: Float, hasta: Float, color: Color) {
    if (hasta - desde <= 0f) return
    val grosor = size.height
    val ltr = layoutDirection == LayoutDirection.Ltr
    val a = (if (ltr) desde else 1f - hasta) * size.width
    val b = (if (ltr) hasta else 1f - desde) * size.width
    val radio = grosor / 2
    val y = size.height / 2
    drawLine(
        color, Offset(a.coerceIn(radio, size.width - radio), y), Offset(b.coerceIn(radio, size.width - radio), y),
        strokeWidth = grosor, cap = StrokeCap.Round
    )
}
