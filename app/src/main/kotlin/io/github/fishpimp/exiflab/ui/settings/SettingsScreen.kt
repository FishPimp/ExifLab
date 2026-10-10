package io.github.fishpimp.exiflab.ui.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.materialkolor.dynamicColorScheme
import io.github.fishpimp.exiflab.BuildConfig
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.settings.AppLanguage
import io.github.fishpimp.exiflab.data.settings.AppSettings
import io.github.fishpimp.exiflab.data.settings.BackupRetention
import io.github.fishpimp.exiflab.data.settings.SidecarNaming
import io.github.fishpimp.exiflab.designsystem.component.Choice
import io.github.fishpimp.exiflab.designsystem.component.ChoiceRow
import io.github.fishpimp.exiflab.designsystem.component.GroupSurface
import io.github.fishpimp.exiflab.designsystem.component.ListRow
import io.github.fishpimp.exiflab.designsystem.component.SectionHeader
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ContrastPreference
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import io.github.fishpimp.exiflab.ui.components.ScreenScaffold

@Composable
fun SettingsScreen(
    onOpenPrivacy: () -> Unit,
    onOpenLicenses: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val backupUsage by viewModel.backupUsage.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        language = language,
        backups = BackupUsage(usedBytes = backupUsage, sizeCapBytes = viewModel.backupSizeCap),
        onThemeMode = viewModel::setThemeMode,
        onDynamicColor = viewModel::setDynamicColor,
        onPalette = viewModel::setPalette,
        onContrast = viewModel::setContrast,
        onLanguage = viewModel::setLanguage,
        onOfflineMode = viewModel::setOfflineMode,
        onBackupRetention = viewModel::setBackupRetention,
        onSidecarNaming = viewModel::setSidecarNaming,
        onDeleteAllBackups = viewModel::deleteAllBackups,
        onOpenPrivacy = onOpenPrivacy,
        onOpenLicenses = onOpenLicenses,
        modifier = modifier,
    )
}

@Composable
fun SettingsContent(
    settings: AppSettings,
    language: AppLanguage,
    backups: BackupUsage,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onPalette: (BrandPalette) -> Unit,
    onContrast: (ContrastPreference) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onOfflineMode: (Boolean) -> Unit,
    onBackupRetention: (BackupRetention) -> Unit,
    onSidecarNaming: (SidecarNaming) -> Unit,
    onDeleteAllBackups: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenLicenses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    ScreenScaffold(title = stringResource(R.string.nav_settings), modifier = modifier) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { SectionHeader(stringResource(R.string.settings_section_appearance)) }
                item {
                    GroupSurface {
                        LabeledControl(stringResource(R.string.settings_theme)) {
                            ChoiceRow(
                                choices = listOf(
                                    Choice(ThemeMode.System, stringResource(R.string.settings_theme_system), Icons.Rounded.BrightnessAuto),
                                    Choice(ThemeMode.Light, stringResource(R.string.settings_theme_light), Icons.Rounded.LightMode),
                                    Choice(ThemeMode.Dark, stringResource(R.string.settings_theme_dark), Icons.Rounded.DarkMode),
                                ),
                                selected = settings.themeMode,
                                onSelect = onThemeMode,
                            )
                        }
                        if (dynamicSupported) {
                            SwitchRow(
                                title = stringResource(R.string.settings_dynamic_color),
                                supporting = stringResource(R.string.settings_dynamic_color_body),
                                icon = Icons.Rounded.Palette,
                                checked = settings.dynamicColor,
                                onCheckedChange = onDynamicColor,
                            )
                        }
                        AnimatedVisibility(visible = !dynamicSupported || !settings.dynamicColor) {
                            LabeledControl(stringResource(R.string.settings_palette)) {
                                PalettePicker(selected = settings.palette, onSelect = onPalette)
                            }
                        }
                        LabeledControl(
                            title = stringResource(R.string.settings_contrast),
                            supporting = stringResource(R.string.settings_contrast_body),
                        ) {
                            ChoiceRow(
                                choices = listOf(
                                    Choice(ContrastPreference.System, stringResource(R.string.settings_contrast_system)),
                                    Choice(ContrastPreference.Standard, stringResource(R.string.settings_contrast_standard)),
                                    Choice(ContrastPreference.Medium, stringResource(R.string.settings_contrast_medium)),
                                    Choice(ContrastPreference.High, stringResource(R.string.settings_contrast_high)),
                                ),
                                selected = settings.contrast,
                                onSelect = onContrast,
                            )
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.settings_section_language)) }
                item {
                    GroupSurface {
                        LabeledControl(stringResource(R.string.settings_language)) {
                            ChoiceRow(
                                choices = listOf(
                                    Choice(AppLanguage.System, stringResource(R.string.settings_language_system)),
                                    Choice(AppLanguage.English, stringResource(R.string.settings_language_english)),
                                    Choice(AppLanguage.Swedish, stringResource(R.string.settings_language_swedish)),
                                ),
                                selected = language,
                                onSelect = onLanguage,
                            )
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.settings_section_privacy)) }
                item {
                    GroupSurface {
                        SwitchRow(
                            title = stringResource(R.string.settings_offline_mode),
                            supporting = stringResource(R.string.settings_offline_mode_body),
                            icon = Icons.Rounded.CloudOff,
                            checked = settings.offlineMode,
                            onCheckedChange = onOfflineMode,
                        )
                        ListRow(
                            title = stringResource(R.string.privacy_title),
                            supporting = stringResource(R.string.settings_privacy_body),
                            icon = Icons.Rounded.PrivacyTip,
                            onClick = onOpenPrivacy,
                            trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null) },
                        )
                    }
                }
                editingAndBackupSettings(
                    settings = settings,
                    backups = backups,
                    onBackupRetention = onBackupRetention,
                    onSidecarNaming = onSidecarNaming,
                    onDeleteAllBackups = onDeleteAllBackups,
                )
                item { SectionHeader(stringResource(R.string.settings_section_about)) }
                item {
                    GroupSurface {
                        ListRow(
                            title = stringResource(R.string.licenses_title),
                            icon = Icons.Rounded.Description,
                            onClick = onOpenLicenses,
                            trailing = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null) },
                        )
                        ListRow(
                            title = stringResource(R.string.settings_version),
                            supporting = BuildConfig.VERSION_NAME,
                            icon = Icons.Rounded.Info,
                        )
                    }
                }
            }
        }
    }
}

/** A settings row with a switch. The whole row toggles, and TalkBack reads it as one switch. */
@Composable
private fun SwitchRow(
    title: String,
    supporting: String?,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun LabeledControl(
    title: String,
    supporting: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun PalettePicker(selected: BrandPalette, onSelect: (BrandPalette) -> Unit) {
    val dark = ExifLabTheme.isDark
    val shape = MaterialShapes.Cookie9Sided.toShape()
    FlowRow(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandPalette.entries.forEach { palette ->
            val scheme = remember(palette, dark) {
                dynamicColorScheme(seedColor = palette.seed, isDark = dark, style = palette.style)
            }
            val isSelected = palette == selected
            val name = stringResource(palette.labelRes())
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(shape)
                    .background(scheme.primaryContainer)
                    .then(
                        if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier,
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(palette) })
                    .semantics { contentDescription = name },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(scheme.primary))
                if (isSelected) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
    // Preview label for sighted users; screen readers already get it from each swatch.
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(selected.labelRes()),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics { },
    )
}

private fun BrandPalette.labelRes(): Int = when (this) {
    BrandPalette.Lagoon -> R.string.palette_lagoon
    BrandPalette.Citrus -> R.string.palette_citrus
    BrandPalette.Ember -> R.string.palette_ember
    BrandPalette.Iris -> R.string.palette_iris
    BrandPalette.Moss -> R.string.palette_moss
    BrandPalette.Graphite -> R.string.palette_graphite
}
