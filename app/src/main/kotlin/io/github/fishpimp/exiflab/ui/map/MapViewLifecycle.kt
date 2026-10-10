package io.github.fishpimp.exiflab.ui.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapView

/**
 * Forwards the screen's lifecycle and memory pressure to [mapView], and destroys it when it
 * leaves the composition. The map is created in onCreate state; this walks it through every
 * intermediate step, so MapLibre always sees start before resume and stop before destroy.
 */
@Composable
internal fun MapViewLifecycle(mapView: MapView) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val stepper = remember(mapView) { MapLifecycleStepper(MapViewCallbacks(mapView)) }

    DisposableEffect(mapView) {
        val observer = LifecycleEventObserver { _, event ->
            // Destruction is handled on dispose, which also covers leaving the screen.
            if (event != Lifecycle.Event.ON_DESTROY) stepper.moveTo(event.targetState)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stepper.moveTo(Lifecycle.State.DESTROYED)
        }
    }

    DisposableEffect(context, mapView) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) mapView.onLowMemory()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Superseded by onTrimMemory; still called on older releases.")
            override fun onLowMemory() = mapView.onLowMemory()
        }
        context.registerComponentCallbacks(callbacks)
        onDispose { context.unregisterComponentCallbacks(callbacks) }
    }
}

/** The lifecycle calls a map view needs, in the order MapLibre expects them. */
internal interface MapLifecycleCallbacks {
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
}

private class MapViewCallbacks(private val mapView: MapView) : MapLifecycleCallbacks {
    override fun onStart() = mapView.onStart()
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onStop() = mapView.onStop()
    override fun onDestroy() = mapView.onDestroy()
}

/**
 * Moves a map view between lifecycle states one step at a time, so it never sees resume without
 * start or destroy without stop. Starts out created; once destroyed it stays destroyed.
 */
internal class MapLifecycleStepper(private val mapView: MapLifecycleCallbacks) {
    var state: Lifecycle.State = Lifecycle.State.CREATED
        private set

    fun moveTo(target: Lifecycle.State) {
        val goal = if (target == Lifecycle.State.INITIALIZED) Lifecycle.State.CREATED else target
        while (state != goal && state != Lifecycle.State.DESTROYED) {
            val next = if (state < goal) stepUp() else stepDown()
            if (next == state) break
            state = next
        }
    }

    private fun stepUp(): Lifecycle.State = when (state) {
        Lifecycle.State.CREATED -> Lifecycle.State.STARTED.also { mapView.onStart() }
        Lifecycle.State.STARTED -> Lifecycle.State.RESUMED.also { mapView.onResume() }
        else -> state
    }

    private fun stepDown(): Lifecycle.State = when (state) {
        Lifecycle.State.RESUMED -> Lifecycle.State.STARTED.also { mapView.onPause() }
        Lifecycle.State.STARTED -> Lifecycle.State.CREATED.also { mapView.onStop() }
        Lifecycle.State.CREATED -> Lifecycle.State.DESTROYED.also { mapView.onDestroy() }
        else -> state
    }
}
