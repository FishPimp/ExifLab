package io.github.fishpimp.exiflab.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.fishpimp.exiflab.data.settings.AppLanguage
import io.github.fishpimp.exiflab.data.settings.AppSettings
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import io.github.fishpimp.exiflab.ui.ExifLabApp
import io.github.fishpimp.exiflab.ui.privacy.LicensesScreen
import io.github.fishpimp.exiflab.ui.privacy.PrivacyScreen
import io.github.fishpimp.exiflab.ui.settings.BackupUsage
import io.github.fishpimp.exiflab.ui.settings.SettingsContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun home_phone_light() = compose.snapshot("home_phone_light") { ExifLabApp() }

    @Test @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun home_phone_dark() = compose.snapshot("home_phone_dark", ThemeMode.Dark) { ExifLabApp() }

    @Test @Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
    fun home_phone_swedish() = compose.snapshot("home_phone_sv", palette = BrandPalette.Citrus) { ExifLabApp() }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun home_tablet() = compose.snapshot("home_tablet_landscape", palette = BrandPalette.Iris) { ExifLabApp() }

    @Test @Config(qualifiers = "w411dp-h1700dp-xxhdpi")
    fun settings_light() = compose.snapshot("settings_phone_light") { SettingsUnderTest(offlineMode = true) }

    @Test @Config(qualifiers = "w411dp-h3200dp-xxhdpi", fontScale = 2.0f)
    fun settings_large_font() = compose.snapshot("settings_phone_font200") { SettingsUnderTest() }

    @Test @Config(qualifiers = "sv-w411dp-h1700dp-xxhdpi")
    fun settings_swedish() = compose.snapshot("settings_phone_sv", ThemeMode.Dark, BrandPalette.Ember) { SettingsUnderTest() }

    @Test @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun privacy() = compose.snapshot("privacy_phone_light", palette = BrandPalette.Moss) { PrivacyScreen(onBack = {}) }

    @Test @Config(qualifiers = "sv-w411dp-h2100dp-night-xxhdpi")
    fun privacy_swedish_dark() = compose.snapshot("privacy_phone_sv_dark", ThemeMode.Dark, BrandPalette.Iris) {
        PrivacyScreen(onBack = {})
    }

    @Test @Config(qualifiers = "w411dp-h3600dp-xxhdpi", fontScale = 2.0f)
    fun privacy_large_font() = compose.snapshot("privacy_phone_font200") { PrivacyScreen(onBack = {}) }

    @Test @Config(qualifiers = "w411dp-h2600dp-xxhdpi")
    fun licenses() = compose.snapshot("licenses_phone_light", palette = BrandPalette.Graphite) { LicensesScreen(onBack = {}) }

    @androidx.compose.runtime.Composable
    private fun SettingsUnderTest(offlineMode: Boolean = false) = SettingsContent(
        settings = AppSettings(dynamicColor = false, offlineMode = offlineMode),
        language = AppLanguage.System,
        backups = BackupUsage(usedBytes = 184_320_000),
        onThemeMode = {}, onDynamicColor = {}, onPalette = {}, onContrast = {}, onLanguage = {}, onOfflineMode = {},
        onBackupRetention = {}, onSidecarNaming = {}, onDeleteAllBackups = {},
        onOpenPrivacy = {}, onOpenLicenses = {},
    )
}
