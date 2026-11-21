package com.auramusic.app.data.db

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ─── Entidades ─────────────────────────────────────────────────────────────

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val artistId: String,
    val album: String,
    val thumbnailUrl: String,
    val durationMs: Long,
    val isLiked: Boolean = false,
    val playCount: Long = 0,
    val lastPlayed: Long = 0,
    val isDownloaded: Boolean = false,
    val localPath: String? = null,
    val downloadQuality: Int = 0, // bitrate
    // ── Modelo unificado (Fase 1) ───────────────────────────────────────────
    // origen: "LOCAL" o "YOUTUBE" (ver OrigenCancion).
    // fuente: Uri de contenido del teléfono (LOCAL) o videoId (YOUTUBE).
    // Defaults pensados para que las filas existentes sigan siendo de YouTube.
    val origen: String = "YOUTUBE",
    val fuente: String = "",
    // Q: marca de contenido explícito (de YouTube Music). Sirve para ocultarlo
    // cuando el ajuste "Ocultar contenido explícito" está activo.
    val explicit: Boolean = false,
    // "No recomendar" (⋮): no vuelve a salir en sugerencias, radio ni mixes.
    // Como isLiked/playCount/lastPlayed, es del perfil de gusto activo (ver PerfilEntity).
    val noRecomendar: Boolean = false
)

/**
 * Perfil de gusto: historial, Me gusta, "no recomendar" y playlists propios; las
 * descargas son de todos. Las columnas de `songs` (isLiked, playCount, lastPlayed,
 * noRecomendar) son SIEMPRE las del perfil activo; las de los demás esperan en
 * [PerfilCancion]. Cambiar de perfil intercambia ambas en una transacción
 * ([PerfilDao.cambiarA]), así el resto de la app no necesita saber de perfiles.
 */
@Entity(tableName = "perfiles")
data class PerfilEntity(
    @PrimaryKey val id: String,
    val nombre: String,
    val creado: Long,
    val activo: Boolean
)

/** Estado de una canción en un perfil que NO está activo. */
@Entity(
    tableName = "perfil_canciones",
    primaryKeys = ["perfilId", "songId"],
    foreignKeys = [
        ForeignKey(PerfilEntity::class, ["id"], ["perfilId"], ForeignKey.CASCADE),
        ForeignKey(SongEntity::class, ["id"], ["songId"], ForeignKey.CASCADE)
    ],
    indices = [Index("songId")]
)
data class PerfilCancion(
    val perfilId: String,
    val songId: String,
    val isLiked: Boolean,
    val playCount: Long,
    val lastPlayed: Long,
    val noRecomendar: Boolean
)

const val PERFIL_PRINCIPAL = "principal"
const val MAX_PERFILES = 5

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val thumbnailUrl: String
)

@Entity(tableName = "playlists", indices = [Index("perfilId")])
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    // Sin valor por defecto en Kotlin a propósito: cada alta dice de qué perfil es.
    @ColumnInfo(defaultValue = PERFIL_PRINCIPAL) val perfilId: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], ForeignKey.CASCADE),
        ForeignKey(SongEntity::class, ["id"], ["songId"], ForeignKey.CASCADE)
    ],
    indices = [Index("songId")]   // ← elimina el warning de full table scan
)
data class PlaylistSongCrossRef(
    val playlistId: String,
    val songId: String,
    val position: Int = 0
)

/** Columnas de `songs` que necesita "Tus artistas" del Inicio (ver [SongDao.escuchasPorArtista]). */
data class EscuchaArtista(
    val id: String,
    val fuente: String,
    val artist: String,
    val artistId: String,
    val playCount: Long,
    val lastPlayed: Long
)

// ─── DAOs ──────────────────────────────────────────────────────────────────

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY lastPlayed DESC")
    fun getAll(): Flow<List<SongEntity>>

    // Inicio. Room vuelve a emitir con cada canción que suena (escribe su fila): en
    // vez de la tabla entera, solo lo que usa cada sección. length(...) = 11 es el
    // primer filtro de "es de YouTube"; el exacto (ID_YOUTUBE) sigue en el Inicio.
    /** Selección rápida: las más escuchadas (empates: la más reciente primero). */
    @Query("""SELECT * FROM songs WHERE playCount > 0 AND noRecomendar = 0
        AND (length(fuente) = 11 OR length(id) = 11) AND (:conExplicito OR explicit = 0)
        ORDER BY playCount DESC, lastPlayed DESC LIMIT :limite""")
    fun masEscuchadas(conExplicito: Boolean, limite: Int): Flow<List<SongEntity>>

    /** Tus artistas: de cada canción escuchada, solo lo necesario para agruparlas. */
    @Query("""SELECT id, fuente, artist, artistId, playCount, lastPlayed FROM songs
        WHERE playCount > 0 AND (length(fuente) = 11 OR length(id) = 11) ORDER BY lastPlayed DESC""")
    fun escuchasPorArtista(): Flow<List<EscuchaArtista>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE isLiked = 1 ORDER BY title")
    fun getLiked(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isDownloaded = 1 ORDER BY title")
    fun getDownloaded(): Flow<List<SongEntity>>

    // Solo lo escuchado: las canciones de otros perfiles están con lastPlayed = 0.
    @Query("SELECT * FROM songs WHERE lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT 20")
    fun getRecentlyPlayed(): Flow<List<SongEntity>>

    // Recientes SOLO de streaming (Mejora 12): el Inicio no debe mezclar locales,
    // que tienen su propio flujo en la pestaña "Local".
    @Query("SELECT * FROM songs WHERE origen != 'LOCAL' AND lastPlayed > 0 AND noRecomendar = 0 ORDER BY lastPlayed DESC LIMIT 20")
    fun getRecentlyPlayedStreaming(): Flow<List<SongEntity>>

    /** Las canciones de este perfil (escuchadas, Me gusta o marcadas) y las descargadas, que son de todos. */
    @Query("SELECT * FROM songs WHERE playCount > 0 OR lastPlayed > 0 OR isLiked = 1 OR noRecomendar = 1 OR isDownloaded = 1 ORDER BY lastPlayed DESC")
    fun delPerfil(): Flow<List<SongEntity>>

    /** Marcadas con "No recomendar" en el perfil activo. */
    // Del perfil activo (los demás tienen lastPlayed = 0 en `songs`).
    @Query("SELECT id FROM songs WHERE lastPlayed > :desde")
    suspend fun escuchadasDesde(desde: Long): List<String>

    @Query("SELECT * FROM songs WHERE noRecomendar = 1 ORDER BY title")
    fun noRecomendadas(): Flow<List<SongEntity>>

    @Query("UPDATE songs SET noRecomendar = :valor WHERE id = :id")
    suspend fun setNoRecomendar(id: String, valor: Boolean)

    @Query("SELECT * FROM songs WHERE artist = :artist ORDER BY title")
    fun getByArtist(artist: String): Flow<List<SongEntity>>

    @Query("SELECT DISTINCT artist, artistId, thumbnailUrl FROM songs ORDER BY artist")
    fun getArtists(): Flow<List<ArtistSummary>>

    @Upsert
    suspend fun upsert(song: SongEntity)

    // Escritura en lote: una sola transacción → una sola invalidación de Room
    // (en vez de N). Clave para que la sincronización no dispare una tormenta de
    // recomposiciones en las listas observadas con Flow.
    @Upsert
    suspend fun upsert(songs: List<SongEntity>)

    // IDs existentes para decidir insertar-vs-actualizar SIN una lectura por fila.
    @Query("SELECT id FROM songs")
    suspend fun todosLosIds(): List<String>

    @Query("UPDATE songs SET isLiked = :liked WHERE id = :id")
    suspend fun setLiked(id: String, liked: Boolean)

    // Marca/desmarca "me gusta" en lote (una transacción para toda la lista).
    @Query("UPDATE songs SET isLiked = :liked WHERE id IN (:ids)")
    suspend fun setLikedBulk(ids: List<String>, liked: Boolean)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayed = :time WHERE id = :id")
    suspend fun incrementPlayCount(id: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE songs SET isDownloaded = :downloaded, localPath = :path, downloadQuality = :quality WHERE id = :id")
    suspend fun setDownloaded(id: String, downloaded: Boolean, path: String?, quality: Int)

    @Query("SELECT * FROM songs WHERE playCount > 0 ORDER BY playCount DESC LIMIT 10")
    fun getMostPlayed(): Flow<List<SongEntity>>

    // Lectura completa (no Flow) para exportar el backup.
    @Query("SELECT * FROM songs")
    suspend fun todas(): List<SongEntity>
}

data class ArtistSummary(
    val artist: String,
    val artistId: String,
    val thumbnailUrl: String
)

@Dao
interface PlaylistDao {
    // Solo las del perfil activo (la consulta también observa la tabla perfiles:
    // al cambiar de perfil la lista se actualiza sola).
    @Query("SELECT * FROM playlists WHERE perfilId = (SELECT id FROM perfiles WHERE activo = 1) ORDER BY createdAt DESC")
    fun getAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id AND perfilId = (SELECT id FROM perfiles WHERE activo = 1)")
    suspend fun getById(id: String): PlaylistEntity?

    /** Perfil al que pertenece una playlist nueva. */
    @Query("SELECT id FROM perfiles WHERE activo = 1 LIMIT 1")
    suspend fun perfilActivo(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: PlaylistEntity)

    // Inserción en lote (una transacción) para la sincronización.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(playlists: List<PlaylistEntity>)

    @Delete
    suspend fun delete(playlist: PlaylistEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)

    // Vacía una playlist (sus relaciones). La sync la usa para reconstruirla solo
    // con música y así eliminar videos generales que hubieran entrado antes (Bug J).
    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongToPlaylist(crossRef: PlaylistSongCrossRef)

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun cuentaEnPlaylist(playlistId: String, songId: String): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun siguientePosicion(playlistId: String): Int

    /**
     * Añade una canción al final de la playlist (antes todas entraban en la posición 0
     * y el orden quedaba al azar). Si ya estaba, no se mueve: devuelve false.
     */
    @Transaction
    suspend fun anadirAlFinal(playlistId: String, songId: String): Boolean {
        if (cuentaEnPlaylist(playlistId, songId) > 0) return false
        addSongToPlaylist(PlaylistSongCrossRef(playlistId, songId, siguientePosicion(playlistId)))
        return true
    }

    // Relaciona muchas canciones con sus playlists en una sola transacción.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongsToPlaylist(crossRefs: List<PlaylistSongCrossRef>)

    @Query("""
        SELECT s.* FROM songs s 
        INNER JOIN playlist_songs ps ON s.id = ps.songId 
        WHERE ps.playlistId = :playlistId 
        ORDER BY ps.position
    """)
    fun getPlaylistSongs(playlistId: String): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    fun getSongCount(playlistId: String): Flow<Int>

    /** Las del perfil activo, sin Flow. */
    @Query("SELECT * FROM playlists WHERE perfilId = (SELECT id FROM perfiles WHERE activo = 1)")
    suspend fun todas(): List<PlaylistEntity>

    // ── Backup ──────────────────────────────────────────────────────────────
    /** Las de todos los perfiles (solo el backup). */
    @Query("SELECT * FROM playlists")
    suspend fun todasDeTodos(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_songs ORDER BY playlistId, position")
    suspend fun todasLasRelaciones(): List<PlaylistSongCrossRef>

    // IGNORE (no REPLACE): REPLACE borra la fila y el CASCADE se llevaría las
    // canciones de una playlist que ya existe. Al importar solo se añade.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertarSiNoExisten(playlists: List<PlaylistEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun relacionarSiNoExisten(crossRefs: List<PlaylistSongCrossRef>)
}

// ─── Base de datos ─────────────────────────────────────────────────────────

@Dao
interface PerfilDao {
    @Query("SELECT * FROM perfiles ORDER BY creado")
    fun todos(): Flow<List<PerfilEntity>>

    @Query("SELECT * FROM perfiles ORDER BY creado")
    suspend fun lista(): List<PerfilEntity>

    @Query("SELECT * FROM perfiles WHERE activo = 1 LIMIT 1")
    fun activo(): Flow<PerfilEntity?>

    @Query("SELECT id FROM perfiles WHERE activo = 1 LIMIT 1")
    suspend fun activoId(): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun crear(perfil: PerfilEntity)

    @Query("UPDATE perfiles SET nombre = :nombre WHERE id = :id")
    suspend fun renombrar(id: String, nombre: String)

    @Query("DELETE FROM playlists WHERE perfilId = :id")
    suspend fun borrarPlaylistsDe(id: String)

    @Query("DELETE FROM perfiles WHERE id = :id AND activo = 0 AND id != '$PERFIL_PRINCIPAL'")
    suspend fun borrarFila(id: String): Int

    /** Borra un perfil que no es el principal ni el activo, con sus playlists. */
    @Transaction
    suspend fun borrar(id: String): Boolean {
        if (id == PERFIL_PRINCIPAL || id == activoId()) return false
        borrarPlaylistsDe(id)
        return borrarFila(id) > 0
    }

    // ── Estado de los perfiles no activos ───────────────────────────────────
    @Query("SELECT * FROM perfil_canciones WHERE perfilId = :id")
    suspend fun estadoDe(id: String): List<PerfilCancion>

    @Upsert
    suspend fun guardarEstado(filas: List<PerfilCancion>)

    @Query("DELETE FROM perfil_canciones WHERE perfilId = :id")
    suspend fun vaciarEstado(id: String)

    @Query("""
        INSERT INTO perfil_canciones (perfilId, songId, isLiked, playCount, lastPlayed, noRecomendar)
        SELECT :id, id, isLiked, playCount, lastPlayed, noRecomendar FROM songs
        WHERE isLiked = 1 OR playCount > 0 OR lastPlayed > 0 OR noRecomendar = 1
    """)
    suspend fun guardarEstadoDelActivo(id: String)

    @Query("""
        UPDATE songs SET isLiked = 0, playCount = 0, lastPlayed = 0, noRecomendar = 0
        WHERE isLiked = 1 OR playCount > 0 OR lastPlayed > 0 OR noRecomendar = 1
    """)
    suspend fun limpiarEstadoDelActivo()

    @Query("""
        UPDATE songs SET
            isLiked      = (SELECT pc.isLiked      FROM perfil_canciones pc WHERE pc.perfilId = :id AND pc.songId = songs.id),
            playCount    = (SELECT pc.playCount    FROM perfil_canciones pc WHERE pc.perfilId = :id AND pc.songId = songs.id),
            lastPlayed   = (SELECT pc.lastPlayed   FROM perfil_canciones pc WHERE pc.perfilId = :id AND pc.songId = songs.id),
            noRecomendar = (SELECT pc.noRecomendar FROM perfil_canciones pc WHERE pc.perfilId = :id AND pc.songId = songs.id)
        WHERE id IN (SELECT songId FROM perfil_canciones WHERE perfilId = :id)
    """)
    suspend fun cargarEstado(id: String)

    @Query("UPDATE perfiles SET activo = (id = :id)")
    suspend fun marcarActivo(id: String)

    @Query("SELECT COUNT(*) FROM perfiles WHERE id = :id")
    suspend fun existe(id: String): Int

    /**
     * Cambia de perfil en UNA transacción: guarda el estado del activo, deja `songs`
     * a cero, carga el del nuevo y lo marca activo. Si la app muere a mitad, no
     * cambia nada.
     */
    @Transaction
    suspend fun cambiarA(id: String): Boolean {
        val actual = activoId() ?: return false
        if (actual == id || existe(id) == 0) return false
        vaciarEstado(actual)
        guardarEstadoDelActivo(actual)
        limpiarEstadoDelActivo()
        cargarEstado(id)
        vaciarEstado(id)
        marcarActivo(id)
        return true
    }
}

@Database(
    entities = [SongEntity::class, ArtistEntity::class, PlaylistEntity::class, PlaylistSongCrossRef::class,
        PerfilEntity::class, PerfilCancion::class],
    version = 4,
    exportSchema = true   // esquemas en app/schemas/ para escribir y probar migraciones
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun perfilDao(): PerfilDao
}

// ─── Migraciones ─────────────────────────────────────────────────────────────

/**
 * v1 → v2 (Fase 1): añade las columnas del modelo unificado sin borrar datos.
 * Toda fila previa queda como origen YOUTUBE y fuente vacía (se rellenará al
 * reproducirla). Conserva likes, descargas, conteos y playlists del usuario.
 */
val MIGRACION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN origen TEXT NOT NULL DEFAULT 'YOUTUBE'")
        db.execSQL("ALTER TABLE songs ADD COLUMN fuente TEXT NOT NULL DEFAULT ''")
    }
}

/** v2 → v3 (Q): añade la columna explicit sin borrar datos. */
val MIGRACION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN explicit INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Siempre tiene que haber un perfil activo: sin él las playlists no se ven (sus
 * consultas filtran por el activo). La migración 3 → 4 crea "principal" al
 * actualizar, pero una instalación nueva nace ya en la versión 4 y no la ejecuta:
 * por eso esto corre en cada apertura (AppModule, onOpen). No toca nada si ya hay
 * un perfil activo.
 */
fun asegurarPerfilActivo(db: SupportSQLiteDatabase) {
    db.execSQL("INSERT OR IGNORE INTO perfiles (id, nombre, creado, activo) VALUES " +
        "('$PERFIL_PRINCIPAL', 'Principal', ${System.currentTimeMillis()}, 0)")
    db.execSQL("UPDATE perfiles SET activo = 1 WHERE id = '$PERFIL_PRINCIPAL' " +
        "AND NOT EXISTS (SELECT 1 FROM perfiles WHERE activo = 1)")
}

/**
 * v3 → v4: perfiles de gusto y "No recomendar". No mueve ningún dato: lo que ya hay
 * en `songs` pasa a ser del perfil "principal", que queda activo, y todas las
 * playlists existentes son suyas.
 */
val MIGRACION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN noRecomendar INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS `perfiles` (`id` TEXT NOT NULL, `nombre` TEXT NOT NULL, " +
            "`creado` INTEGER NOT NULL, `activo` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("INSERT OR IGNORE INTO perfiles (id, nombre, creado, activo) VALUES " +
            "('$PERFIL_PRINCIPAL', 'Principal', ${System.currentTimeMillis()}, 1)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `perfil_canciones` (`perfilId` TEXT NOT NULL, `songId` TEXT NOT NULL, " +
            "`isLiked` INTEGER NOT NULL, `playCount` INTEGER NOT NULL, `lastPlayed` INTEGER NOT NULL, " +
            "`noRecomendar` INTEGER NOT NULL, PRIMARY KEY(`perfilId`, `songId`), " +
            "FOREIGN KEY(`perfilId`) REFERENCES `perfiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`songId`) REFERENCES `songs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_perfil_canciones_songId` ON `perfil_canciones` (`songId`)")
        db.execSQL("ALTER TABLE playlists ADD COLUMN perfilId TEXT NOT NULL DEFAULT '$PERFIL_PRINCIPAL'")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_perfilId` ON `playlists` (`perfilId`)")
    }
}
