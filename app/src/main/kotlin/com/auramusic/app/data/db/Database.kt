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
    val explicit: Boolean = false
)

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val thumbnailUrl: String
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
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

// ─── DAOs ──────────────────────────────────────────────────────────────────

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY lastPlayed DESC")
    fun getAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getById(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE isLiked = 1 ORDER BY title")
    fun getLiked(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isDownloaded = 1 ORDER BY title")
    fun getDownloaded(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY lastPlayed DESC LIMIT 20")
    fun getRecentlyPlayed(): Flow<List<SongEntity>>

    // Recientes SOLO de streaming (Mejora 12): el Inicio no debe mezclar locales,
    // que tienen su propio flujo en la pestaña "Local".
    @Query("SELECT * FROM songs WHERE origen != 'LOCAL' ORDER BY lastPlayed DESC LIMIT 20")
    fun getRecentlyPlayedStreaming(): Flow<List<SongEntity>>

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

    @Query("SELECT * FROM songs ORDER BY playCount DESC LIMIT 10")
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
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: String): PlaylistEntity?

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

    // ── Backup ──────────────────────────────────────────────────────────────
    @Query("SELECT * FROM playlists")
    suspend fun todas(): List<PlaylistEntity>

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

@Database(
    entities = [SongEntity::class, ArtistEntity::class, PlaylistEntity::class, PlaylistSongCrossRef::class],
    version = 3,
    exportSchema = true   // esquemas en app/schemas/ para escribir y probar migraciones
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
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
