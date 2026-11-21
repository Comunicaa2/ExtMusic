package com.auramusic.app.data.repository

import android.content.Context
import com.auramusic.app.data.db.PERFIL_PRINCIPAL
import java.io.File

/**
 * Archivos que se guardan por perfil de gusto (Inicio y recomendaciones). El perfil
 * principal conserva los de siempre en filesDir; los demás van en filesDir/perfiles/<id>.
 */
object ArchivosPerfil {
    private val ID_SEGURO = Regex("^[\\w-]{1,40}$")

    fun archivo(contexto: Context, perfilId: String?, nombre: String): File {
        val id = perfilId?.takeIf { ID_SEGURO.matches(it) } ?: PERFIL_PRINCIPAL
        return if (id == PERFIL_PRINCIPAL) File(contexto.filesDir, nombre)
        else File(File(contexto.filesDir, "perfiles/$id").apply { mkdirs() }, nombre)
    }

    /** Al borrar un perfil, sus archivos también se van. */
    fun borrar(contexto: Context, perfilId: String) {
        if (perfilId == PERFIL_PRINCIPAL || !ID_SEGURO.matches(perfilId)) return
        File(contexto.filesDir, "perfiles/$perfilId").deleteRecursively()
    }
}
