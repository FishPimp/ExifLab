package io.github.fishpimp.exiflab

import android.app.Application

class ExifLabApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
