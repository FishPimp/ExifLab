package io.github.fishpimp.exiflab.ui.map

import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * OpenFreeMap's own Positron (light) and Dark styles, bundled so no style request is needed.
 * Their tiles, sprites and glyphs still come from tiles.openfreemap.org.
 *
 * Source: styles/positron and styles/dark in github.com/hyperknot/openfreemap-styles (MIT; designs
 * CC BY 4.0 from OpenMapTiles), with the `__TILEJSON_DOMAIN__` placeholder replaced by
 * tiles.openfreemap.org, as OpenFreeMap serves them, and the unused shaded-relief source removed.
 */
internal object MapStyles {
    private const val LIGHT = "asset://map/openfreemap-positron.json"
    private const val DARK = "asset://map/openfreemap-dark.json"

    fun uri(dark: Boolean): String = if (dark) DARK else LIGHT
}

/** Layer ids in the bundled styles that carry land, water and park colors. */
private object StyleLayers {
    const val BACKGROUND = "background"
    const val WATER = "water"
    const val WATERWAY = "waterway"
    val parks = listOf("park", "landuse_park")
}

/**
 * Tints the style's land, water and parks towards the app's colors. Only these few layers are
 * touched, and only when they exist with the expected type, so a style update cannot break it.
 */
internal fun Style.applyPalette(palette: MapPalette) {
    (getLayer(StyleLayers.BACKGROUND) as? BackgroundLayer)
        ?.setProperties(PropertyFactory.backgroundColor(palette.land.toArgb()))
    (getLayer(StyleLayers.WATER) as? FillLayer)
        ?.setProperties(PropertyFactory.fillColor(palette.water.toArgb()))
    (getLayer(StyleLayers.WATERWAY) as? LineLayer)
        ?.setProperties(PropertyFactory.lineColor(palette.water.toArgb()))
    StyleLayers.parks.forEach { id ->
        (getLayer(id) as? FillLayer)?.setProperties(PropertyFactory.fillColor(palette.park.toArgb()))
    }
}

/** The location marker: a ring on the ground and the pin above it, on top of every other layer. */
internal object MarkerLayers {
    private const val SOURCE = "exiflab-marker"
    private const val RING = "exiflab-marker-ring"
    private const val PIN = "exiflab-marker-pin"
    private const val PIN_IMAGE = "exiflab-marker-pin-image"

    /** Adds the marker to [style], or updates its colors when it is already there. */
    fun install(style: Style, palette: MapPalette, pin: Bitmap, position: LatLng?) {
        style.addImage(PIN_IMAGE, pin)
        if (style.getSource(SOURCE) == null) style.addSource(GeoJsonSource(SOURCE))
        val ring = style.getLayer(RING) ?: CircleLayer(RING, SOURCE).also(style::addLayer)
        ring.setProperties(
            PropertyFactory.circleRadius(MapPin.RingRadius.value),
            PropertyFactory.circleColor(palette.pin.toArgb()),
            PropertyFactory.circleOpacity(MapPin.RING_FILL_ALPHA),
            PropertyFactory.circleStrokeWidth(MapPin.RingStroke.value),
            PropertyFactory.circleStrokeColor(palette.pin.toArgb()),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        )
        val symbol = style.getLayer(PIN) ?: SymbolLayer(PIN, SOURCE).also(style::addLayer)
        symbol.setProperties(
            PropertyFactory.iconImage(PIN_IMAGE),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
        setPosition(style, position)
    }

    fun setPosition(style: Style, position: LatLng?) {
        val source = style.getSource(SOURCE) as? GeoJsonSource ?: return
        if (position == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(position.longitude, position.latitude)))
        }
    }
}
