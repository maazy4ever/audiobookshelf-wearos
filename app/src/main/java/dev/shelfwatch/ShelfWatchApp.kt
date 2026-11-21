package dev.shelfwatch

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory

class ShelfWatchApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        // Ausstehende Fortschritte nach App-Start nachsynchronisieren
        Graph.sync.schedule()
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Graph.http }
            .crossfade(true)
            .build()
}
