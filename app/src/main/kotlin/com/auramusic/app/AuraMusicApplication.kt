package com.auramusic.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.auramusic.app.data.api.NewPipeDownloader
import dagger.hilt.android.HiltAndroidApp
import org.schabi.newpipe.extractor.NewPipe
import javax.inject.Inject

@HiltAndroidApp
class AuraMusicApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    // Fábrica de workers de Hilt: permite inyectar dependencias en DescargaWorker.
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        NewPipe.init(NewPipeDownloader.instance)
    }

    // Inicialización on-demand de WorkManager con la fábrica de Hilt (Bug 4).
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    // Caché de portadas en disco: carpeta, 2 % del almacenamiento y tope de 250 MB,
    // como Coil por defecto, pero fijados aquí para que el tope (elegido por el
    // usuario) no dependa de la versión de Coil. Al llenarse, se van las portadas
    // que hace más tiempo que no se ven.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizePercent(0.02)
                .maximumMaxSizeBytes(250L * 1024 * 1024)
                .build()
        }
        .build()
}
