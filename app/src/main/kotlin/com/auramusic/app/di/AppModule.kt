package com.auramusic.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.auramusic.app.data.api.InnertubeClient
import com.auramusic.app.data.db.asegurarPerfilActivo
import com.auramusic.app.data.db.AuraDatabase
import com.auramusic.app.data.db.MIGRACION_1_2
import com.auramusic.app.data.db.MIGRACION_2_3
import com.auramusic.app.data.db.MIGRACION_3_4
import com.auramusic.app.data.db.PerfilDao
import com.auramusic.app.data.db.PlaylistDao
import com.auramusic.app.data.db.SongDao
import com.auramusic.app.data.repository.RecommendationRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AuraDatabase =
        Room.databaseBuilder(ctx, AuraDatabase::class.java, "aura_music.db")
            // Sin fallbackToDestructiveMigration(): si falta una migración, mejor que la
            // app falle de forma visible a que borre en silencio likes y playlists,
            // que ahora solo existen en el teléfono (no hay sincronización con cuenta).
            .addMigrations(MIGRACION_1_2, MIGRACION_2_3, MIGRACION_3_4)
            // Instalación nueva: crea el perfil principal (ver asegurarPerfilActivo).
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) = asegurarPerfilActivo(db)
            })
            .build()

    @Provides @Singleton
    fun provideSongDao(db: AuraDatabase): SongDao = db.songDao()

    @Provides @Singleton
    fun providePlaylistDao(db: AuraDatabase): PlaylistDao = db.playlistDao()

    @Provides @Singleton
    fun providePerfilDao(db: AuraDatabase): PerfilDao = db.perfilDao()

    @Provides @Singleton
    fun provideInnertubeClient(): InnertubeClient = InnertubeClient()

    @Provides @Singleton
    fun provideRecommendationRepository(
        @ApplicationContext ctx: Context,
        songDao: SongDao,
        perfilDao: PerfilDao,
        innertube: InnertubeClient
    ): RecommendationRepository = RecommendationRepository(ctx, songDao, perfilDao, innertube)
}
