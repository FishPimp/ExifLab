package io.github.fishpimp.exiflab.ui.map

import java.util.Locale

/** A point on the map, in WGS84 degrees. */
data class LatLng(val latitude: Double, val longitude: Double) {
    /** True for a real position: finite, latitude within ±90 and longitude within ±180. */
    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

/**
 * Decimal degrees as "59.32930, 18.06860". Always uses a dot as the decimal separator, whatever
 * the app language, so the text pastes into any map app or search field.
 */
fun LatLng.formatted(decimals: Int = DISPLAY_DECIMALS): String =
    String.format(Locale.ROOT, "%.${decimals}f, %.${decimals}f", latitude, longitude)

/** Five decimals is about one meter, as precise as photo GPS gets. */
const val DISPLAY_DECIMALS = 5

/** Six decimals (about ten centimeters) for copying, so nothing is lost when pasted elsewhere. */
const val COPY_DECIMALS = 6
