package com.auramusic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class AudioQualityBadge(val label: String, val color: Color) {
    // "HQ" (antes "Hi-Res"): YouTube Music es SIEMPRE lossy (sin cuenta, ≤160 kbps) y el
    // badge se decide solo por bitrate (no por sample rate ≥96 kHz ni bit depth ≥24),
    // así que NO es Hi-Res real. Se etiqueta "HQ" para no engañar al usuario. El badge
    // LOSSLESS sí es honesto: solo sale con formatos flac/alac/wav (ver detectQualityBadge).
    HI_RES  ("HQ",      Color(0xFF00C853)),   // verde vibrante — alta calidad (lossy)
    LOSSLESS("Lossless",Color(0xFF2979FF)),   // azul
    HIGH    ("High",    Color(0xFF7C4DFF)),   // púrpura
    VBR     ("VBR",     Color(0xFFFF6D00)),   // naranja
    UNKNOWN ("",        Color.Transparent)
}

/**
 * Detecta la calidad del formato de audio y devuelve el badge correcto.
 */
fun detectQualityBadge(mimeType: String, bitrate: Int, sampleRate: Int = 0): AudioQualityBadge {
    val mime = mimeType.lowercase()
    return when {
        mime.contains("flac") || mime.contains("alac") || mime.contains("wav") -> AudioQualityBadge.LOSSLESS
        sampleRate >= 44100 && bitrate >= 256_000 -> AudioQualityBadge.HI_RES
        bitrate >= 192_000 -> AudioQualityBadge.HIGH
        mime.contains("opus") || mime.contains("vorbis") -> AudioQualityBadge.VBR
        else -> AudioQualityBadge.UNKNOWN
    }
}

/**
 * Etiqueta a partir del bitrate en **kbps** (lo que guarda downloadQuality y da
 * NewPipe). YouTube sin cuenta llega a ~160 kbps: Opus 160 → "HQ", AAC 128 →
 * "High"; por debajo no se muestra nada (antes salía "VBR", que no informa).
 */
fun bitrateToQualityBadge(kbps: Int): AudioQualityBadge = when {
    kbps >= 150 -> AudioQualityBadge.HI_RES
    kbps >= 100 -> AudioQualityBadge.HIGH
    else        -> AudioQualityBadge.UNKNOWN
}

@Composable
fun QualityBadge(badge: AudioQualityBadge, modifier: Modifier = Modifier) {
    if (badge == AudioQualityBadge.UNKNOWN || badge.label.isEmpty()) return
    Text(
        text = badge.label,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = modifier
            .background(badge.color, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}
