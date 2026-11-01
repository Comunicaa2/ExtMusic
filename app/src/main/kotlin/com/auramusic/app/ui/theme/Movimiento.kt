package com.auramusic.app.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.snap
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Ritmo común de las animaciones de ExtMusic (curvas de Material 3), para que
 * todo se mueva igual: entra frenando, sale acelerando, y nada dura más de lo
 * necesario. Compose ya escala estas duraciones con la "Escala de animación" del
 * sistema; con "Quitar animaciones" (escala 0) terminan al instante.
 */
object Movimiento {
    const val CORTA = 150
    const val MEDIA = 300
    const val LARGA = 450

    /** Entra frenando (emphasized decelerate). */
    val Entrada = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Sale acelerando (emphasized accelerate). */
    val Salida = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** Cambios en el sitio (standard). */
    val Estandar = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun <T> entrada(ms: Int = MEDIA): FiniteAnimationSpec<T> = tween(ms, easing = Entrada)
    fun <T> salida(ms: Int = CORTA): FiniteAnimationSpec<T> = tween(ms, easing = Salida)
    fun <T> estandar(ms: Int = MEDIA): FiniteAnimationSpec<T> = tween(ms, easing = Estandar)
    /** Para fundidos de entrada: pareja (las curvas enfatizadas llegaban casi opacas en el primer fotograma). */
    fun <T> fundido(ms: Int = MEDIA): FiniteAnimationSpec<T> = tween(ms, easing = LinearEasing)
    /**
     * Para lo que vuela o se desplaza mucho (la portada, el mini y la barra): arranca
     * suave. Las curvas enfatizadas recorren un tercio en los primeros 40 ms y, si se
     * pierde un fotograma al empezar, se ve un salto.
     */
    fun <T> vuelo(ms: Int = LARGA): FiniteAnimationSpec<T> = tween(ms, easing = FastOutSlowInEasing)
}

/**
 * Cambio de canción: lo nuevo entra desde el lado hacia el que se avanzó
 * ([direccion] 1 = siguiente, -1 = anterior) y lo viejo sale por el otro. El
 * desplazamiento es corto (1/[fraccion] del ancho): se nota el sentido sin marear.
 *
 * Como el eje compartido de Material: lo viejo se apaga en los primeros 90 ms y lo
 * nuevo aparece después, así nunca se ven los dos títulos superpuestos.
 */
fun <S> AnimatedContentTransitionScope<S>.cambioDeCancion(
    direccion: Int, fraccion: Int = 6, conEscala: Boolean = false
): ContentTransform {
    val entra = slideInHorizontally(Movimiento.estandar(Movimiento.LARGA)) { it * direccion / fraccion } + apareceTras()
    val sale = slideOutHorizontally(Movimiento.estandar(Movimiento.LARGA)) { -it * direccion / fraccion } + seApagaYa()
    return ((if (conEscala) entra + scaleIn(Movimiento.entrada(Movimiento.LARGA), initialScale = 0.92f) else entra)
        togetherWith sale).using(SizeTransform(clip = false))
}

/** Cambio de canción en vertical y discreto (mini reproductor). */
fun <S> AnimatedContentTransitionScope<S>.cambioVertical(): ContentTransform =
    (slideInVertically(Movimiento.estandar(Movimiento.MEDIA)) { it / 3 } + apareceTras())
        .togetherWith(slideOutVertically(Movimiento.estandar(Movimiento.MEDIA)) { -it / 3 } + seApagaYa())
        .using(SizeTransform(clip = false))

// Mitades del cruce: la saliente se apaga en 90 ms; la entrante aparece a partir de ahí.
private const val CRUCE = 90
private fun seApagaYa() = fadeOut(tween(CRUCE, easing = FastOutLinearInEasing))
private fun apareceTras() = fadeIn(tween(Movimiento.MEDIA - CRUCE, delayMillis = CRUCE, easing = LinearOutSlowInEasing))

/**
 * true si el sistema tiene "Quitar animaciones" (escala de animación 0). Se usa
 * para apagar lo decorativo o continuo (fondo que gira, portada que vuela); el
 * resto ya lo acorta Compose solo. Se provee una vez en la raíz.
 */
val LocalMovimientoReducido = compositionLocalOf { false }

@Composable
fun rememberMovimientoReducido(): Boolean {
    val contexto = LocalContext.current
    val resolver = contexto.contentResolver
    fun leer() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reducido by remember { mutableStateOf(leer()) }
    DisposableEffect(resolver) {
        val observador = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reducido = leer() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observador)
        onDispose { resolver.unregisterContentObserver(observador) }
    }
    return reducido
}

// ─── Portada que vuela entre el mini reproductor y el reproductor ─────────────

/** Escena de elementos compartidos de la app (null fuera de ella). */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalTransicionCompartida = compositionLocalOf<SharedTransitionScope?> { null }

/** Alcance de la animación de entrada y salida de la pantalla del reproductor. */
val LocalAlcanceReproductor = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Las dos portadas usan estas esquinas: en el mini (46 dp) se ve como un círculo. */
val FormaPortada = RoundedCornerShape(24.dp)

/**
 * La portada del mini y la del reproductor son "la misma": al abrir el reproductor
 * la pequeña vuela y crece hasta su sitio, y al cerrarlo vuelve. Sin escena, sin
 * [alcance] o con "Quitar animaciones", no hace nada. Funciona en los dos sentidos
 * porque el reproductor es una capa: el Inicio y el mini siguen montados debajo.
 *
 * Va en la tarjeta de la portada, DENTRO del AnimatedContent del cambio de canción
 * ([clave] = id de la canción) y ANTES de los modificadores de tamaño. Puesta por
 * fuera, el AnimatedContent recortaba su tamaño con retraso mientras el vuelo lo
 * volvía a medir y el vuelo empezaba como una franja aplastada.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.portadaCompartida(alcance: AnimatedVisibilityScope?, clave: Any?): Modifier {
    val escena = LocalTransicionCompartida.current
    if (escena == null || alcance == null || clave == null || LocalMovimientoReducido.current) return this
    return with(escena) {
        // sharedBounds (y no sharedElement): la portada se vuelve a medir al marco en
        // cada fotograma. Con sharedElement, el primer
        // fotograma del cierre dibujaba la portada grande sin encoger, recortada.
        this@portadaCompartida.sharedBounds(
            rememberSharedContentState(key = "portada-$clave"),
            animatedVisibilityScope = alcance,
            // Solo se ve la que llega, opaca desde el principio: fundidas a la vez se
            // veían translúcidas a medio vuelo.
            enter = EnterTransition.None,
            exit = fadeOut(snap()),
            boundsTransform = { _, _ -> Movimiento.vuelo() },
            // Se vuelve a medir (no se escala: la redonda del mini se veía como un
            // círculo enorme) y sin recorte extra: la tarjeta ya redondea sus esquinas,
            // y el recorte iba un fotograma por delante del contenido al empezar.
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
        )
    }
}

/**
 * Encoge el contenido en la maqueta (no solo al dibujar, como scale) y lo centra en
 * el sitio que ocuparía entero. Para la portada del reproductor en pausa: el vuelo
 * toma el tamaño de la maqueta, así que con scale empezaba creciendo de golpe del 94
 * al 100 %. [escala] se lee al medir: cambiarla no recompone.
 */
fun Modifier.escalaEnLayout(escala: () -> Float): Modifier = layout { medible, limites ->
    val s = escala().coerceIn(0.5f, 1f)
    val alto = if (limites.hasBoundedHeight) (limites.maxHeight * s).roundToInt() else limites.maxHeight
    val hijo = medible.measure(limites.copy(minWidth = 0, maxWidth = (limites.maxWidth * s).roundToInt(),
        minHeight = 0, maxHeight = alto))
    val ancho = limites.maxWidth
    val total = (hijo.height / s).roundToInt().coerceIn(limites.minHeight, limites.maxHeight)
    layout(ancho, total) { hijo.place((ancho - hijo.width) / 2, (total - hijo.height) / 2) }
}
