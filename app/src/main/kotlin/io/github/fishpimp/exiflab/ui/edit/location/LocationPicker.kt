package io.github.fishpimp.exiflab.ui.edit.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material.icons.rounded.Signpost
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.places.Place
import io.github.fishpimp.exiflab.data.places.PlaceKind
import io.github.fishpimp.exiflab.data.places.PlaceSearchError
import io.github.fishpimp.exiflab.data.places.PlaceSearchState
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.model.GeoLocation
import io.github.fishpimp.exiflab.ui.map.LatLng
import io.github.fishpimp.exiflab.ui.map.LocationMap
import io.github.fishpimp.exiflab.ui.map.formatted
import java.util.Locale

/** What the picker holds: the pin and the text of the coordinate and altitude fields. */
data class LocationDraft(val pin: LatLng?, val coordinates: String, val altitude: String)

/**
 * Full-screen location picker: a search bar with place results, an interactive map where a tap or
 * long press drops the pin (TalkBack: "Place pin at map center"), and coordinate entry that takes
 * decimal degrees, degrees-minutes-seconds and map links, plus an optional altitude.
 */
@Composable
fun LocationPicker(
    initial: GeoLocation?,
    query: String,
    search: PlaceSearchState,
    onQueryChange: (String) -> Unit,
    onConfirm: (latitude: Double, longitude: Double, altitudeMeters: Double?) -> Unit,
    onClose: () -> Unit,
) {
    val start = initial?.let { LatLng(it.latitude, it.longitude) }
    var latitude by rememberSaveable { mutableStateOf(start?.latitude) }
    var longitude by rememberSaveable { mutableStateOf(start?.longitude) }
    var coordinates by rememberSaveable { mutableStateOf(start?.formatted(COORDINATE_DECIMALS).orEmpty()) }
    var altitude by rememberSaveable { mutableStateOf(initial?.altitudeMeters?.let { formatAltitude(it) }.orEmpty()) }
    val pin = if (latitude != null && longitude != null) LatLng(latitude!!, longitude!!) else null

    fun movePin(to: LatLng, text: String = to.formatted(COORDINATE_DECIMALS)) {
        latitude = to.latitude
        longitude = to.longitude
        coordinates = text
    }

    LocationPickerContent(
        draft = LocationDraft(pin, coordinates, altitude),
        query = query,
        search = search,
        onQueryChange = onQueryChange,
        onPlacePicked = { place ->
            movePin(place.latLng)
            onQueryChange("")
        },
        onMapPick = { movePin(it) },
        onCoordinatesChange = { text ->
            coordinates = text
            val parsed = CoordinateParser.parseOrNull(text)
            if (parsed != null) {
                latitude = parsed.latLng.latitude
                longitude = parsed.latLng.longitude
                parsed.altitudeMeters?.let { altitude = formatAltitude(it) }
            }
        },
        onAltitudeChange = { altitude = it },
        onConfirm = {
            val position = pin ?: return@LocationPickerContent
            onConfirm(position.latitude, position.longitude, parseAltitude(altitude))
        },
        onClose = onClose,
    )
}

/** The picker's page; stateless, so screenshot tests can show any state. */
@Composable
fun LocationPickerContent(
    draft: LocationDraft,
    query: String,
    search: PlaceSearchState,
    onQueryChange: (String) -> Unit,
    onPlacePicked: (Place) -> Unit,
    onMapPick: (LatLng) -> Unit,
    onCoordinatesChange: (String) -> Unit,
    onAltitudeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val parse = remember(draft.coordinates) { if (draft.coordinates.isBlank()) null else CoordinateParser.parse(draft.coordinates) }
    val coordinateError = (parse as? CoordinateParse.Failure)?.error
    val altitudeInvalid = draft.altitude.isNotBlank() && parseAltitude(draft.altitude) == null
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit_location_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= 840.dp
            val searchPanel: @Composable (Modifier) -> Unit = { m ->
                SearchPanel(query, search, onQueryChange, onPlacePicked, m)
            }
            val entryPanel: @Composable (Modifier) -> Unit = { m ->
                EntryPanel(draft, coordinateError, altitudeInvalid, onCoordinatesChange, onAltitudeChange, onConfirm, m)
            }
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(420.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        searchPanel(Modifier)
                        entryPanel(Modifier)
                    }
                    PickerMap(draft.pin, onMapPick, Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        PickerMap(draft.pin, onMapPick, Modifier.fillMaxSize())
                        searchPanel(Modifier.padding(12.dp))
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        entryPanel(Modifier.navigationBarsPadding().padding(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerMap(pin: LatLng?, onPick: (LatLng) -> Unit, modifier: Modifier) {
    LocationMap(marker = pin, interactive = true, onPick = onPick, shape = RectangleShape, modifier = modifier)
}

/** The search field with its results (or why there are none) in a card under it. */
@Composable
private fun SearchPanel(
    query: String,
    search: PlaceSearchState,
    onQueryChange: (String) -> Unit,
    onPlacePicked: (Place) -> Unit,
    modifier: Modifier,
) {
    val focus = LocalFocusManager.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.edit_location_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.edit_location_search_clear))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val showPanel = query.trim().length >= 2 && search !is PlaceSearchState.Idle
        if (showPanel) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (search) {
                    is PlaceSearchState.Searching -> Row(
                        Modifier.padding(16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LoadingIndicator(Modifier.size(32.dp))
                        Text(stringResource(R.string.edit_location_searching), style = MaterialTheme.typography.bodyMedium)
                    }
                    is PlaceSearchState.Failed -> SearchMessage(
                        icon = if (search.error == PlaceSearchError.Offline) Icons.Rounded.CloudOff else if (search.error == PlaceSearchError.Network) Icons.Rounded.SignalWifiOff else Icons.Rounded.ErrorOutline,
                        text = stringResource(
                            when (search.error) {
                                PlaceSearchError.Offline -> R.string.edit_location_search_offline
                                PlaceSearchError.Network -> R.string.edit_location_search_network
                                PlaceSearchError.Server -> R.string.edit_location_search_server
                            },
                        ),
                    )
                    is PlaceSearchState.Results -> if (search.places.isEmpty()) {
                        SearchMessage(Icons.Rounded.Search, stringResource(R.string.edit_location_search_none, search.query))
                    } else {
                        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            search.places.forEachIndexed { index, place ->
                                if (index > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                PlaceRow(place) { onPlacePicked(place); focus.clearFocus() }
                            }
                        }
                    }
                    PlaceSearchState.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun SearchMessage(icon: ImageVector, text: String) {
    Row(
        Modifier.padding(16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PlaceRow(place: Place, onClick: () -> Unit) {
    val context = listOfNotNull(place.locality, place.region, place.country).distinct().joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(place.kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.bodyLarge)
            if (context.isNotEmpty()) {
                Text(context, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val PlaceKind.icon: ImageVector
    get() = when (this) {
        PlaceKind.Address -> Icons.Rounded.Home
        PlaceKind.PointOfInterest -> Icons.Rounded.Star
        PlaceKind.Street -> Icons.Rounded.Signpost
        PlaceKind.District -> Icons.Rounded.Apartment
        PlaceKind.City -> Icons.Rounded.LocationCity
        PlaceKind.Region -> Icons.Rounded.Map
        PlaceKind.Country -> Icons.Rounded.Flag
        PlaceKind.Other -> Icons.Rounded.Place
    }

/** Coordinate and altitude entry, the chosen point, and "Use this location". */
@Composable
private fun EntryPanel(
    draft: LocationDraft,
    coordinateError: CoordinateError?,
    altitudeInvalid: Boolean,
    onCoordinatesChange: (String) -> Unit,
    onAltitudeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = draft.coordinates,
            onValueChange = onCoordinatesChange,
            label = { Text(stringResource(R.string.edit_location_coordinates_label)) },
            leadingIcon = { Icon(Icons.Rounded.Place, contentDescription = null) },
            isError = coordinateError != null,
            supportingText = {
                val text = coordinateError?.let { stringResource(it.messageRes) } ?: stringResource(R.string.edit_location_coordinates_hint)
                Text(text, modifier = if (coordinateError != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            textStyle = ExifLabTheme.extendedTypography.monoLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.altitude,
            onValueChange = onAltitudeChange,
            label = { Text(stringResource(R.string.edit_location_altitude_label)) },
            leadingIcon = { Icon(Icons.Rounded.Terrain, contentDescription = null) },
            suffix = { Text(stringResource(R.string.edit_location_altitude_unit)) },
            isError = altitudeInvalid,
            supportingText = {
                Text(
                    stringResource(if (altitudeInvalid) R.string.edit_location_altitude_invalid else R.string.edit_location_altitude_hint),
                    modifier = if (altitudeInvalid) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onConfirm,
            enabled = draft.pin != null && !altitudeInvalid,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null)
            Text(
                draft.pin?.let { stringResource(R.string.edit_location_use_point, it.formatted()) } ?: stringResource(R.string.edit_location_tap_map),
                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
            )
        }
    }
}

private val CoordinateError.messageRes: Int
    get() = when (this) {
        CoordinateError.Empty -> R.string.edit_location_coordinates_hint
        CoordinateError.Unrecognized -> R.string.edit_location_error_unrecognized
        CoordinateError.OutOfRange -> R.string.edit_location_error_range
        CoordinateError.ShortLink -> R.string.edit_location_error_short_link
    }

/** Altitude in meters from the text field; a comma works as the decimal separator. */
fun parseAltitude(text: String): Double? =
    text.trim().replace(',', '.').replace('−', '-').takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -1_000.0..100_000.0 }

private fun formatAltitude(meters: Double): String = String.format(Locale.ROOT, "%.1f", meters).removeSuffix(".0")

private const val COORDINATE_DECIMALS = 6
