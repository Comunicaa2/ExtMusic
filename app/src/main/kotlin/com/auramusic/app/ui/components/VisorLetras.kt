package com.auramusic.app.ui.components

import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import com.auramusic.app.ui.theme.LocalMovimientoReducido
import com.auramusic.app.ui.theme.Movimiento
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.auramusic.app.R
import com.auramusic.app.data.api.LyricsResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.math.cos
import kotlin.math.sin

// ─── Modelo de lo que se dibuja ───────────────────────────────────────────────

/** Una línea del visor; las de interludio (instrumental) se pintan como "♫". */
private data class LineaVisor(val timeMs: Long, val texto: String, val interludio: Boolean)

// Silencio mínimo para mostrar "♫": con menos, la línea anterior sigue resaltada.
private const val SILENCIO_MIN_MS = 4_000L

// Lo que se tarda en cantar una línea, a ojo: entre 3 y 7 s según su largo.
private fun duracionEstimada(texto: String): Long = (1_500L + texto.length * 110L).coerceIn(3_000L, 7_000L)

/**
 * Prepara las líneas sincronizadas para el visor:
 *  - Las vacías (LRCLIB marca así el final de un verso o un instrumental) pasan a
 *    "♫" si el silencio hasta la siguiente dura al menos [SILENCIO_MIN_MS].
 *  - Si la letra no trae ninguna (KuGou, .lrc guardados antes), se estima cuándo
 *    acaba cada línea y, si después queda un silencio largo, se añade "♫". Antes la
 *    última línea seguía resaltada durante todo el instrumental.
 *  - Si tarda más de 5 s en empezar, "♫" al principio. Nunca dos "♫" seguidos.
 * Las no sincronizadas solo pierden las líneas vacías.
 */
private fun lineasDe(letra: LyricsResult): List<LineaVisor> {
    if (!letra.isSynced) return letra.lines.filter { it.text.isNotBlank() }
        .map { LineaVisor(it.timeMs, it.text.trim(), false) }
    val l = letra.lines
    val conMarcas = l.any { it.text.isBlank() }
    val res = mutableListOf<LineaVisor>()
    l.forEachIndexed { i, x ->
        val siguiente = l.getOrNull(i + 1)?.timeMs
        if (x.text.isBlank()) {
            if (siguiente == null || siguiente - x.timeMs >= SILENCIO_MIN_MS) res += LineaVisor(x.timeMs, "", true)
        } else {
            res += LineaVisor(x.timeMs, x.text.trim(), false)
            if (!conMarcas) {
                val fin = x.timeMs + duracionEstimada(x.text.trim())
                if (siguiente == null || siguiente - fin >= SILENCIO_MIN_MS) res += LineaVisor(fin, "", true)
            }
        }
    }
    if ((res.firstOrNull()?.timeMs ?: 0L) > 5_000L) res.add(0, LineaVisor(0L, "", true))
    return res.filterIndexed { i, v -> !(v.interludio && res.getOrNull(i - 1)?.interludio == true) }
}

/** Índice de la línea que suena en [posMs], o -1 si aún no empieza. */
private fun indiceActivo(lineas: List<LineaVisor>, posMs: Long): Int =
    lineas.indexOfLast { it.timeMs <= posMs }

/** Color dominante oscuro de la portada (para el fondo), o null mientras carga. */
@Composable
private fun rememberColorPortada(url: String?): Color? {
    val contexto = LocalContext.current
    var color by remember(url) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        val res = contexto.imageLoader.execute(
            ImageRequest.Builder(contexto).data(url).size(128).allowHardware(false).build()
        )
        val bmp = ((res as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val p = Palette.from(bmp).generate()
        val rgb = p.getDarkVibrantColor(p.getDarkMutedColor(p.getDominantColor(0xFF1E2A38.toInt())))
        color = Color(rgb)
    }
    return color
}

/**
 * Línea activa leída de [posMs] dentro de un derivedStateOf: la posición cambia
 * hasta 10 veces por segundo, pero quien lee esto solo se recompone al cambiar
 * de línea (antes cada tic rehacía el AnimatedContent y la lista).
 */
@Composable
private fun lineaActiva(letra: LyricsResult, lineas: List<LineaVisor>, posMs: () -> Long): State<Int> {
    val pos by rememberUpdatedState(posMs)
    return remember(letra, lineas) {
        derivedStateOf { if (letra.isSynced) indiceActivo(lineas, pos()) else -1 }
    }
}

// ─── Vista previa en el reproductor ───────────────────────────────────────────

/**
 * Tarjeta del reproductor: solo 3 líneas (anterior, actual, siguiente) que suben
 * al cambiar de línea. Sin desplazamiento propio (antes había una lista dentro de
 * la lista del reproductor y se peleaban). Al tocarla se abre el visor completo.
 */
@Composable
fun LetraPrevia(
    letra: LyricsResult,
    posMs: () -> Long,
    tamanoSp: Int,
    modifier: Modifier = Modifier
) {
    val lineas = remember(letra) { lineasDe(letra) }
    val activo by lineaActiva(letra, lineas, posMs)
    val claro = MaterialTheme.colorScheme.onSurface
    // Alto fijo (la línea activa siempre ocupa 2 renglones; las otras, 1): antes
    // la tarjeta crecía y encogía según el largo de cada línea y empujaba todo.
    val altoNormal = (tamanoSp * 1.25f).sp
    val altoActiva = (tamanoSp * 1.2f * 1.25f).sp
    val altoHueco = with(LocalDensity.current) { (altoActiva * 2).toDp() }
    AnimatedContent(
        targetState = activo,
        transitionSpec = {
            (slideInVertically(Movimiento.entrada()) { it / 3 } + fadeIn(Movimiento.fundido()))
                .togetherWith(slideOutVertically(Movimiento.salida(Movimiento.MEDIA)) { -it / 3 } + fadeOut(Movimiento.salida()))
        },
        label = "letraPrevia",
        modifier = modifier
    ) { i ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            val visibles = if (letra.isSynced) listOf(i - 1, i, i + 1) else listOf(0, 1, 2)
            visibles.forEach { j ->
                val l = lineas.getOrNull(j)
                val esActiva = letra.isSynced && j == i
                val texto = when { l == null -> " "; l.interludio -> "♫"; else -> l.texto }
                val color = if (esActiva || !letra.isSynced) claro else claro.copy(alpha = 0.38f)
                if (esActiva) Box(Modifier.height(altoHueco).padding(vertical = 0.dp), contentAlignment = Alignment.CenterStart) {
                    Text(texto, color = color, fontSize = (tamanoSp * 1.2f).sp, lineHeight = altoActiva,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                } else Text(texto, color = color, fontSize = tamanoSp.sp, lineHeight = altoNormal,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

/**
 * La línea que suena, sola y en una línea, bajo la portada (idea de SimpMusic).
 * Solo con letra sincronizada; antes de la primera línea queda en blanco.
 */
@Composable
fun LineaLetraActual(letra: LyricsResult, posMs: () -> Long, modifier: Modifier = Modifier) {
    val lineas = remember(letra) { lineasDe(letra) }
    val activo by lineaActiva(letra, lineas, posMs)
    val texto = lineas.getOrNull(activo)?.let { if (it.interludio) "♫" else it.texto }.orEmpty()
    AnimatedContent(
        targetState = texto,
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
        label = "lineaActual", modifier = modifier
    ) { t ->
        Text(t.ifEmpty { " " }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth())
    }
}

// ─── Visor a pantalla completa ────────────────────────────────────────────────

/**
 * Visor de letras a pantalla completa, al estilo de SimpMusic / Apple Music:
 *  - Fondo: degradado del color de la portada a negro que gira despacio.
 *  - Líneas grandes, en negrita y a la izquierda; la activa en blanco a ~1/3 de
 *    altura. Al cambiar, la anterior se apaga, la lista sube frenando (~320 ms) y
 *    la nueva se ilumina. "♫" en las pausas instrumentales.
 *  - Tocar una línea salta a ese momento.
 *  - Si se desliza a mano, la sincronía se pausa y aparece "Volver a la letra";
 *    a los 4 s sin tocar vuelve sola.
 *  - Controles completos abajo (progreso, anterior, play/pausa, siguiente).
 * [posMs] ya viene corregida con el retraso de Ajustes; [onSeek] recibe la
 * posición de la línea y el llamador le suma ese retraso.
 */
@Composable
fun VisorLetrasCompleto(
    letra: LyricsResult,
    titulo: String,
    artista: String,
    portada: String?,
    posMs: () -> Long,
    duracionMs: Long,
    reproduciendo: Boolean,
    tamanoSp: Int,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onAnterior: () -> Unit,
    onSiguiente: () -> Unit,
    onCerrar: () -> Unit
) {
    BackHandler(onBack = onCerrar)
    val lineas = remember(letra) { lineasDe(letra) }
    val activo by lineaActiva(letra, lineas, posMs)
    val colorBase = rememberColorPortada(portada) ?: Color(0xFF1E2A38)
    val colorAnimado by animateColorAsState(colorBase, tween(600), label = "colorFondo")

    // Fondo que gira despacio (vuelta de 14 s), como el de SimpMusic. Solo gira
    // mientras suena y sin "Quitar animaciones": en pausa se queda quieto donde
    // estaba (antes giraba siempre y redibujaba la pantalla sin parar).
    val giroAnim = remember { Animatable(0f) }
    val reducido = LocalMovimientoReducido.current
    LaunchedEffect(reproduciendo, reducido) {
        if (!reproduciendo || reducido) return@LaunchedEffect
        while (true) {
            val desde = giroAnim.value % 360f
            giroAnim.snapTo(desde)
            giroAnim.animateTo(desde + 360f, tween(14_000, easing = LinearEasing))
        }
    }
    // Se redibuja solo cuando el ángulo avanza un grado entero (~26 veces por
    // segundo en vez de las 120 de la pantalla): a esta velocidad se ve igual de
    // suave y gasta mucha menos batería en gama baja.
    val giro by remember { derivedStateOf { kotlin.math.floor(giroAnim.value) } }

    Box(Modifier.fillMaxSize()) {
        // El fondo va en su propio nodo, hermano del contenido: al girar solo se
        // vuelve a pintar él (antes cada paso regrababa también letra y controles).
        Box(Modifier.fillMaxSize().drawBehind {
            drawRect(Color.Black)
            val rad = Math.toRadians(giro.toDouble())
            val r = size.maxDimension * 0.75f
            val c = Offset(size.width / 2, size.height / 2)
            val d = Offset((cos(rad) * r).toFloat(), (sin(rad) * r).toFloat())
            drawRect(
                Brush.linearGradient(
                    0f to colorAnimado,
                    0.55f to lerp(colorAnimado, Color.Black, 0.55f),
                    1f to Color.Black,
                    start = c - d, end = c + d
                )
            )
        })
        Column(Modifier.fillMaxSize().graphicsLayer().statusBarsPadding().navigationBarsPadding()) {
            // Cabecera: portada, título, artista y cerrar.
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCerrar) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.accion_cerrar), tint = Color.White, modifier = Modifier.size(30.dp))
                }
                AsyncImage(conReintento(portada), null, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(titulo, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(artista, color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                ListaLetras(lineas, activo, letra.isSynced, tamanoSp, onSeek)
            }

            // Controles.
            Text(
                if (letra.isSynced) stringResource(R.string.letras_fuente, letra.source)
                else stringResource(R.string.letras_no_sincronizada, letra.source),
                color = Color.White.copy(0.5f), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)
            )
            ControlesVisor(posMs, duracionMs, reproduciendo, onSeek, onPlayPause, onAnterior, onSiguiente)
        }
    }
}

@Composable
private fun ListaLetras(
    lineas: List<LineaVisor>,
    activo: Int,
    sincronizada: Boolean,
    tamanoSp: Int,
    onSeek: (Long) -> Unit
) {
    // Abre ya en la línea que suena (si no, bajaba animado desde el principio).
    val estado = rememberLazyListState(initialFirstVisibleItemIndex = activo.coerceAtLeast(0))
    var manual by remember { mutableStateOf(false) }
    var ultimoToque by remember { mutableLongStateOf(0L) }

    // Deslizar a mano pausa la sincronía; a los 4 s sin tocar vuelve sola.
    LaunchedEffect(estado) {
        estado.interactionSource.interactions.filterIsInstance<DragInteraction>().collect {
            manual = true; ultimoToque = System.currentTimeMillis()
        }
    }
    LaunchedEffect(manual, ultimoToque) {
        if (manual) { delay(4_000); if (System.currentTimeMillis() - ultimoToque >= 4_000) manual = false }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val arriba = maxHeight * 0.32f
        // Lleva la línea activa a ~1/3 de altura con un desplazamiento que frena al final.
        LaunchedEffect(activo, manual) {
            if (!sincronizada || manual || activo < 0) return@LaunchedEffect
            val item = estado.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activo }
            if (item != null) estado.animateScrollBy(item.offset.toFloat(), Movimiento.estandar(380))
            // Fuera de la vista (al buscar en la barra o al volver de deslizar a mano):
            // también animado; antes saltaba de golpe.
            else estado.animateScrollToItem(activo)
        }

        LazyColumn(
            state = estado,
            contentPadding = PaddingValues(top = arriba, bottom = maxHeight * 0.6f),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(lineas) { i, l ->
                val esActiva = sincronizada && i == activo
                val color by animateColorAsState(
                    when {
                        !sincronizada -> Color.White.copy(0.9f)
                        esActiva -> Color.White
                        else -> Color.White.copy(0.35f)
                    },
                    tween(if (esActiva) 380 else 160), label = "colorLinea"
                )
                // La que suena, a tamaño completo; las demás un poco más pequeñas.
                // Se lee en graphicsLayer: solo se redibuja, no se recompone.
                val escala by animateFloatAsState(if (esActiva || !sincronizada) 1f else 0.94f,
                    Movimiento.estandar(380), label = "escalaLinea")
                Text(
                    text = if (l.interludio) "♫" else l.texto,
                    color = color,
                    fontSize = (tamanoSp * 1.55f).sp,
                    lineHeight = (tamanoSp * 1.55f * 1.25f).sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                        .graphicsLayer { scaleX = escala; scaleY = escala; transformOrigin = TransformOrigin(0f, 0.5f) }
                        .clickable(
                            enabled = sincronizada,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { manual = false; onSeek(l.timeMs) }
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = manual && sincronizada,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
        ) {
            Surface(
                onClick = { manual = false },
                shape = CircleShape, color = Color.White.copy(0.18f), contentColor = Color.White
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerticalAlignCenter, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.letras_volver), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlesVisor(
    posMs: () -> Long,
    duracionMs: Long,
    reproduciendo: Boolean,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onAnterior: () -> Unit,
    onSiguiente: () -> Unit
) {
    var arrastrando by remember { mutableStateOf<Float?>(null) }
    // La posición llega 10 veces por segundo (por la letra); los controles solo
    // necesitan medio segundo: así no se recomponen en cada tic.
    val pos by rememberUpdatedState(posMs)
    val posRedondeada by remember { derivedStateOf { pos() / 500 * 500 } }
    val fraccion = arrastrando ?: if (duracionMs > 0) (posRedondeada.toFloat() / duracionMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
        // La barra y el tiempo, sin semántica, como en el reproductor (BarraProgreso).
        Column(Modifier.clearAndSetSemantics {}) {
            Slider(
                value = fraccion,
                onValueChange = { arrastrando = it },
                onValueChangeFinished = { arrastrando?.let { onSeek((it * duracionMs).toLong()) }; arrastrando = null },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White, activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(0.25f)
                )
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatoTiempo((fraccion * duracionMs).toLong()), color = Color.White.copy(0.7f), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(formatoTiempo(duracionMs), color = Color.White.copy(0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAnterior) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            Surface(onClick = onPlayPause, shape = CircleShape, color = Color.White, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    IconoPlayPausa(reproduciendo, null, Modifier.size(34.dp), tint = Color.Black)
                }
            }
            IconButton(onClick = onSiguiente) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
        }
    }
}

private fun formatoTiempo(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
