package io.github.fishpimp.exiflab.ui.privacy

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.component.GroupSurface
import io.github.fishpimp.exiflab.designsystem.component.ListRow
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold

/** A third-party component bundled in the app. [licenseAsset] points to a full text in assets. */
data class OssComponent(val name: String, val license: String, val licenseAsset: String? = null)

/** Keep in sync with the dependencies in the build files. */
val ossComponents = listOf(
    OssComponent("AndroidX and Jetpack Compose", "Apache License 2.0"),
    OssComponent("Kotlin and kotlinx libraries", "Apache License 2.0"),
    OssComponent("MaterialKolor", "MIT License"),
    OssComponent("Coil", "Apache License 2.0"),
    OssComponent("Bricolage Grotesque", "SIL Open Font License 1.1", "licenses/OFL-BricolageGrotesque.txt"),
    OssComponent("JetBrains Mono", "SIL Open Font License 1.1", "licenses/OFL-JetBrainsMono.txt"),
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
                items(ossComponents) { component ->
                    GroupSurface {
                        ListRow(
                            title = component.name,
                            supporting = component.license,
                            onClick = component.licenseAsset?.let { asset -> { openAsset = asset } },
                        )
                    }
                }
            }
        }
    }
    openAsset?.let { asset -> LicenseTextDialog(asset = asset, onDismiss = { openAsset = null }) }
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
