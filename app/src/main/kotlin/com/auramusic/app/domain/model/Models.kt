package com.auramusic.app.domain.model

/**
 * Origen de una canción dentro de la cola unificada (Fase 1).
 *
 * El reproductor (ExoPlayer) trata ambos por igual: una fuente es una fuente.
 *  - LOCAL:   la `fuente` es un Uri de contenido del almacenamiento del teléfono.
 *  - YOUTUBE: la `fuente` es un videoId que luego se resuelve a una URL de stream.
 *
 * Esto evita mantener dos sistemas paralelos: la misma cola, el mismo
 * SongEntity y la misma UI sirven para archivos locales y para YouTube Music.
 */
enum class OrigenCancion { LOCAL, YOUTUBE }

// ─── Ajustes de la app (Fase 2) ──────────────────────────────────────────────
// Tipos del dominio para que tanto la capa de datos (RepositorioPreferencias)
// como la UI (SettingsViewModel/SettingsScreen) los compartan sin acoplarse.

/** Calidad de audio para reproducción y descargas. */
enum class AudioQuality(val label: String, val description: String) {
    LOW   ("Ahorro de datos", "~50 kbps"),
    MEDIUM("Normal",          "~128 kbps · AAC"),
    HIGH  ("Máxima",          "~160 kbps · Opus"),
}

/**
 * Cada nivel fija su códec: es la combinación que da ese bitrate en YouTube
 * (AAC 48/128 kbps, Opus 50/70/160 kbps). Así el usuario elige solo "calidad"
 * y no hay combinaciones engañosas como Máxima + AAC = 128 kbps.
 */
val AudioQuality.codec: CodecAudio
    get() = when (this) {
        AudioQuality.LOW    -> CodecAudio.OPUS
        AudioQuality.MEDIUM -> CodecAudio.AAC
        AudioQuality.HIGH   -> CodecAudio.OPUS
    }

/** Formato del archivo descargado: el que corresponde al códec de la calidad. */
val AudioQuality.formatoDescarga: FormatoDescarga
    get() = if (codec == CodecAudio.AAC) FormatoDescarga.M4A else FormatoDescarga.OPUS

/** Bitrate aproximado en kbps de cada nivel (para la etiqueta de calidad). */
val AudioQuality.kbpsAprox: Int
    get() = when (this) {
        AudioQuality.LOW    -> 50
        AudioQuality.MEDIUM -> 128
        AudioQuality.HIGH   -> 160
    }

/**
 * Tema visual de la app (selector de 3 opciones, no toggle).
 *  - CLARO:  esquema claro.
 *  - OSCURO: esquema oscuro normal (gris de Material).
 *  - AMOLED: negro puro #000000 en los fondos → apaga píxeles y ahorra batería
 *            en pantallas OLED/AMOLED.
 */
enum class TemaApp(val etiqueta: String) {
    CLARO("Claro"),
    OSCURO("Oscuro"),
    AMOLED("AMOLED (negro puro)"),
}

/** Códec de audio preferido al transmitir (AAC = M4A, Opus = WebM). Lo aplica InnertubeClient. */
enum class CodecAudio { AAC, OPUS }

/** Formato/contenedor del archivo al descargar. Mejora W: sin MP3 (la descarga va
 *  a la caché privada filesDir; MP3 no aporta y ocupa más). Solo M4A (def.) y Opus. */
enum class FormatoDescarga(val extension: String) {
    M4A("m4a"),
    OPUS("opus"),
}

/**
 * Estado completo de los ajustes que la app guarda y aplica (Fase 2).
 *
 * Convención de campos según su estado de cableado:
 *  - Cableados ya (afectan comportamiento): tema, interfaz, letras, aleatoria…
 *  - Guardados SIN efecto (no se muestran en Ajustes): datos móviles, región,
 *    caché de streams y crossfade. Códec, formato de descarga y precarga ya no
 *    se leen: el códec/formato salen de la calidad (AudioQuality.codec) y la
 *    precarga está siempre activa.
 */
data class AppSettings(
    // ── Apariencia / interfaz ──────────────────────────────────────────────
    val tema: TemaApp = TemaApp.OSCURO,
    val blurPlayerBg: Boolean = true,
    val liquidGlassEnabled: Boolean = true,
    // ── Reproducción ───────────────────────────────────────────────────────
    val reproduccionSegundoPlano: Boolean = true,
    val crossfadeSegundos: Int = 0,            // 0..10 s
    val normalizarVolumen: Boolean = false,
    val ahorroAudio: Boolean = false,          // audio offload: decodifica el chip de audio
    val colaAleatoria: Boolean = false,
    val ocultarExplicito: Boolean = false,     // Q: oculta canciones explicit en la UI
    val reproduccionAutomatica: Boolean = true, // al acabar la cola sigue con canciones parecidas
    // ── YouTube Music ─────────────────────────────────────────────────────
    val permitirDatosMoviles: Boolean = true,
    val codecAudio: CodecAudio = CodecAudio.AAC,
    val precargarSiguiente: Boolean = true,
    val regionPais: String = "",               // "" = automática
    val cachearStreams: Boolean = true,
    // ── Calidad de audio ───────────────────────────────────────────────────
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val downloadQuality: AudioQuality = AudioQuality.HIGH,
    // ── Descargas ──────────────────────────────────────────────────────────
    val formatoDescarga: FormatoDescarga = FormatoDescarga.M4A,
    val descargarAlDarLike: Boolean = false,   // P: al dar me gusta, descarga en caché
    val carpetaDescargas: String = "",         // "" = carpeta de la app
    val incrustarCaratula: Boolean = true,
    val descargarLetras: Boolean = true,
    val descargarSoloWifi: Boolean = false,
    val descargasSimultaneas: Int = 2,         // 1..3
    // ── Biblioteca local ───────────────────────────────────────────────────
    val carpetasEscaneo: String = "",          // "" = todo el dispositivo
    // ── Caché ──────────────────────────────────────────────────────────────
    val cacheMaximoMb: Int = 256,
    // ── Letras ─────────────────────────────────────────────────────────────
    val showLyrics: Boolean = true,
    val letrasAutomaticas: Boolean = true,
    val tamanoFuenteLetras: Int = 16,          // sp (base; el visor completo lo agranda)
    // Retraso de la letra respecto al audio (ms): con auriculares Bluetooth el
    // sonido llega tarde y la letra se adelanta; subiéndolo se compensa.
    val retrasoLetrasMs: Int = 0,
)

// ─── Identidad de canciones (reproducción automática y recomendaciones) ─────

/** Formato de un videoId de YouTube (11 caracteres base64url). */
val ID_YOUTUBE = Regex("^[A-Za-z0-9_-]{11}$")

/**
 * Clave de "la misma canción" aunque tenga otro videoId (YouTube tiene varias
 * ediciones: single, álbum, remaster…). "Proverbs 31 Woman (Remastered)" +
 * "Dwayne Tryumf, X" → "proverbs 31 woman|dwayne tryumf": sin acentos ni
 * mayúsculas, sin lo que va entre paréntesis/corchetes y solo el primer artista.
 */
fun claveCancion(titulo: String, artista: String): String {
    fun limpiar(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[(\\[].*?[)\\]]"), " ")
        .replace(Regex("\\s+"), " ").trim()
    val primerArtista = artista.split(",", "&", " y ", " x ", " feat", " ft.").first().removeSuffix(" - Topic")
    return limpiar(titulo) + "|" + limpiar(primerArtista)
}
