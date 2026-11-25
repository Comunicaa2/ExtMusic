package com.auramusic.app.actualizador

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * Firmas de la app instalada y de una APK descargada.
 *
 * El actualizador solo existe en las copias firmadas con la clave de las releases
 * de GitHub: F-Droid firma con su propia clave (Android rechazaría la APK de GitHub
 * encima) y el debug (Mambo) con la de depuración.
 */
object FirmaPropia {

    /** SHA-256 del certificado de las APK de GitHub. No es secreto: va dentro de cada APK. */
    const val HUELLA_GITHUB = "7492411a3df91a2128010740826791eed78f15deba2a962bea2ca6e9e2a3e2a7"

    /** ¿Esta copia es la de GitHub? Hace una consulta al PackageManager: llamar fuera del hilo principal. */
    fun esDeGithub(ctx: Context): Boolean = HUELLA_GITHUB in huellasInstaladas(ctx)

    fun huellasInstaladas(ctx: Context): Set<String> = runCatching {
        huellas(ctx.packageManager.getPackageInfo(ctx.packageName, banderas()))
    }.getOrDefault(emptySet())

    fun versionInstalada(ctx: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
    }.getOrDefault(0L)

    /** Paquete, versionCode y huellas de una APK en disco, o null si Android no la puede leer. */
    fun leerApk(ctx: Context, apk: File): DatosApk? = runCatching {
        val info = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, banderas()) ?: return null
        DatosApk(info.packageName, PackageInfoCompat.getLongVersionCode(info), huellas(info))
    }.getOrNull()

    data class DatosApk(val paquete: String, val versionCode: Long, val huellas: Set<String>)

    // Se piden las dos: en algunos Android, una APK sin instalar solo trae una de ellas.
    @Suppress("DEPRECATION")
    private fun banderas(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun huellas(info: PackageInfo): Set<String> {
        val firmas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners ?: info.signatures
        else info.signatures
        return firmas.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** SHA-256 de un archivo, leído por bloques (una APK son unos 5 MB). */
    fun sha256(archivo: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        archivo.inputStream().use { entrada ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = entrada.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
