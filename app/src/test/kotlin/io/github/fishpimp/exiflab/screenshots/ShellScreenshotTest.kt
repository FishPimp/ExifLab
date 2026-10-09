package io.github.fishpimp.exiflab.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.fishpimp.exiflab.data.settings.AppLanguage
import io.github.fishpimp.exiflab.data.settings.AppSettings
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import io.github.fishpimp.exiflab.ui.ExifLabApp
import io.github.fishpimp.exiflab.ui.privacy.PrivacyScreen
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

    @Test @Config(qualifiers = "w411dp-h2400dp-xxhdpi", fontScale = 2.0f)
    fun settings_large_font() = compose.snapshot("settings_phone_font200") { SettingsUnderTest() }

    @Test @Config(qualifiers = "sv-w411dp-h1400dp-xxhdpi")
    fun settings_swedish() = compose.snapshot("settings_phone_sv", ThemeMode.Dark, BrandPalette.Ember) { SettingsUnderTest() }

    @Test @Config(qualifiers = "w411dp-h1600dp-xxhdpi")
    fun privacy() = compose.snapshot("privacy_phone_light", palette = BrandPalette.Moss) { PrivacyScreen(onBack = {}) }

    @androidx.compose.runtime.Composable
    private fun SettingsUnderTest() = SettingsContent(
        settings = AppSettings(dynamicColor = false),
        language = AppLanguage.System,
        onThemeMode = {}, onDynamicColor = {}, onPalette = {}, onContrast = {}, onLanguage = {},
        onOpenPrivacy = {}, onOpenLicenses = {},
    )
}
