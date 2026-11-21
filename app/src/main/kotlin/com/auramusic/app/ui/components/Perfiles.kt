package com.auramusic.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auramusic.app.R
import com.auramusic.app.data.db.MAX_PERFILES
import com.auramusic.app.data.db.PERFIL_PRINCIPAL
import com.auramusic.app.data.db.PerfilEntity
import com.auramusic.app.ui.screens.PerfilesViewModel

/**
 * Botón del perfil de gusto en la barra del Inicio: muestra el activo y, al tocarlo,
 * la lista para cambiar, crear uno nuevo o gestionarlos.
 */
@Composable
fun SelectorPerfil(vm: PerfilesViewModel) {
    val perfiles by vm.perfiles.collectAsStateWithLifecycle()
    val activo by vm.activo.collectAsStateWithLifecycle()
    var abierto by remember { mutableStateOf(false) }
    var crear by remember { mutableStateOf(false) }
    var gestionar by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { abierto = true }, contentPadding = PaddingValues(horizontal = 10.dp),
            modifier = Modifier.heightIn(min = 44.dp)) {
            Icon(Icons.Default.Person, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(activo?.nombre.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp))
        }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            Text(stringResource(R.string.perfiles_titulo), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            perfiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.nombre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        if (p.activo) Icon(Icons.Default.Check, stringResource(R.string.perfil_activo))
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = { abierto = false; vm.cambiarA(p.id) }
                )
            }
            HorizontalDivider()
            if (perfiles.size < MAX_PERFILES) DropdownMenuItem(
                text = { Text(stringResource(R.string.perfil_nuevo)) },
                leadingIcon = { Icon(Icons.Default.Add, null) },
                onClick = { abierto = false; crear = true }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.perfil_gestionar)) },
                leadingIcon = { Icon(Icons.Default.ManageAccounts, null) },
                onClick = { abierto = false; gestionar = true }
            )
        }
    }

    if (crear) DialogoNombrePerfil(
        titulo = stringResource(R.string.perfil_nuevo), inicial = "",
        explicacion = stringResource(R.string.perfil_nuevo_explica),
        onAceptar = { vm.crear(it); crear = false }, onCerrar = { crear = false }
    )
    if (gestionar) DialogoGestionPerfiles(vm) { gestionar = false }
}

/** Crear, renombrar y borrar perfiles (desde el Inicio o desde Ajustes). */
@Composable
fun DialogoGestionPerfiles(vm: PerfilesViewModel, onCerrar: () -> Unit) {
    val perfiles by vm.perfiles.collectAsStateWithLifecycle()
    var renombrar by remember { mutableStateOf<PerfilEntity?>(null) }
    var borrar by remember { mutableStateOf<PerfilEntity?>(null) }
    var crear by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(stringResource(R.string.perfiles_titulo)) },
        text = {
            Column {
                Text(stringResource(R.string.perfiles_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                perfiles.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.nombre, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.activo) Text(stringResource(R.string.perfil_activo),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { renombrar = p }) {
                            Icon(Icons.Default.Edit, stringResource(R.string.perfil_renombrar))
                        }
                        // El principal y el activo no se pueden borrar.
                        IconButton(onClick = { borrar = p }, enabled = !p.activo && p.id != PERFIL_PRINCIPAL) {
                            Icon(Icons.Default.DeleteOutline, stringResource(R.string.perfil_borrar))
                        }
                    }
                }
                if (perfiles.size < MAX_PERFILES) TextButton(onClick = { crear = true }) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.perfil_nuevo))
                } else Text(stringResource(R.string.perfil_limite, MAX_PERFILES),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text(stringResource(R.string.accion_cerrar)) } }
    )

    if (crear) DialogoNombrePerfil(
        titulo = stringResource(R.string.perfil_nuevo), inicial = "",
        explicacion = stringResource(R.string.perfil_nuevo_explica),
        onAceptar = { vm.crear(it); crear = false }, onCerrar = { crear = false }
    )
    renombrar?.let { p ->
        DialogoNombrePerfil(
            titulo = stringResource(R.string.perfil_renombrar), inicial = p.nombre, explicacion = null,
            onAceptar = { vm.renombrar(p.id, it); renombrar = null }, onCerrar = { renombrar = null }
        )
    }
    borrar?.let { p ->
        AlertDialog(
            onDismissRequest = { borrar = null },
            title = { Text(stringResource(R.string.perfil_borrar)) },
            text = { Text(stringResource(R.string.perfil_borrar_confirmar, p.nombre)) },
            confirmButton = {
                TextButton(onClick = { vm.borrar(p.id); borrar = null }) {
                    Text(stringResource(R.string.perfil_borrar), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { borrar = null }) { Text(stringResource(R.string.accion_cancelar)) } }
        )
    }
}

/** Ajustes ▸ Canciones no recomendadas: las marcadas en el perfil activo, para devolverlas. */
@Composable
fun DialogoNoRecomendadas(vm: PerfilesViewModel, onCerrar: () -> Unit) {
    val canciones by vm.noRecomendadas.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(stringResource(R.string.no_recomendadas_titulo)) },
        text = {
            if (canciones.isEmpty()) Text(stringResource(R.string.no_recomendadas_vacio),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(canciones.size, key = { canciones[it].id }) { i ->
                    val c = canciones[i]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { vm.volverARecomendar(c.id) }) {
                            Text(stringResource(R.string.no_recomendadas_devolver))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCerrar) { Text(stringResource(R.string.accion_cerrar)) } }
    )
}

@Composable
private fun DialogoNombrePerfil(
    titulo: String, inicial: String, explicacion: String?,
    onAceptar: (String) -> Unit, onCerrar: () -> Unit
) {
    var nombre by remember { mutableStateOf(inicial) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(titulo) },
        text = {
            Column {
                explicacion?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(value = nombre, onValueChange = { nombre = it.take(40) }, singleLine = true,
                    label = { Text(stringResource(R.string.perfil_nombre)) })
            }
        },
        confirmButton = {
            TextButton(onClick = { onAceptar(nombre) }, enabled = nombre.isNotBlank()) {
                Text(stringResource(R.string.accion_aceptar))
            }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text(stringResource(R.string.accion_cancelar)) } }
    )
}
