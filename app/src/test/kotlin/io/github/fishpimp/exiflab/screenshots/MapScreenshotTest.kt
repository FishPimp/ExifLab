package io.github.fishpimp.exiflab.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import io.github.fishpimp.exiflab.ui.map.LatLng
import io.github.fishpimp.exiflab.ui.map.LocalMapAvailabilityOverride
import io.github.fishpimp.exiflab.ui.map.LocationMap
import io.github.fishpimp.exiflab.ui.map.MapAvailability
import io.github.fishpimp.exiflab.ui.map.MapViewContent
import io.github.fishpimp.exiflab.ui.map.pinBitmap
import io.github.fishpimp.exiflab.ui.map.rememberMapPalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The map as Robolectric can show it: the drawn stand-in, in each state it can be in. MapLibre
 * itself never starts in tests (no Vulkan, no network).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val stockholm = LatLng(59.32930, 18.06860)

    @Test @Config(qualifiers = "w411dp-h1200dp-xxhdpi")
    fun map_states_light() = compose.snapshot("map_states_light") { MapStates() }

    @Test @Config(qualifiers = "w411dp-h1200dp-night-xxhdpi")
    fun map_states_dark() = compose.snapshot("map_states_dark", ThemeMode.Dark, BrandPalette.Iris) { MapStates() }

    @Test @Config(qualifiers = "sv-w411dp-h1200dp-xxhdpi")
    fun map_states_swedish() = compose.snapshot("map_states_sv", palette = BrandPalette.Citrus) { MapStates() }

    @Test @Config(qualifiers = "w411dp-h1600dp-xxhdpi", fontScale = 2.0f)
    fun map_states_large_font() = compose.snapshot("map_states_font200") { MapStates() }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun map_view_light() = compose.snapshot("map_view_light", palette = BrandPalette.Moss) { FullMap() }

    @Test @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun map_view_offline_dark() = compose.snapshot("map_view_offline_dark", ThemeMode.Dark) {
        FullMap(MapAvailability.OfflineMode)
    }

    @Test @Config(qualifiers = "sv-w411dp-h891dp-xxhdpi", fontScale = 2.0f)
    fun map_view_swedish_large_font() = compose.snapshot("map_view_sv_font200", palette = BrandPalette.Ember) {
        FullMap(MapAvailability.OfflineMode)
    }

    @Test @Config(qualifiers = "w411dp-h400dp-xxhdpi")
    fun pin_bitmap() = compose.snapshot("map_pin_bitmap") { PinBitmaps() }

    /** The embedded map as the photo viewer shows it, in every state it can be in. */
    @Composable
    private fun MapStates() = Previewing {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf(
                "Preview" to MapAvailability.Preview,
                "Offline mode" to MapAvailability.OfflineMode,
                "No connection" to MapAvailability.NoConnection,
                "Unsupported device" to MapAvailability.Unsupported,
            ).forEach { (label, availability) ->
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CompositionLocalProvider(LocalMapAvailabilityOverride provides availability) {
                    LocationMap(
                        marker = stockholm,
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun FullMap(availability: MapAvailability = MapAvailability.Preview) = Previewing {
        CompositionLocalProvider(LocalMapAvailabilityOverride provides availability) {
            MapViewContent(
                title = "IMG_2041.HEIC",
                location = stockholm,
                onBack = {},
                onCopyCoordinates = {},
                onOpenInOtherApp = {},
            )
        }
    }

    /** The bitmap MapLibre draws as the marker, next to the same pin on the stand-in. */
    @Composable
    private fun PinBitmaps() = Previewing {
        val palette = rememberMapPalette()
        val density = LocalDensity.current
        val bitmap = remember(palette, density) { pinBitmap(density, palette).asImageBitmap() }
        Row(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.background(palette.land).padding(8.dp))
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.background(palette.water).padding(8.dp))
            LocationMap(marker = stockholm, modifier = Modifier.fillMaxWidth().height(160.dp))
        }
    }

    @Composable
    private fun Previewing(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalInspectionMode provides true, content = content)
}
