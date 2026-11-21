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

    // Caché de portadas en disco: tope fijo de 50 MB en todos los teléfonos (~500
    // portadas a 544 px, sin bajar su calidad). Antes eran 250 MB que Ajustes no
    // mostraba. Al llenarse, se van las portadas que hace más tiempo que no se ven;
    // una caché mayor de antes se recorta al abrir.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve(CARPETA_PORTADAS))
                .maxSizeBytes(com.auramusic.app.domain.model.CACHE_PORTADAS_MB * 1024 * 1024)
                .build()
        }
        // Las portadas que llegan de la red o del disco se funden en 200 ms en vez de
        // aparecer de golpe. Las que ya están en memoria (volver a una lista, la portada
        // que vuela al reproductor) salen al instante: Coil no funde las de memoria.
        .crossfade(200)
        .build()

    companion object {
        const val CARPETA_PORTADAS = "image_cache"
    }
}
