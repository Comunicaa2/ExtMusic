package com.auramusic.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.auramusic.app.R
import com.auramusic.app.work.FalloDescarga
import com.auramusic.app.work.MotivoFallo

/** "Reintentar descarga" con el motivo debajo, en el ⋮ de la lista y del reproductor. */
@Composable
fun TextoReintentarDescarga(fallo: FalloDescarga) {
    Column {
        Text(stringResource(R.string.desc_reintentar))
        Text(
            stringResource(if (fallo.motivo == MotivoFallo.ESPACIO) R.string.desc_fallo_espacio else R.string.desc_fallo_otro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun IconoReintentarDescarga() = Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.error)
