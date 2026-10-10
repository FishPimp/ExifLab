package io.github.fishpimp.exiflab.ui.privacy

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.component.GroupSurface
import io.github.fishpimp.exiflab.designsystem.component.ListRow
import io.github.fishpimp.exiflab.designsystem.component.SectionHeader
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold

/**
 * A third-party component or data source. [licenseAsset] points to a full text in assets; [url]
 * to the source's own license or copyright page, opened in the browser. [nameRes] replaces
 * [name] where the name itself is translated.
 */
data class OssComponent(
    val name: String,
    val license: String,
    val licenseAsset: String? = null,
    val url: String? = null,
    @param:StringRes val nameRes: Int? = null,
)

/** Keep in sync with the dependencies in the build files, transitive ones included. */
val ossComponents = listOf(
    OssComponent("AndroidX and Jetpack Compose", "Apache License 2.0"),
    OssComponent("Kotlin and kotlinx libraries", "Apache License 2.0"),
    OssComponent("MaterialKolor", "MIT License"),
    OssComponent("metadata-extractor", "Apache License 2.0"),
    OssComponent("Adobe XMP Core", "BSD 3-Clause License"),
    OssComponent("Coil", "Apache License 2.0"),
    OssComponent("MapLibre Native", "BSD 2-Clause License", url = "https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md"),
    OssComponent("MapLibre Java (GeoJSON and Turf)", "Apache License 2.0"),
    OssComponent("MapLibre Android Gestures", "BSD 2-Clause License"),
    OssComponent("OkHttp", "Apache License 2.0"),
    OssComponent("Okio", "Apache License 2.0"),
    OssComponent("Gson", "Apache License 2.0"),
    OssComponent("Timber", "Apache License 2.0"),
    OssComponent("Bricolage Grotesque", "SIL Open Font License 1.1", "licenses/OFL-BricolageGrotesque.txt"),
    OssComponent("JetBrains Mono", "SIL Open Font License 1.1", "licenses/OFL-JetBrainsMono.txt"),
)

/** Where the map's data, tiles and styles come from. Every map also shows a short credit line. */
val mapAttributions = listOf(
    OssComponent(
        name = "OpenStreetMap contributors",
        nameRes = R.string.map_credit_openstreetmap,
        license = "Open Database License (ODbL) 1.0",
        url = "https://www.openstreetmap.org/copyright",
    ),
    OssComponent("OpenMapTiles", "BSD 3-Clause License (code), CC BY 4.0 (design)", url = "https://www.openmaptiles.org/"),
    OssComponent("OpenFreeMap", "MIT License (styles), tiles hosted by OpenFreeMap", url = "https://openfreemap.org/"),
    OssComponent(
        "Positron and Dark Matter map styles",
        "BSD 3-Clause License (code), CC BY 4.0 (design)",
        url = "https://github.com/hyperknot/openfreemap-styles/blob/main/LICENSE.md",
    ),
)

@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var openAsset by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenScaffold(title = stringResource(R.string.licenses_title), onBack = onBack, modifier = modifier) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SectionHeader(stringResource(R.string.licenses_section_libraries)) }
                items(ossComponents) { component ->
                    ComponentRow(component, onOpenAsset = { openAsset = it })
                }
                item { SectionHeader(stringResource(R.string.licenses_section_map), Modifier.padding(top = 8.dp)) }
                items(mapAttributions) { component ->
                    ComponentRow(component, onOpenAsset = { openAsset = it })
                }
            }
        }
    }
    openAsset?.let { asset -> LicenseTextDialog(asset = asset, onDismiss = { openAsset = null }) }
}

@Composable
private fun ComponentRow(component: OssComponent, onOpenAsset: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val onClick: (() -> Unit)? = when {
        component.licenseAsset != null -> { { onOpenAsset(component.licenseAsset) } }
        // The browser opens the page; ExifLab itself makes no request.
        component.url != null -> { { runCatching { uriHandler.openUri(component.url) } } }
        else -> null
    }
    GroupSurface {
        ListRow(
            title = component.nameRes?.let { stringResource(it) } ?: component.name,
            supporting = component.license,
            onClick = onClick,
            trailing = if (component.licenseAsset == null && component.url != null) {
                { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) }
            } else {
                null
            },
        )
    }
}

@Composable
private fun LicenseTextDialog(asset: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(asset) {
        runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrDefault("")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        text = {
            Text(
                text,
                style = ExifLabTheme.extendedTypography.monoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            )
        },
    )
}
