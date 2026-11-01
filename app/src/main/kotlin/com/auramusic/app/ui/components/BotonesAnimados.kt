package com.auramusic.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.auramusic.app.ui.theme.Movimiento

/**
 * Icono de play/pausa que cambia con escala y fundido (150 ms) en vez de saltar.
 * Lo usan el reproductor, el mini y el visor de letras.
 */
@Composable
fun IconoPlayPausa(reproduciendo: Boolean, descripcion: String?, modifier: Modifier = Modifier, tint: Color? = null) {
    AnimatedContent(
        targetState = reproduciendo,
        transitionSpec = {
            (scaleIn(tween(Movimiento.CORTA), initialScale = 0.6f) + fadeIn(tween(Movimiento.CORTA)))
                .togetherWith(scaleOut(tween(Movimiento.CORTA), targetScale = 0.6f) + fadeOut(tween(Movimiento.CORTA)))
        },
        label = "playPausa"
    ) { suena ->
        val icono = if (suena) Icons.Default.Pause else Icons.Default.PlayArrow
        if (tint != null) Icon(icono, descripcion, modifier, tint = tint) else Icon(icono, descripcion, modifier)
    }
}

/**
 * Icono que cambia entre dos (p. ej. repetir y repetir una) con fundido, y color
 * animado (aleatorio y repetir encendidos o apagados).
 */
@Composable
fun IconoConEstado(icono: ImageVector, descripcion: String?, color: Color, modifier: Modifier = Modifier) {
    val tinte by animateColorAsState(color, tween(Movimiento.MEDIA), label = "tinteIcono")
    AnimatedContent(
        targetState = icono,
        transitionSpec = { fadeIn(tween(Movimiento.CORTA)).togetherWith(fadeOut(tween(Movimiento.CORTA))) },
        label = "iconoEstado"
    ) { Icon(it, descripcion, modifier, tint = tinte) }
}

/**
 * Corazón de "Me gusta": color animado y, al tocarlo, un rebote (1 → 1,25 → 1).
 * Cambiar de canción no rebota: solo cuenta el cambio de [megusta] en la misma [clave].
 */
@Composable
fun CorazonMeGusta(megusta: Boolean, clave: Any?, descripcion: String, apagado: Color, onClick: () -> Unit) {
    val tinte by animateColorAsState(if (megusta) Color.Red else apagado, tween(Movimiento.MEDIA), label = "tinteCorazon")
    val escala = remember { Animatable(1f) }
    var anterior by remember { mutableStateOf(clave to megusta) }
    LaunchedEffect(clave, megusta) {
        val (claveAntes, gustabaAntes) = anterior
        anterior = clave to megusta
        if (claveAntes == clave && gustabaAntes != megusta && megusta) {
            escala.snapTo(1f)
            escala.animateTo(1.25f, tween(110))
            escala.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
        }
    }
    IconButton(onClick = onClick) {
        Icon(if (megusta) Icons.Default.Favorite else Icons.Default.FavoriteBorder, descripcion,
            Modifier.graphicsLayer { scaleX = escala.value; scaleY = escala.value }, tint = tinte)
    }
}
