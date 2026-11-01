@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.auramusic.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.auramusic.app.R
import com.auramusic.app.ui.navigation.Screen

/**
 * Pantalla de la biblioteca LOCAL del teléfono (Fase 1).
 * Pide el permiso de audio adecuado según la versión de Android, escanea con
 * MediaStore y reproduce los archivos directamente desde el almacenamiento.
 */
@Composable
fun CancionesLocalesScreen(navController: NavController, playerViewModel: PlayerViewModel) {
    val vm: BibliotecaLocalViewModel = hiltViewModel()
    val origenLocal = stringResource(R.string.nav_local)
    val estado by vm.estado.collectAsStateWithLifecycle()
    val contexto = LocalContext.current

    // Android 13+ (API 33) usa READ_MEDIA_AUDIO; por debajo, READ_EXTERNAL_STORAGE.
    val permiso = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val lanzadorPermiso = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) vm.escanear() else vm.marcarSinPermiso()
    }

    // Al entrar: si ya hay permiso escanea; si no, lo pide.
    LaunchedEffect(Unit) {
        val yaConcedido = ContextCompat.checkSelfPermission(contexto, permiso) ==
            PackageManager.PERMISSION_GRANTED
        if (yaConcedido) vm.escanear() else lanzadorPermiso.launch(permiso)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.local_titulo)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.accion_atras))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when (val e = estado) {
                EstadoBibliotecaLocal.Inicial,
                EstadoBibliotecaLocal.Cargando ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }

                EstadoBibliotecaLocal.SinPermiso ->
                    MensajeCentrado(
                        icono = { Icon(Icons.Outlined.MusicOff, null, Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        texto = stringResource(R.string.local_sin_permiso),
                        textoBoton = stringResource(R.string.local_conceder_permiso),
                        onBoton = { lanzadorPermiso.launch(permiso) }
                    )

                is EstadoBibliotecaLocal.Lista -> {
                    if (e.canciones.isEmpty()) {
                        MensajeCentrado(
                            icono = { Icon(Icons.Outlined.LibraryMusic, null, Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            texto = stringResource(R.string.local_vacio),
                            textoBoton = null, onBoton = {}
                        )
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 160.dp)) {
                            item {
                                SectionTitle(
                                    pluralStringResource(R.plurals.n_canciones, e.canciones.size, e.canciones.size),
                                    Modifier.padding(horizontal = 16.dp)
                                )
                            }
                            itemsIndexed(e.canciones) { index, cancion ->
                                SongRow(song = cancion, onPlay = {
                                    playerViewModel.reproducirCola(e.canciones, index, origenLocal)
                                    playerViewModel.abrirReproductor()
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MensajeCentrado(
    icono: @Composable () -> Unit,
    texto: String,
    textoBoton: String?,
    onBoton: () -> Unit
) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icono()
            Spacer(Modifier.height(16.dp))
            Text(texto, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (textoBoton != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBoton) { Text(textoBoton) }
            }
        }
    }
}
