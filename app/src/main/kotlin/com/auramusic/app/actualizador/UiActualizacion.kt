package com.auramusic.app.actualizador

import android.app.Activity
import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auramusic.app.R
import com.auramusic.app.ui.screens.SettingsToggleItem
import com.auramusic.app.ui.theme.LocalMovimientoReducido

/** El mismo ViewModel para la fila de Ajustes y la hoja: el de la Activity. */
@Composable
private fun vmActualizacion(): ActualizacionViewModel =
    hiltViewModel(viewModelStoreOwner = LocalActivity.current as ComponentActivity)

// ── Ajustes ▸ Acerca de ────────────────────────────────────────────────────────

/** "Buscar actualizaciones" y "Buscar al abrir la app". No se ve en copias de F-Droid ni en debug. */
@Composable
fun FilasActualizacion() {
    val vm = vmActualizacion()
    val estado by vm.estado.collectAsStateWithLifecycle()
    val automatico by vm.automatico.collectAsStateWithLifecycle()
    if (estado is EstadoActualizacion.Oculto) return
    val ctx = LocalContext.current

    // El icono gira al buscar: al menos una vuelta entera (GitHub puede responder en
    // 0,2 s) y sigue mientras dure la búsqueda. Con movimiento reducido, quieto.
    val comprobando = estado is EstadoActualizacion.Comprobando
    val sigueComprobando by rememberUpdatedState(comprobando)
    val reducido = LocalMovimientoReducido.current
    val giro = remember { Animatable(0f) }
    var vueltas by remember { mutableIntStateOf(0) }
    LaunchedEffect(comprobando) { if (comprobando) vueltas++ }
    LaunchedEffect(vueltas) {
        if (vueltas == 0 || reducido) return@LaunchedEffect
        do {
            giro.animateTo(giro.value + 360f, tween(700, easing = LinearEasing))
        } while (sigueComprobando)
        giro.snapTo(0f)
    }
    // Tocar busca (al día o error al comprobar); en los demás estados abre la hoja.
    val tocarBusca = estado is EstadoActualizacion.AlDia ||
        (estado as? EstadoActualizacion.Fallo)?.enDescarga == false

    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.act_buscar), fontWeight = FontWeight.Medium) },
            supportingContent = {
                Column {
                    Text(textoFila(ctx, estado), color = colorFila(estado))
                    if (estado is EstadoActualizacion.Descargando) {
                        val e = estado as EstadoActualizacion.Descargando
                        LinearProgressIndicator(
                            progress = { fraccion(e) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }
            },
            leadingContent = {
                // El giro se lee al dibujar (graphicsLayer): no recompone la fila en cada fotograma.
                Icon(
                    Icons.Default.Refresh, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.graphicsLayer { rotationZ = giro.value }
                )
            },
            modifier = Modifier.clickable(enabled = !comprobando) {
                if (tocarBusca) vueltas++
                vm.tocarFila()
            }
        )
        SettingsToggleItem(
            title = stringResource(R.string.act_auto),
            subtitle = stringResource(R.string.act_auto_desc),
            checked = automatico,
            onToggle = { vm.cambiarAutomatico(!automatico) },
            icon = Icons.Default.Schedule
        )
    }
}

private fun textoFila(ctx: Context, e: EstadoActualizacion): String = when (e) {
    is EstadoActualizacion.AlDia ->
        if (e.ultimaComprobacion <= 0) ctx.getString(R.string.act_al_dia_nunca)
        else ctx.getString(R.string.act_al_dia, cuando(ctx, e.ultimaComprobacion))
    EstadoActualizacion.Comprobando -> ctx.getString(R.string.act_buscando)
    is EstadoActualizacion.Disponible -> ctx.getString(R.string.act_disponible_fila, e.version.version)
    is EstadoActualizacion.Descargando ->
        ctx.getString(R.string.act_descargando_fila, e.version.version, (fraccion(e) * 100).toInt())
    is EstadoActualizacion.Lista -> ctx.getString(R.string.act_lista_fila, e.version.version)
    is EstadoActualizacion.Fallo -> ctx.getString(when (e.tipo) {
        ErrorActualizacion.RED -> if (e.enDescarga) R.string.act_err_red_descarga else R.string.act_err_red
        ErrorActualizacion.LIMITE -> R.string.act_err_limite
        ErrorActualizacion.CATALOGO -> R.string.act_err_catalogo
        ErrorActualizacion.DANADA -> R.string.act_err_danada
        ErrorActualizacion.APK_NO_VALIDA -> R.string.act_err_apk
    })
    EstadoActualizacion.Oculto -> ""
}

@Composable
private fun colorFila(e: EstadoActualizacion): Color = when (e) {
    is EstadoActualizacion.Disponible, is EstadoActualizacion.Lista -> MaterialTheme.colorScheme.primary
    is EstadoActualizacion.Fallo -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** "hoy, 23:13" o "24 nov, 23:13". */
private fun cuando(ctx: Context, t: Long): String =
    if (DateUtils.isToday(t)) ctx.getString(R.string.act_hoy, DateFormat.getTimeFormat(ctx).format(t))
    else DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME)

private fun fraccion(e: EstadoActualizacion.Descargando): Float =
    (e.bajados.toFloat() / e.version.tamano.coerceAtLeast(1)).coerceIn(0f, 1f)

// ── Hoja al abrir la app ───────────────────────────────────────────────────────

/**
 * Aviso de versión nueva (una vez por versión), progreso, "Lista para instalar" y
 * "Falta un permiso". Va en MainActivity, encima de todo; no toca el reproductor.
 */
@Composable
fun HojaActualizacion() {
    // No entra en el primer fotograma: el ViewModel, su lectura de la firma y la
    // comprobación empiezan después de pintar la app (en el J7 eran ~18 ms de arranque).
    var trasPrimerFotograma by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withFrameNanos { }; trasPrimerFotograma = true }
    if (trasPrimerFotograma) HojaActualizacionActiva()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaActualizacionActiva() {
    val vm = vmActualizacion()
    val actividad = LocalActivity.current as Activity
    val estado by vm.estado.collectAsStateWithLifecycle()
    val visible by vm.hojaVisible.collectAsStateWithLifecycle()
    val pidePermiso by vm.pidePermiso.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) { vm.alIniciar(); onStopOrDispose { } }
    LifecycleResumeEffect(Unit) { vm.alReanudar(actividad); onPauseOrDispose { } }

    // La hoja solo tiene algo que enseñar en estos estados; si algo falla, desaparece
    // y lo dice la fila de Ajustes (tocarla reintenta).
    val conContenido = estado is EstadoActualizacion.Disponible ||
        estado is EstadoActualizacion.Descargando || estado is EstadoActualizacion.Lista
    if (!visible || !conContenido) return

    val e = estado
    // Qué se enseña: al cambiar (descarga → lista) el contenido nuevo solo se funde;
    // la entrada escalonada es para la primera aparición de la hoja.
    val tipo = when {
        pidePermiso && e is EstadoActualizacion.Lista -> 3
        e is EstadoActualizacion.Disponible -> 0
        e is EstadoActualizacion.Descargando -> 1
        else -> 2
    }
    HojaAnimada(tipo = tipo, onCerrada = vm::ocultarHoja) { cerrar ->
        when {
            tipo == 3 -> ContenidoPermiso(
                onCancelar = vm::cancelarPermiso,
                onAbrirAjustes = { vm.abrirAjustesPermiso(actividad) }
            )
            e is EstadoActualizacion.Disponible -> ContenidoDisponible(
                e.version,
                onMasTarde = cerrar,
                onDescargar = vm::descargar
            )
            e is EstadoActualizacion.Descargando -> ContenidoDescargando(
                e,
                onCancelar = vm::cancelarDescarga,
                onOcultar = cerrar
            )
            e is EstadoActualizacion.Lista -> ContenidoLista(
                e.version,
                onMasTarde = cerrar,
                onInstalar = { vm.instalar(actividad) }
            )
        }
    }
}

// ── Hoja con rebote (opción B de la maqueta) ───────────────────────────────────
// ModalBottomSheet no deja cambiar su entrada (sube recta), así que la hoja es propia:
// velo, hoja que sube con un rebote, se arrastra hacia abajo para cerrar y sigue el
// gesto de atrás. Dentro, el icono salta y el resto entra escalonado. Todo se anima
// en graphicsLayer (sin recomponer por fotograma) y con movimiento reducido no se anima.

/** true mientras la hoja hace su primera entrada: los elementos entran escalonados. */
private val LocalEntradaEscalonada = staticCompositionLocalOf { false }

// Retrasos desde que empieza a subir la hoja (ms), los mismos que la maqueta.
private const val R_ICONO = 300
private const val R_TITULO = 380
private const val R_SUBTITULO = 440
private const val R_CUERPO = 480
private const val R_LINEA = 540
private const val R_PASO_LINEA = 90
private const val R_NOTA = 760
private const val R_BOTONES = 800

private val EnfatizadaEntrada = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EnfatizadaSalida = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaAnimada(
    tipo: Int,
    onCerrada: () -> Unit,
    contenido: @Composable ColumnScope.(cerrar: () -> Unit) -> Unit
) {
    val reducido = LocalMovimientoReducido.current
    val alcance = rememberCoroutineScope()
    val densidad = LocalDensity.current
    val rebote = with(densidad) { 14.dp.toPx() }
    val asiento = with(densidad) { 4.dp.toPx() }

    var alto by remember { mutableIntStateOf(0) }
    val y = remember { Animatable(Float.MAX_VALUE) }      // desplazamiento hacia abajo (px)
    val velo = remember { Animatable(0f) }
    var cerrando by remember { mutableStateOf(false) }

    // Primera aparición: escalonar. Al cambiar de contenido, ya no.
    val primerTipo = remember { tipo }
    var yaEntro by remember { mutableStateOf(false) }
    LaunchedEffect(tipo) { if (tipo != primerTipo) yaEntro = true }

    LaunchedEffect(alto > 0) {
        if (alto == 0) return@LaunchedEffect
        if (reducido) { y.snapTo(0f); velo.snapTo(1f); return@LaunchedEffect }
        y.snapTo(alto.toFloat())
        launch { velo.animateTo(1f, tween(300)) }
        // Sube, se pasa un poco (14 dp), baja 4 dp y se asienta: 560 ms, como la maqueta.
        y.animateTo(0f, keyframes {
            durationMillis = 560
            -rebote at 392 using FastOutSlowInEasing
            asiento at 476
        })
    }

    val cerrar: () -> Unit = {
        if (!cerrando) {
            cerrando = true
            alcance.launch {
                if (!reducido) {
                    launch { velo.animateTo(0f, tween(260)) }
                    y.animateTo(alto.toFloat(), tween(260, easing = EnfatizadaSalida))
                }
                onCerrada()
            }
        }
    }
    BackHandler(enabled = !cerrando, onBack = cerrar)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                // El velo se pinta con su opacidad, sin capa: con graphicsLayer(alpha) la
                // pantalla entera pasaba por un búfer aparte en cada fotograma (GPU del J7
                // a 17–22 ms por fotograma).
                .drawBehind { drawRect(Color.Black.copy(alpha = 0.55f * velo.value)) }
                .clickable(interactionSource = null, indication = null, onClickLabel = stringResource(R.string.act_cerrar), onClick = cerrar)
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 24 dp de más por debajo de la pantalla: el rebote no deja ver un hueco.
                .offset(y = 24.dp)
                .onSizeChanged { alto = it.height }
                .graphicsLayer { translationY = if (alto == 0) 1e6f else y.value }
                .draggable(
                    state = rememberDraggableState { d ->
                        alcance.launch { y.snapTo((y.value + d).coerceAtLeast(0f)) }
                    },
                    orientation = Orientation.Vertical,
                    enabled = !cerrando,
                    onDragStopped = { velocidad ->
                        if (y.value > alto * 0.3f || velocidad > 1800f) cerrar()
                        else y.animateTo(0f, tween(220, easing = EnfatizadaEntrada))
                    }
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            CompositionLocalProvider(LocalEntradaEscalonada provides !yaEntro) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, bottom = 28.dp + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    BottomSheetDefaults.DragHandle(Modifier.align(Alignment.CenterHorizontally))
                    contenido(cerrar)
                }
            }
        }
    }
}

/** Aparece (fundido y 12 dp hacia arriba) [retrasoMs] después de que empiece a subir la hoja. */
@Composable
private fun Modifier.entrada(retrasoMs: Int): Modifier {
    val escalonar = LocalEntradaEscalonada.current
    val reducido = LocalMovimientoReducido.current
    val v = remember { Animatable(if (reducido) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reducido) return@LaunchedEffect
        if (escalonar) delay(retrasoMs.toLong())
        v.animateTo(1f, tween(if (escalonar) 300 else 200, easing = EnfatizadaEntrada))
    }
    val sube = with(LocalDensity.current) { 12.dp.toPx() }
    return graphicsLayer {
        alpha = v.value
        translationY = if (escalonar) (1f - v.value) * sube else 0f
        // Aplica la transparencia al dibujar, sin búfer aparte por cada elemento.
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** El icono "salta": crece de 0 a 1,18 girando y se asienta (520 ms). */
@Composable
private fun Modifier.salto(retrasoMs: Int): Modifier {
    val reducido = LocalMovimientoReducido.current
    val escala = remember { Animatable(if (reducido) 1f else 0f) }
    val giro = remember { Animatable(if (reducido) 0f else -30f) }
    LaunchedEffect(Unit) {
        if (reducido) return@LaunchedEffect
        delay(retrasoMs.toLong())
        launch { giro.animateTo(0f, keyframes { durationMillis = 520; 6f at 312 }) }
        escala.animateTo(1f, keyframes { durationMillis = 520; 1.18f at 312 using FastOutSlowInEasing })
    }
    return graphicsLayer { scaleX = escala.value; scaleY = escala.value; rotationZ = giro.value }
}

@Composable
private fun Cabecera(icono: ImageVector, titulo: String, subtitulo: String) {
    val escalonar = LocalEntradaEscalonada.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Salta en la primera entrada y al llegar a "Lista" (el check); si no, solo se funde.
        val iconoMod = if (escalonar || icono == Icons.Default.Check) Modifier.salto(if (escalonar) R_ICONO else 0)
            else Modifier.entrada(0)
        Box(
            iconoMod.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icono, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(titulo, Modifier.entrada(R_TITULO), style = MaterialTheme.typography.headlineSmall)
            Text(subtitulo, Modifier.entrada(R_SUBTITULO), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Nota(texto: String) =
    Text(texto, Modifier.entrada(R_NOTA), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Botones(secundario: String, onSecundario: () -> Unit, principal: String, onPrincipal: () -> Unit, tonal: Boolean = false) {
    Row(Modifier.fillMaxWidth().entrada(R_BOTONES), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onSecundario) { Text(secundario) }
        if (tonal) FilledTonalButton(onClick = onPrincipal) { Text(principal) }
        else Button(onClick = onPrincipal) { Text(principal) }
    }
}

@Composable
private fun ContenidoDisponible(v: VersionPublicada, onMasTarde: () -> Unit, onDescargar: () -> Unit) {
    val ctx = LocalContext.current
    Cabecera(
        Icons.Default.Download,
        stringResource(R.string.act_nueva_titulo),
        stringResource(R.string.act_version_tamano, v.version, Formatter.formatShortFileSize(ctx, v.tamano))
    )
    if (v.notas.isNotEmpty()) {
        Column(
            Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.act_novedades), Modifier.entrada(R_CUERPO), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            v.notas.forEachIndexed { i, nota ->
                // Cada novedad entra una tras otra (90 ms entre ellas).
                Row(Modifier.entrada(R_LINEA + i * R_PASO_LINEA)) {
                    Text("•", Modifier.width(18.dp), style = MaterialTheme.typography.bodyLarge)
                    Text(nota, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    Nota(stringResource(R.string.act_sigue_sonando))
    Botones(stringResource(R.string.act_mas_tarde), onMasTarde, stringResource(R.string.act_descargar), onDescargar)
}

@Composable
private fun ContenidoDescargando(e: EstadoActualizacion.Descargando, onCancelar: () -> Unit, onOcultar: () -> Unit) {
    val ctx = LocalContext.current
    val f = fraccion(e)
    Cabecera(Icons.Default.Download, stringResource(R.string.act_descargando_titulo), stringResource(R.string.act_version, e.version.version))
    Column(Modifier.entrada(R_CUERPO), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val estilo = MaterialTheme.typography.bodyMedium
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            Text(stringResource(R.string.act_progreso,
                Formatter.formatShortFileSize(ctx, e.bajados), Formatter.formatShortFileSize(ctx, e.version.tamano)),
                style = estilo, color = color)
            Text(stringResource(R.string.act_porcentaje, (f * 100).toInt()), style = estilo, color = color)
        }
    }
    Nota(stringResource(R.string.act_puedes_cerrar))
    Botones(stringResource(R.string.act_cancelar_descarga), onCancelar, stringResource(R.string.act_ocultar), onOcultar, tonal = true)
}

@Composable
private fun ContenidoLista(v: VersionPublicada, onMasTarde: () -> Unit, onInstalar: () -> Unit) {
    val ctx = LocalContext.current
    Cabecera(
        Icons.Default.Check,
        stringResource(R.string.act_lista_titulo),
        stringResource(R.string.act_version_tamano, v.version, Formatter.formatShortFileSize(ctx, v.tamano))
    )
    Surface(Modifier.entrada(R_CUERPO), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Icon(Icons.Default.VerifiedUser, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.act_comprobada), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Nota(stringResource(R.string.act_confirmar))
    Botones(stringResource(R.string.act_mas_tarde), onMasTarde, stringResource(R.string.act_instalar), onInstalar)
}

@Composable
private fun ContenidoPermiso(onCancelar: () -> Unit, onAbrirAjustes: () -> Unit) {
    Cabecera(Icons.Default.Lock, stringResource(R.string.act_permiso_titulo), stringResource(R.string.act_permiso_sub))
    Text(stringResource(R.string.act_permiso_texto), Modifier.entrada(R_CUERPO), style = MaterialTheme.typography.bodyLarge)
    Column(Modifier.entrada(R_LINEA), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val pasos = listOf(
            conNegrita(stringResource(R.string.act_paso1, stringResource(R.string.act_abrir_ajustes)), stringResource(R.string.act_abrir_ajustes)),
            conNegrita(stringResource(R.string.act_paso2, stringResource(R.string.act_permitir_fuente)), stringResource(R.string.act_permitir_fuente)),
            AnnotatedString(stringResource(R.string.act_paso3))
        )
        pasos.forEachIndexed { i, paso ->
            Row {
                Text("${i + 1}.", Modifier.width(22.dp), style = MaterialTheme.typography.bodyLarge)
                Text(paso, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    Botones(stringResource(R.string.act_cancelar), onCancelar, stringResource(R.string.act_abrir_ajustes), onAbrirAjustes)
}

private fun conNegrita(texto: String, parte: String): AnnotatedString = buildAnnotatedString {
    append(texto)
    val i = texto.indexOf(parte)
    if (i >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Medium), i, i + parte.length)
}
