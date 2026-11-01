package com.auramusic.app.ui.theme

import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.auramusic.app.domain.model.TemaApp

val AuraPurple      = Color(0xFF7C4DFF)
val AuraPurpleLight = Color(0xFFB47EFF)
val AuraBlue        = Color(0xFF448AFF)
val AuraDark        = Color(0xFF080810)
val AuraSurface     = Color(0xFF101018)
val AuraSurface2    = Color(0xFF18182A)

private val DarkColorScheme = darkColorScheme(
    primary          = AuraPurple,
    onPrimary        = Color.White,
    primaryContainer = Color(0xFF3D1F8A),
    secondary        = AuraBlue,
    background       = AuraDark,
    surface          = AuraSurface,
    surfaceVariant   = AuraSurface2,
    onBackground     = Color.White,
    onSurface        = Color.White,
    onSurfaceVariant = Color(0xFFB0B0CC)
)

private val LightColorScheme = lightColorScheme(
    primary          = AuraPurple,
    onPrimary        = Color.White,
    primaryContainer = AuraPurpleLight,
    secondary        = AuraBlue,
    background       = Color(0xFFF8F8FF),
    surface          = Color.White,
    surfaceVariant   = Color(0xFFEEEEFF),
    onBackground     = Color(0xFF0A0A14),
    onSurface        = Color(0xFF0A0A14),
    onSurfaceVariant = Color(0xFF44445A)
)

@Composable
fun AuraMusicTheme(
    tema: TemaApp = TemaApp.OSCURO,
    content: @Composable () -> Unit
) {
    // El tema es EXPLÍCITO (lo elige el usuario), no sigue al sistema.
    // En Android 12+ Claro y Oscuro usan los colores del wallpaper (Monet).
    val usarDinamico = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val ctx = LocalContext.current

    // Esquema oscuro de referencia: EXACTAMENTE el que usa el modo Oscuro.
    val esquemaOscuro = if (usarDinamico) dynamicDarkColorScheme(ctx) else DarkColorScheme

    val colorScheme = when (tema) {
        TemaApp.CLARO  -> {
            val base = if (usarDinamico) dynamicLightColorScheme(ctx) else LightColorScheme
            // Sube el contraste del texto secundario (artistas/subtítulos): Monet
            // lo deja gris muy claro sobre blanco. Gris oscuro legible (~9:1 sobre #FFF).
            base.copy(onSurfaceVariant = Color(0xFF44445A))
        }
        TemaApp.OSCURO -> esquemaOscuro
        // AMOLED = el MISMO modo Oscuro, pero con los fondos en negro puro.
        // Acentos, texto, iconos y toggles quedan IDÉNTICOS al Oscuro; solo los
        // fondos grises se vuelven #000000. No es un esquema nuevo.
        TemaApp.AMOLED -> esquemaOscuro.copy(
            background       = Color(0xFF000000),
            surface          = Color(0xFF000000),
            surfaceVariant   = Color(0xFF000000),
            surfaceContainer = Color(0xFF000000),
        )
    }

    MaterialTheme(colorScheme = colorScheme, typography = AuraTypography, content = content)
}
