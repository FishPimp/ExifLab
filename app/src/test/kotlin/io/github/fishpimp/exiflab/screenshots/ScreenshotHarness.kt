package io.github.fishpimp.exiflab.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode

/** Renders [content] in the app theme and saves a PNG under build/outputs/roborazzi. */
fun ComposeContentTestRule.snapshot(
    name: String,
    themeMode: ThemeMode = ThemeMode.Light,
    palette: BrandPalette = BrandPalette.Lagoon,
    content: @Composable () -> Unit,
) {
    setContent {
        ExifLabTheme(themeMode = themeMode, dynamicColor = false, palette = palette) { content() }
    }
    waitForIdle()
    onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
}
