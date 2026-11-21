package com.auramusic.app.ui.theme

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Liquid glass con la librería Backdrop (Kyant0, Apache-2.0). El contenido de la
 * pantalla se graba en un [Backdrop] (Modifier.layerBackdrop en MainActivity) y cada
 * pieza de cristal lo dibuja debajo de sí.
 *
 * El efecto y sus animaciones siguen el cristal de SimpMusic (maxrave-dev/SimpMusic,
 * GPL-3.0, como esta app; `LiquidGlassContainer`, `GlassLuminance` y
 * `LiquidGlassTabBar`, de noviembre de 2026), que a su vez adapta el catálogo de Kyant:
 * - Se oscurece según lo que tiene detrás ([rememberLuminanciaCristal]): sobre un
 *   fondo claro no se queda blanco lechoso y el texto se sigue leyendo.
 * - Al pulsarlo crece un poco, refracta más y un brillo sigue al dedo ([PulsacionCristal]).
 * - Brillo en el borde (Highlight) y la gota de la pestaña activa ([GotaPestana]).
 *
 * La refracción usa RuntimeShader (Android 13+); por debajo se usa la versión sólida,
 * con la misma forma, para que el diseño no cambie.
 */
fun cristalDisponible(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Tema oscuro (Oscuro o AMOLED): el velo del cristal es negro; en el claro, blanco. */
@Composable
fun temaOscuro(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * Cápsula de cristal (la barra y el mini reproductor). Con [fondo] = null (efecto
 * desactivado o Android < 13) es una cápsula sólida de color [solido] con un borde sutil.
 *
 * [capa] y [luminancia] son de la propia pieza: el cristal graba en [capa] lo que tiene
 * detrás y [luminancia] (de [rememberLuminanciaCristal] sobre esa capa) gradúa el velo.
 * [escalaPulsado]: cuánto crece al pulsarlo (en piezas anchas, poco).
 */
@Composable
fun Modifier.capsulaCristal(
    fondo: Backdrop?,
    solido: Color,
    borde: Color,
    capa: GraphicsLayer? = null,
    luminancia: State<Float>? = null,
    escalaPulsado: Float = 1.12f,
    forma: Shape = Capsule()
): Modifier {
    if (fondo == null) return this.background(solido, forma).border(0.5.dp, borde, forma)
    val pulsacion = rememberPulsacionCristal()
    val oscuro = temaOscuro()
    return this.cristalInteractivo(
        fondo = fondo, forma = forma, oscuro = oscuro,
        luminancia = { luminancia?.value ?: 0.5f }, capa = capa,
        pulsacion = pulsacion, escalaPulsado = escalaPulsado
    )
}

/**
 * El cristal en sí: desenfoque y velo según la luminancia de detrás, saturación
 * (vibrancy), refracción de lupa en los bordes y la respuesta a la pulsación.
 * [luminancia] se lee al dibujar: cambiarla no recompone.
 */
fun Modifier.cristalInteractivo(
    fondo: Backdrop,
    forma: Shape,
    oscuro: Boolean,
    luminancia: () -> Float,
    capa: GraphicsLayer?,
    pulsacion: PulsacionCristal?,
    escalaPulsado: Float = 1.12f,
    veloMinimo: Float = 0.12f,
    veloMaximo: Float = 0.5f
): Modifier =
    this.drawBackdrop(
        backdrop = fondo,
        shape = { forma },
        highlight = { Highlight.Default },
        effects = {
            val l = (luminancia() * 2f - 1f).let { sign(it) * it * it }
            val p = pulsacion?.progreso ?: 0f
            vibrancy()
            // Brillo y contraste neutros: subirlos dejaba el cristal blanco sobre fondos
            // claros. Lo que oscurece es el velo de onDrawSurface.
            colorControls(0.05f, 1f, 1.5f)
            blur(
                (if (l > 0f) lerp(8.dp.toPx(), 16.dp.toPx(), l) else lerp(8.dp.toPx(), 2.dp.toPx(), -l)) +
                    2.dp.toPx() * p
            )
            // Altura de refracción por debajo de la mitad del alto: si la de arriba y la de
            // abajo se tocan, en una píldora ancha sale una raya oscura en medio.
            lens(size.minDimension / 4f + 2.dp.toPx() * p, size.minDimension / 2f, false)
        },
        onDrawBackdrop = { dibujar ->
            dibujar()
            capa?.record { dibujar() }
        },
        onDrawSurface = {
            // Más velo cuanto más claro es lo de detrás.
            val velo = lerp(veloMinimo, veloMaximo, ((luminancia() - 0.3f) / 0.5f).coerceIn(0f, 1f))
            drawRect((if (oscuro) Color.Black else Color.White).copy(alpha = velo))
            val p = pulsacion?.progreso ?: 0f
            if (p > 0f) drawRect(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.18f * p), Color.Transparent),
                    center = pulsacion?.toque ?: Offset(size.width / 2f, size.height / 2f),
                    radius = size.minDimension * 1.2f
                ),
                blendMode = BlendMode.Plus
            )
        },
        layerBlock = if (pulsacion != null) {
            {
                val escala = lerp(1f, escalaPulsado, pulsacion.progreso)
                scaleX = escala; scaleY = escala
            }
        } else null
    ).then(
        if (pulsacion != null) Modifier.pointerInput(pulsacion) { pulsacion.detectar(this) } else Modifier
    )

/**
 * Pulsación de una pieza de cristal: [progreso] va de 0 a 1 con un muelle mientras se
 * pulsa y [toque] es donde está el dedo (centro del brillo). Solo observa: los clics
 * de lo que hay dentro siguen funcionando.
 */
class PulsacionCristal(private val ambito: CoroutineScope) {
    private val muelle = spring(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.001f)
    private val animacion = Animatable(0f, 0.001f)

    val progreso: Float get() = animacion.value
    var toque by mutableStateOf(Offset.Zero)
        private set

    suspend fun detectar(puntero: PointerInputScope) = with(puntero) {
        observarArrastre(
            alEmpezar = { abajo ->
                toque = abajo.position
                ambito.launch { animacion.animateTo(1f, muelle) }
            },
            alTerminar = { ambito.launch { animacion.animateTo(0f, muelle) } },
            alCancelar = { ambito.launch { animacion.animateTo(0f, muelle) } }
        ) { cambio, _ -> toque = cambio.position }
    }
}

@Composable
fun rememberPulsacionCristal(): PulsacionCristal {
    val ambito = rememberCoroutineScope()
    return remember(ambito) { PulsacionCristal(ambito) }
}

/**
 * Luminancia media (0,3–0,8) de lo que el cristal tiene detrás, grabado en [capa].
 * Se mira cada 2,5 s en una copia de 5×5 píxeles (casi gratis) y solo con la app
 * delante; el cambio se funde en 500 ms.
 */
@Composable
fun rememberLuminanciaCristal(capa: GraphicsLayer, activa: Boolean): State<Float> {
    val muestra = rememberGraphicsLayer()
    val luminancia = remember { Animatable(0.5f) }
    val ciclo = LocalLifecycleOwner.current.lifecycle
    val densidad = LocalDensity.current
    val direccion = LocalLayoutDirection.current
    LaunchedEffect(capa, muestra, activa, ciclo, densidad, direccion) {
        if (!activa) return@LaunchedEffect
        ciclo.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val pixeles = IntArray(LADO_MUESTRA * LADO_MUESTRA)
            while (isActive) {
                val tam = capa.size
                // Una capa no tiene nada grabado hasta que se dibuja por primera vez.
                if (tam.width > 0 && tam.height > 0) {
                    val objetivo = try {
                        muestra.record(densidad, direccion, IntSize(LADO_MUESTRA, LADO_MUESTRA)) {
                            scale(LADO_MUESTRA.toFloat() / tam.width, LADO_MUESTRA.toFloat() / tam.height, Offset.Zero) {
                                drawLayer(capa)
                            }
                        }
                        muestra.toImageBitmap().readPixels(pixeles)
                        (pixeles.sumOf { c ->
                            0.2126 * (c shr 16 and 0xFF) / 255.0 + 0.7152 * (c shr 8 and 0xFF) / 255.0 + 0.0722 * (c and 0xFF) / 255.0
                        } / pixeles.size).toFloat().coerceIn(0.3f, 0.8f)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Se queda el último velo; se reintenta al volver a la app, no en bucle.
                        Log.w("Cristal", "No se pudo medir el fondo: ${e.message}")
                        return@repeatOnLifecycle
                    }
                    if (abs(objetivo - luminancia.targetValue) >= 0.02f) luminancia.animateTo(objetivo, tween(500))
                }
                delay(2_500)
            }
        }
    }
    return luminancia.asState()
}

private const val LADO_MUESTRA = 5

/**
 * La gota de la pestaña activa: [valor] es su posición (en pestañas, continua), se
 * desliza con un muelle, se estira y aplasta con la velocidad ([velocidad]) y al
 * pulsarla se hincha ([escalaX]/[escalaY], [progresoPulsado]). Se puede arrastrar de
 * una pestaña a otra con [modificador]. Es el `DampedDragAnimation` del catálogo de
 * Kyant tal como lo usa SimpMusic.
 */
class GotaPestana(
    private val ambito: CoroutineScope,
    valorInicial: Float,
    private val rango: ClosedFloatingPointRange<Float>,
    private val escalaPulsada: Float,
    private val alEmpezar: GotaPestana.() -> Unit,
    private val alSoltar: GotaPestana.() -> Unit,
    private val alArrastrar: GotaPestana.(dx: Float) -> Unit
) {
    private val muelleValor = spring(1f, 1000f, 0.001f)
    private val muelleVelocidad = spring(0.5f, 300f, 0.01f)
    private val muellePulsado = spring(1f, 1000f, 0.001f)
    private val muelleX = spring(0.6f, 250f, 0.001f)
    private val muelleY = spring(0.7f, 250f, 0.001f)

    private val animValor = Animatable(valorInicial, 0.001f)
    private val animVelocidad = Animatable(0f, 5f)
    private val animPulsado = Animatable(0f, 0.001f)
    private val animX = Animatable(1f, 0.001f)
    private val animY = Animatable(1f, 0.001f)
    private val mutex = MutatorMutex()
    private val rastreador = VelocityTracker()

    val valor: Float get() = animValor.value
    val valorObjetivo: Float get() = animValor.targetValue
    val progresoPulsado: Float get() = animPulsado.value
    val escalaX: Float get() = animX.value
    val escalaY: Float get() = animY.value
    val velocidad: Float get() = animVelocidad.value

    val modificador: Modifier = Modifier.pointerInput(Unit) {
        observarArrastre(
            alEmpezar = { alEmpezar(); pulsar() },
            alTerminar = { alSoltar(); soltar() },
            alCancelar = { alSoltar(); soltar() }
        ) { _, dx -> alArrastrar(dx.x) }
    }

    fun pulsar() {
        rastreador.resetTracking()
        ambito.launch {
            launch { animPulsado.animateTo(1f, muellePulsado) }
            launch { animX.animateTo(escalaPulsada, muelleX) }
            launch { animY.animateTo(escalaPulsada, muelleY) }
        }
    }

    fun soltar() {
        ambito.launch {
            withFrameNanos {}
            if (valor != valorObjetivo) {
                val umbral = (rango.endInclusive - rango.start) * 0.025f
                snapshotFlow { animValor.value }.filter { abs(it - animValor.targetValue) < umbral }.first()
            }
            launch { animPulsado.animateTo(0f, muellePulsado) }
            launch { animX.animateTo(1f, muelleX) }
            launch { animY.animateTo(1f, muelleY) }
        }
    }

    fun actualizar(v: Float) {
        val objetivo = v.coerceIn(rango.start, rango.endInclusive)
        ambito.launch { animValor.animateTo(objetivo, muelleValor) { anotarVelocidad() } }
    }

    fun animarA(v: Float) {
        ambito.launch {
            mutex.mutate {
                pulsar()
                launch { animValor.animateTo(v.coerceIn(rango.start, rango.endInclusive), muelleValor) }
                if (velocidad != 0f) launch { animVelocidad.animateTo(0f, muelleVelocidad) }
                soltar()
            }
        }
    }

    private fun anotarVelocidad() {
        rastreador.addPosition(SystemClock.uptimeMillis(), Offset(valor, 0f))
        val v = rastreador.calculateVelocity().x / (rango.endInclusive - rango.start)
        ambito.launch { animVelocidad.animateTo(v, muelleVelocidad) }
    }
}

/**
 * Reconoce pulsar, arrastrar y soltar sin consumir nada (el `DragGestureInspector`
 * del catálogo de Kyant): el cristal reacciona y los botones de dentro reciben sus clics.
 */
suspend fun PointerInputScope.observarArrastre(
    alEmpezar: (abajo: PointerInputChange) -> Unit = {},
    alTerminar: (cambio: PointerInputChange) -> Unit = {},
    alCancelar: () -> Unit = {},
    alArrastrar: (cambio: PointerInputChange, dx: Offset) -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val abajo = awaitFirstDown(requireUnconsumed = false)
        alEmpezar(abajo)
        alArrastrar(abajo, Offset.Zero)
        val arriba = seguir(abajo.id) { alArrastrar(it, it.positionChange()) }
        if (arriba == null) alCancelar() else alTerminar(arriba)
    }
}

private suspend inline fun AwaitPointerEventScope.seguir(
    id: PointerId,
    alMover: (PointerInputChange) -> Unit
): PointerInputChange? {
    if (currentEvent.changes.fastFirstOrNull { it.id == id }?.pressed != true) return null
    var puntero = id
    while (true) {
        val cambio = moverOSoltar(puntero) ?: return null
        if (cambio.isConsumed) return null
        if (cambio.changedToUpIgnoreConsumed()) return cambio
        alMover(cambio)
        puntero = cambio.id
    }
}

private suspend inline fun AwaitPointerEventScope.moverOSoltar(id: PointerId): PointerInputChange? {
    var puntero = id
    while (true) {
        val evento = awaitPointerEvent()
        val cambio = evento.changes.fastFirstOrNull { it.id == puntero } ?: return null
        if (cambio.changedToUpIgnoreConsumed()) {
            val otro = evento.changes.fastFirstOrNull { it.pressed } ?: return cambio
            puntero = otro.id
        } else if (cambio.previousPosition != cambio.position) {
            return cambio
        }
    }
}

/** Alto de la gota de la pestaña activa (la barra mide 64 dp). */
val ALTO_GOTA: Dp = 56.dp
