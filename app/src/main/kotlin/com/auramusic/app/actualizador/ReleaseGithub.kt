package com.auramusic.app.actualizador

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Lo que se usa de `GET /repos/Comunicaa2/ExtMusic/releases/latest`. */
@Serializable
data class ReleaseGithub(
    @SerialName("tag_name") val tag: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetGithub> = emptyList()
)

@Serializable
data class AssetGithub(
    val name: String = "",
    val size: Long = 0,
    // "sha256:…": GitHub lo calcula al subir el archivo.
    val digest: String? = null,
    @SerialName("browser_download_url") val url: String = ""
)

/** Una versión publicada, ya validada. Se guarda tal cual en las preferencias del actualizador. */
@Serializable
data class VersionPublicada(
    val version: String,
    val notas: List<String>,
    val tamano: Long,
    val sha256: String,
    val url: String
)

object ValidarRelease {

    const val PREFIJO_DESCARGA = "https://github.com/Comunicaa2/ExtMusic/releases/download/"
    private const val TAMANO_MAXIMO = 50L * 1024 * 1024
    private val TAG = Regex("^v?(\\d{1,4})(\\.\\d{1,4}){1,3}$")
    private val DIGEST = Regex("^sha256:([0-9a-f]{64})$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    /** null si a la release le falta algo o algo no cuadra: entonces no se ofrece nada. */
    fun validar(r: ReleaseGithub): VersionPublicada? {
        if (r.draft || r.prerelease || !TAG.matches(r.tag)) return null
        val apk = r.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }.singleOrNull() ?: return null
        if (apk.size !in 1..TAMANO_MAXIMO) return null
        if (!apk.url.startsWith(PREFIJO_DESCARGA)) return null
        val sha = apk.digest?.let { DIGEST.find(it)?.groupValues?.get(1) } ?: return null
        return VersionPublicada(r.tag.removePrefix("v"), notas(r.body), apk.size, sha, apk.url)
    }

    /** Revalida una versión ya guardada (por si las preferencias se dañaron o se tocaron). */
    fun esValida(v: VersionPublicada): Boolean =
        TAG.matches(v.version) && v.tamano in 1..TAMANO_MAXIMO &&
            v.url.startsWith(PREFIJO_DESCARGA) && SHA256.matches(v.sha256)

    /** El cuerpo de la release es el changelog en texto plano: una novedad por línea. */
    private fun notas(body: String?): List<String> =
        body.orEmpty().lines()
            .map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("• ").trim() }
            .filter { it.isNotEmpty() }
            .take(12)
            .map { it.take(240) }

    /** ¿[a] es más nueva que [b]? Compara número a número ("1.10" > "1.9"). */
    fun esMasNueva(a: String, b: String): Boolean {
        val x = numeros(a); val y = numeros(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun numeros(v: String): List<Int> =
        v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
}
