package io.github.fishpimp.exiflab

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

/**
 * Owns the [AppGraph], starts its background maintenance (crash recovery of interrupted saves,
 * backup pruning) and hands its image loader to Coil, so `AsyncImage` uses it everywhere.
 */
class ExifLabApplication : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.startMaintenance()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = graph.imageLoader
}
