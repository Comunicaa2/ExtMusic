package com.auramusic.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.pluralStringResource
import com.auramusic.app.data.repository.RespaldoRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.auramusic.app.BuildConfig
import com.auramusic.app.R
import com.auramusic.app.domain.model.AudioQuality
import com.auramusic.app.domain.model.TemaApp
import com.auramusic.app.ui.theme.cristalDisponible
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ── Etiquetas de enums traducibles ────────────────────────────────────────────
// El enum guarda solo su `name` (lógica/persistencia); el texto visible se
// resuelve aquí desde strings.xml para que cada idioma lo traduzca.
@Composable fun TemaApp.textoUi(): String = stringResource(
    when (this) {
        TemaApp.CLARO  -> R.string.tema_claro
        TemaApp.OSCURO -> R.string.tema_oscuro
        TemaApp.AMOLED -> R.string.tema_amoled
    }
)
@Composable fun AudioQuality.etiquetaUi(): String = stringResource(
    when (this) {
        AudioQuality.LOW    -> R.string.calidad_baja
        AudioQuality.MEDIUM -> R.string.calidad_media
        AudioQuality.HIGH   -> R.string.calidad_alta
    }
)
@Composable fun AudioQuality.descripcionUi(): String = stringResource(
    when (this) {
        AudioQuality.LOW    -> R.string.calidad_baja_desc
        AudioQuality.MEDIUM -> R.string.calidad_media_desc
        AudioQuality.HIGH   -> R.string.calidad_alta_desc
    }
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val vm: SettingsViewModel = hiltViewModel()
    val s by vm.settings.collectAsStateWithLifecycle()
    val offloadRoto by vm.offloadRoto.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Backup de la biblioteca (archivo comprimido elegido con el selector del sistema).
    val msgExportado = stringResource(R.string.respaldo_exportado)
    val msgError = stringResource(R.string.respaldo_error)
    val msgGrande = stringResource(R.string.respaldo_grande)
    val avisar: (String) -> Unit = { m -> scope.launch { snackbar.showSnackbar(m) } }
    val avisarFallo: (Throwable) -> Unit = { e ->
        avisar(if (e is RespaldoRepository.RespaldoDemasiadoGrande) msgGrande else msgError)
    }
    var resumenImportado by remember { mutableStateOf<RespaldoRepository.Resumen?>(null) }
    val pickerExportar = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.exportarRespaldo(uri) { r ->
            r.onSuccess { avisar(msgExportado.format(it.canciones, it.playlists)) }.onFailure(avisarFallo)
        }
    }
    val pickerImportar = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importarRespaldo(uri) { r ->
            r.onSuccess { resumenImportado = it }.onFailure(avisarFallo)
        }
    }
    resumenImportado?.let { r ->
        val downloadVm: DownloadViewModel = hiltViewModel()
        DialogoRespaldoImportado(r,
            onDescargar = { r.porDescargar.forEach { downloadVm.downloadSong(it.toSearchItem()) }; resumenImportado = null },
            onCerrar = { resumenImportado = null })
    }

    // Diálogos
    var dlgTema by remember { mutableStateOf(false) }
    var dlgCalidadRepro by remember { mutableStateOf(false) }
    var dlgCalidadDescarga by remember { mutableStateOf(false) }
    var dlgMusicaTelefono by remember { mutableStateOf(false) }
    var dlgAlmacenamiento by remember { mutableStateOf(false) }

    // Uso actual de la caché, para "Almacenamiento". Se recalcula al limpiarla.
    var usoCacheMb by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) { vm.usoCacheMb { usoCacheMb = it } }

    val pickerCarpetasEscaneo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.setCarpetasEscaneo(uri.toString())
        }
    }

    if (dlgTema) OpcionPickerDialog(
        titulo = stringResource(R.string.ajustes_tema),
        opciones = TemaApp.values().map { it to it.textoUi() },
        actual = s.tema, onElegir = { vm.setTema(it); dlgTema = false }, onCerrar = { dlgTema = false }
    )
    if (dlgCalidadRepro) OpcionPickerDialog(
        titulo = stringResource(R.string.ajustes_calidad_repro),
        opciones = AudioQuality.values().map { it to it.textoCompleto() },
        actual = s.audioQuality, onElegir = { vm.setAudioQuality(it); dlgCalidadRepro = false }, onCerrar = { dlgCalidadRepro = false }
    )
    if (dlgCalidadDescarga) OpcionPickerDialog(
        titulo = stringResource(R.string.ajustes_calidad_descarga),
        opciones = AudioQuality.values().map { it to it.textoCompleto() },
        actual = s.downloadQuality, onElegir = { vm.setDownloadQuality(it); dlgCalidadDescarga = false }, onCerrar = { dlgCalidadDescarga = false }
    )
    if (dlgMusicaTelefono) {
        val msgActualizada = stringResource(R.string.bib_actualizada)
        AlertDialog(
            onDismissRequest = { dlgMusicaTelefono = false },
            title = { Text(stringResource(R.string.datos_musica_telefono)) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.bib_carpetas)) },
                        supportingContent = { Text(nombreCarpeta(s.carpetasEscaneo)) },
                        leadingContent = { Icon(Icons.Default.FolderOpen, null) },
                        modifier = Modifier.clickable { dlgMusicaTelefono = false; pickerCarpetasEscaneo.launch(null) }
                    )
                    if (s.carpetasEscaneo.isNotBlank()) ListItem(
                        headlineContent = { Text(stringResource(R.string.bib_carpetas_usar_todo)) },
                        supportingContent = { Text(stringResource(R.string.bib_carpetas_usar_todo_desc)) },
                        leadingContent = { Icon(Icons.Default.PhoneAndroid, null) },
                        modifier = Modifier.clickable { vm.setCarpetasEscaneo("") }
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.bib_actualizar)) },
                        supportingContent = { Text(stringResource(R.string.bib_actualizar_desc)) },
                        leadingContent = { Icon(Icons.Default.Refresh, null) },
                        modifier = Modifier.clickable {
                            dlgMusicaTelefono = false
                            vm.actualizarBiblioteca { n -> avisar("$msgActualizada ($n)") }
                        }
                    )
                }
            },
            confirmButton = { TextButton(onClick = { dlgMusicaTelefono = false }) { Text(stringResource(R.string.importar_listo)) } }
        )
    }
    if (dlgAlmacenamiento) {
        val msgLimpia = stringResource(R.string.cache_limpiada)
        AlertDialog(
            onDismissRequest = { dlgAlmacenamiento = false },
            title = { Text(stringResource(R.string.datos_almacenamiento)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.datos_almacenamiento_explica),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingsSliderItem(
                        title = stringResource(R.string.cache_tamano),
                        valor = s.cacheMaximoMb, rango = 64f..1024f, pasos = 14,
                        icon = Icons.Default.Storage,
                        valorTexto = { stringResource(R.string.cache_mb, it) },
                        onCommit = { vm.setCacheMaximoMb(it) },
                        subtitle = stringResource(R.string.cache_tamano_desc)
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.cache_limpiar)) },
                        supportingContent = { Text(stringResource(R.string.cache_limpiar_desc)) },
                        leadingContent = { Icon(Icons.Default.DeleteSweep, null) },
                        modifier = Modifier.clickable {
                            vm.limpiarCache {
                                vm.usoCacheMb { usoCacheMb = it }
                                avisar(msgLimpia)
                            }
                        }
                    )
                }
            },
            confirmButton = { TextButton(onClick = { dlgAlmacenamiento = false }) { Text(stringResource(R.string.importar_listo)) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ajustes_titulo)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accion_atras))
                    }
                }
            )
        },
        // Encima del mini reproductor y la barra flotantes (si no, quedaba tapado).
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = 160.dp)) }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 160.dp)
        ) {
            // Orden: Sonido, Reproducción, Descargas, Letras, Apariencia, Tus datos,
            // Acerca de. Cada nivel de calidad fija su códec/formato (ver
            // AudioQuality.codec), así que ya no hay ajustes de códec ni formato; la
            // precarga de la siguiente canción está siempre activa.

            // ── Sonido ─────────────────────────────────────────────────────
            item { SettingsSectionHeader(stringResource(R.string.sec_sonido)) }
            item {
                SettingsClickItem(
                    title = stringResource(R.string.ajustes_calidad_repro),
                    subtitle = s.audioQuality.textoCompleto(),
                    icon = Icons.Default.HighQuality, onClick = { dlgCalidadRepro = true }
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Compresor de NormalizadorVolumen (DynamicsProcessing, Android 9+).
                item {
                    SettingsToggleItem(
                        title = stringResource(R.string.rep_normalizar),
                        subtitle = stringResource(R.string.rep_normalizar_desc),
                        checked = s.normalizarVolumen, onToggle = { vm.toggleNormalizar() },
                        icon = Icons.Default.GraphicEq
                    )
                }
            }
            item {
                SettingsToggleItem(
                    title = stringResource(R.string.rep_ahorro_audio),
                    subtitle = stringResource(
                        if (s.ahorroAudio && offloadRoto) R.string.rep_ahorro_audio_roto
                        else R.string.rep_ahorro_audio_desc),
                    checked = s.ahorroAudio, onToggle = { vm.toggleAhorroAudio() },
                    icon = Icons.Default.BatterySaver
                )
            }

            // ── Reproducción ───────────────────────────────────────────────
            item { SettingsSectionHeader(stringResource(R.string.sec_reproduccion)) }
            item {
                SettingsToggleItem(
                    title = stringResource(R.string.rep_automatica),
                    subtitle = stringResource(R.string.rep_automatica_desc),
                    checked = s.reproduccionAutomatica, onToggle = { vm.toggleReproduccionAutomatica() },
                    icon = Icons.Default.AllInclusive
                )
            }
            item {
                SettingsToggleItem(
                    title = stringResource(R.string.rep_aleatoria),
                    subtitle = stringResource(R.string.rep_aleatoria_desc),
                    checked = s.colaAleatoria, onToggle = { vm.toggleColaAleatoria() },
                    icon = Icons.Default.Shuffle
                )
            }
            item {
                SettingsToggleItem(
                    title = stringResource(R.string.rep_segundo_plano),
                    subtitle = stringResource(R.string.rep_segundo_plano_desc),
                    checked = s.reproduccionSegundoPlano, onToggle = { vm.toggleSegundoPlano() },
                    icon = Icons.Default.Headset
                )
            }
            item {
                SettingsToggleItem(
                    title = stringResource(R.string.rep_ocultar_explicito),
                    subtitle = stringResource(R.string.rep_ocultar_explicito_desc),
                    checked = s.ocultarExplicito, onToggle = { vm.toggleOcultarExplicito() },
                    icon = Icons.Default.Explicit
                )
            }

            // ── Descargas ──────────────────────────────────────────────────
            item { SettingsSectionHeader(stringResource(R.string.sec_descargas)) }
            item {
                SettingsClickItem(
                    title = stringResource(R.string.ajustes_calidad_descarga),
                    subtitle = s.downloadQuality.textoCompleto(),
                    icon = Icons.Default.HighQuality, onClick = { dlgCalidadDescarga = true }
                )
            }
            item {
                SettingsToggleItem(
                    title = stringResource(R.string.desc_solo_wifi),
                    subtitle = stringResource(R.string.desc_solo_wifi_desc),
                    checked = s.descargarSoloWifi, onToggle = { vm.toggleDescargaSoloWifi() },
                    icon = Icons.Default.Wifi
                )
            }
            item {
                // P: descargar automáticamente al dar me gusta.
                SettingsToggleItem(
                    title = stringResource(R.string.desc_al_like),
                    subtitle = stringResource(R.string.desc_al_like_desc),
                    checked = s.descargarAlDarLike, onToggle = { vm.toggleDescargarAlDarLike() },
                    icon = Icons.Default.Favorite
                )
            }
            item {
                SettingsToggleItem(
                    title = stringResource(R.string.desc_letras),
                    subtitle = stringResource(R.string.desc_letras_desc),
                    checked = s.descargarLetras, onToggle = { vm.toggleDescargarLetras() },
                    icon = Icons.Default.Lyrics
                )
            }

            // ── Letras ─────────────────────────────────────────────────────
            item { SettingsSectionHeader(stringResource(R.string.sec_letras)) }
            item {
                SettingsToggleItem(
                    title = stringResource(R.string.letras_mostrar),
                    subtitle = stringResource(R.string.letras_mostrar_desc),
                    checked = s.showLyrics, onToggle = { vm.toggleShowLyrics() },
                    icon = Icons.Default.Lyrics
                )
            }
            item {
                SettingsSliderItem(
                    title = stringResource(R.string.letras_tamano),
                    valor = s.tamanoFuenteLetras, rango = 12f..28f, pasos = 15,
                    icon = Icons.Default.FormatSize,
                    valorTexto = { stringResource(R.string.letras_tamano_valor, it) },
                    onCommit = { vm.setTamanoFuenteLetras(it) }
                )
            }
            item {
                SettingsSliderItem(
                    title = stringResource(R.string.letras_retraso),
                    valor = s.retrasoLetrasMs, rango = -500f..1500f, pasos = 19,
                    icon = Icons.Default.Bluetooth,
                    valorTexto = { stringResource(R.string.letras_retraso_valor, it / 1000f) },
                    onCommit = { vm.setRetrasoLetras(((it + 50) / 100) * 100) },
                    subtitle = stringResource(R.string.letras_retraso_desc)
                )
            }

            // ── Apariencia ─────────────────────────────────────────────────
            item { SettingsSectionHeader(stringResource(R.string.sec_apariencia)) }
            item {
                SettingsClickItem(
                    title = stringResource(R.string.ajustes_tema),
                    subtitle = s.tema.textoUi(),
                    icon = Icons.Default.Palette,
                    onClick = { dlgTema = true }
                )
            }
            item {
                SettingsToggleItem(
                    title = stringResource(R.string.ui_desenfocar),
                    subtitle = stringResource(R.string.ui_desenfocar_desc),
                    checked = s.blurPlayerBg, onToggle = { vm.toggleBlurPlayerBg() },
                    icon = Icons.Default.Palette
                )
            }
            // Liquid glass con refracción: necesita Android 13 (RuntimeShader).
            if (cristalDisponible()) {
                item {
                    SettingsToggleItem(
                        title = stringResource(R.string.ui_liquid_glass),
                        subtitle = stringResource(R.string.ui_liquid_glass_desc),
                        checked = s.liquidGlassEnabled, onToggle = { vm.toggleLiquidGlass() },
                        icon = Icons.Default.AutoAwesome
                    )
                }
            }

            // ── Tus datos ──────────────────────────────────────────────────
            item { SettingsSectionHeader(stringResource(R.string.sec_tus_datos)) }
            item {
                SettingsClickItem(
                    title = stringResource(R.string.respaldo_exportar),
                    subtitle = stringResource(R.string.respaldo_exportar_desc),
                    icon = Icons.Default.Backup,
                    onClick = {
                        val fecha = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                            .format(java.util.Date())
                        pickerExportar.launch("extmusic-$fecha.extmusic")
                    }
                )
            }
            item {
                SettingsClickItem(
                    title = stringResource(R.string.respaldo_importar),
                    subtitle = stringResource(R.string.respaldo_importar_desc),
                    icon = Icons.Default.Restore,
                    // */*: los .extmusic no tienen un tipo conocido; el contenido se valida al leerlo.
                    onClick = { pickerImportar.launch(arrayOf("*/*")) }
                )
            }
            item {
                SettingsClickItem(
                    title = stringResource(R.string.datos_musica_telefono),
                    subtitle = nombreCarpeta(s.carpetasEscaneo),
                    icon = Icons.Default.PhoneAndroid,
                    onClick = { dlgMusicaTelefono = true }
                )
            }
            item {
                SettingsClickItem(
                    title = stringResource(R.string.datos_almacenamiento),
                    subtitle = if (usoCacheMb < 0) stringResource(R.string.datos_almacenamiento_calculando)
                               else stringResource(R.string.datos_almacenamiento_uso, usoCacheMb, s.cacheMaximoMb),
                    icon = Icons.Default.Storage,
                    onClick = { dlgAlmacenamiento = true }
                )
            }

            // ── Acerca de ──────────────────────────────────────────────────
            item { SettingsSectionHeader(stringResource(R.string.sec_acerca)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_name)) },
                    supportingContent = { Text(stringResource(R.string.acerca_version, BuildConfig.VERSION_NAME)) },
                    leadingContent = { Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary) }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.acerca_creditos)) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.acerca_newpipe))
                            Text(stringResource(R.string.acerca_lrclib))
                            Text(stringResource(R.string.acerca_kugou))
                        }
                    },
                    leadingContent = { Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary) }
                )
            }
        }
    }
}

/** "Máxima · ~160 kbps · Opus": nombre y detalle juntos, para no tener que abrir el diálogo. */
@Composable
private fun AudioQuality.textoCompleto(): String = etiquetaUi() + " · " + descripcionUi()

/** Nombre legible de la carpeta elegida ("Music" en vez de content://…tree/primary%3AMusic). */
@Composable
private fun nombreCarpeta(uriCarpeta: String): String =
    if (uriCarpeta.isBlank()) stringResource(R.string.bib_carpetas_todas)
    else runCatching { Uri.parse(uriCarpeta).lastPathSegment?.substringAfter(':') }
        .getOrNull()?.ifBlank { null } ?: uriCarpeta

// ── Componentes de ajustes ────────────────────────────────────────────────────

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SettingsToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
    icon: ImageVector
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = { onToggle() }) },
        modifier = Modifier.clickable { onToggle() }
    )
}

@Composable
fun SettingsClickItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** Fila con deslizador para ajustes numéricos (crossfade, caché, tamaño de fuente). */
@Composable
fun SettingsSliderItem(
    title: String,
    valor: Int,
    rango: ClosedFloatingPointRange<Float>,
    pasos: Int,
    icon: ImageVector,
    valorTexto: @Composable (Int) -> String,
    onCommit: (Int) -> Unit,
    subtitle: String? = null
) {
    // Estado local mientras se arrastra; se confirma a DataStore al soltar.
    var local by remember(valor) { mutableFloatStateOf(valor.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                valorTexto(local.roundToInt()),
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium
            )
        }
        if (subtitle != null) Text(
            subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 40.dp)
        )
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local.roundToInt()) },
            valueRange = rango,
            steps = pasos
        )
    }
}

/** Diálogo genérico de selección de una opción de una lista. */
@Composable
fun <T> OpcionPickerDialog(
    titulo: String,
    opciones: List<Pair<T, String>>,
    actual: T,
    onElegir: (T) -> Unit,
    onCerrar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(titulo) },
        text = {
            Column {
                opciones.forEach { (valor, etiqueta) ->
                    ListItem(
                        headlineContent = { Text(etiqueta) },
                        trailingContent = {
                            if (valor == actual)
                                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier.clickable { onElegir(valor) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text(stringResource(R.string.accion_cancelar)) } }
    )
}

// Qué trajo el backup importado y, si había descargas que en este teléfono no
// están, la opción de volver a bajarlas.
@Composable
private fun DialogoRespaldoImportado(
    r: RespaldoRepository.Resumen, onDescargar: () -> Unit, onCerrar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCerrar,
        icon = { Icon(Icons.Default.Restore, null) },
        title = { Text(stringResource(R.string.respaldo_importado_titulo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.respaldo_importado, r.canciones, r.playlists))
                if (r.ajustes) Text(stringResource(R.string.respaldo_importado_ajustes))
                if (r.cola) Text(stringResource(R.string.respaldo_importado_cola))
                if (r.descartados > 0) Text(pluralStringResource(R.plurals.respaldo_descartados, r.descartados, r.descartados))
                if (r.porDescargar.isNotEmpty())
                    Text(pluralStringResource(R.plurals.respaldo_por_descargar, r.porDescargar.size, r.porDescargar.size))
            }
        },
        confirmButton = {
            if (r.porDescargar.isNotEmpty())
                TextButton(onClick = onDescargar) { Text(stringResource(R.string.respaldo_volver_a_descargar)) }
            else TextButton(onClick = onCerrar) { Text(stringResource(R.string.accion_aceptar)) }
        },
        dismissButton = {
            if (r.porDescargar.isNotEmpty()) TextButton(onClick = onCerrar) { Text(stringResource(R.string.accion_cerrar)) }
        }
    )
}
