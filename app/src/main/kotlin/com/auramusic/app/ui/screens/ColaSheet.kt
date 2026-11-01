package com.auramusic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.auramusic.app.R
import com.auramusic.app.ui.components.conReintento
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// ─── COLA ── hoja "A continuación" (estructura de YT Music) ─────────────────
// "Reproduciendo desde", la canción que suena resaltada, el interruptor de
// reproducción automática (idea de SimpMusic) y las siguientes: tocar salta a
// ella, ≡ arrastra para reordenar y deslizar la quita (con "Deshacer").
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColaSheet(playerViewModel: PlayerViewModel, settingsVm: SettingsViewModel, onCerrar: () -> Unit) {
    val cola by playerViewModel.cola.collectAsStateWithLifecycle()
    val actual by playerViewModel.indiceActual.collectAsStateWithLifecycle()
    val origen by playerViewModel.origenCola.collectAsStateWithLifecycle()
    val ajustes by settingsVm.settings.collectAsStateWithLifecycle()
    val haptica = LocalHapticFeedback.current
    // Las acciones de una fila miran la cola de AHORA: tras arrastrar o quitar otra,
    // la posición con la que se dibujó la fila ya no vale.
    val colaAhora by rememberUpdatedState(cola)
    val posicionDe = { clave: String -> colaAhora.indexOfFirst { it.clave == clave } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val textoQuitada = stringResource(R.string.cola_quitada)
    val textoDeshacer = stringResource(R.string.cola_deshacer)

    // Copia local para que el arrastre se vea al momento; ExoPlayer se mueve al soltar.
    var siguientes by remember { mutableStateOf(emptyList<PlayerViewModel.ItemCola>()) }
    var arrastre by remember { mutableStateOf<Pair<Int, Int>?>(null) }   // (desde, hasta) en la cola completa
    LaunchedEffect(cola, actual) { if (arrastre == null) siguientes = cola.drop(actual + 1) }
    val lista = rememberLazyListState()
    val reordenable = rememberReorderableLazyListState(lista) { de, a ->
        val i = siguientes.indexOfFirst { it.clave == de.key }
        val j = siguientes.indexOfFirst { it.clave == a.key }
        if (i < 0 || j < 0) return@rememberReorderableLazyListState
        siguientes = siguientes.toMutableList().apply { add(j, removeAt(i)) }
        arrastre = (arrastre?.first ?: (actual + 1 + i)) to (actual + 1 + j)
        haptica.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    ModalBottomSheet(onDismissRequest = onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box {
            LazyColumn(state = lista, contentPadding = PaddingValues(bottom = 24.dp)) {
                origen?.let { o ->
                    item(key = "origen") {
                        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 12.dp)) {
                            Text(stringResource(R.string.cola_desde), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(o, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                cola.getOrNull(actual)?.let { c ->
                    item(key = "sonando") {
                        FilaCola(c, sonando = true, modifier = Modifier.padding(horizontal = 10.dp)
                            .clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer))
                    }
                }
                item(key = "cabecera") {
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.cola_titulo), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.cola_automatica), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Switch(checked = ajustes.reproduccionAutomatica, onCheckedChange = { settingsVm.toggleReproduccionAutomatica() })
                    }
                }
                if (siguientes.isEmpty()) item(key = "vacia") {
                    Text(stringResource(R.string.cola_vacia), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(18.dp))
                }
                items(siguientes, key = { it.clave }) { c ->
                    ReorderableItem(reordenable, key = c.clave) { arrastrando ->
                        val deslizar = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                            if (v != SwipeToDismissBoxValue.EndToStart) return@rememberSwipeToDismissBoxState false
                            // El gesto puede confirmar más de una vez: si ya no está, no se quita otra.
                            val indice = posicionDe(c.clave)
                            if (indice < 0) return@rememberSwipeToDismissBoxState true
                            playerViewModel.quitarDeCola(indice)
                            scope.launch {
                                val r = snackbar.showSnackbar(textoQuitada.format(c.titulo), textoDeshacer, duration = SnackbarDuration.Long)
                                if (r == SnackbarResult.ActionPerformed) playerViewModel.reinsertarEnCola(indice, c)
                            }
                            true
                        })
                        // La lista guarda el estado de la fila: al volver con "Deshacer"
                        // reaparecería deslizada (solo la franja roja). Vuelve a su sitio.
                        LaunchedEffect(Unit) {
                            if (deslizar.currentValue != SwipeToDismissBoxValue.Settled) deslizar.snapTo(SwipeToDismissBoxValue.Settled)
                        }
                        SwipeToDismissBox(
                            state = deslizar, enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(end = 22.dp),
                                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.cola_quitar), color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        ) {
                            FilaCola(
                                c, sonando = false,
                                modifier = Modifier
                                    .shadow(if (arrastrando) 8.dp else 0.dp)
                                    .background(if (arrastrando) MaterialTheme.colorScheme.surfaceContainerHighest
                                                else MaterialTheme.colorScheme.surfaceContainerLow)
                                    .clickable { posicionDe(c.clave).takeIf { it >= 0 }?.let(playerViewModel::saltarA) },
                                manija = {
                                    IconButton(
                                        onClick = {},
                                        modifier = Modifier.draggableHandle(
                                            onDragStarted = { haptica.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                            onDragStopped = {
                                                arrastre?.let { (de, a) -> playerViewModel.moverEnCola(de, a) }
                                                arrastre = null
                                                haptica.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            }
                                        )
                                    ) { Icon(Icons.Default.DragHandle, stringResource(R.string.cola_arrastrar)) }
                                }
                            )
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun FilaCola(
    c: PlayerViewModel.ItemCola, sonando: Boolean, modifier: Modifier = Modifier,
    manija: (@Composable () -> Unit)? = null
) {
    Row(modifier.fillMaxWidth().height(66.dp).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)), Alignment.Center) {
            AsyncImage(conReintento(mejorarCaratula(c.portada)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (sonando) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)))
                Icon(Icons.Default.GraphicEq, stringResource(R.string.cola_sonando), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.titulo, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (sonando) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (sonando) FontWeight.Medium else FontWeight.Normal)
            val duracion = c.duracionMs?.takeIf { it > 0 }?.let { " • " + formatearDuracion(it) }.orEmpty()
            Text(c.artista + duracion, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        manija?.invoke()
    }
}

private fun formatearDuracion(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
