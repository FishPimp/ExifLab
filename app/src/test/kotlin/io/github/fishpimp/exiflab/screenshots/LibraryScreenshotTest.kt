package io.github.fishpimp.exiflab.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.fishpimp.exiflab.designsystem.theme.BrandPalette
import io.github.fishpimp.exiflab.designsystem.theme.ThemeMode
import io.github.fishpimp.exiflab.ui.folder.FolderContent
import io.github.fishpimp.exiflab.ui.folder.FolderUiState
import io.github.fishpimp.exiflab.ui.home.HomeContent
import io.github.fishpimp.exiflab.ui.library.LibraryContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home with recents, the Library and a folder grid, in light, dark, Swedish and at 200% font. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun home_light() = compose.snapshot("files_home_light") { Home() }

    @Test @Config(qualifiers = "w411dp-h1100dp-night-xxhdpi")
    fun home_dark() = compose.snapshot("files_home_dark", ThemeMode.Dark, BrandPalette.Iris) { Home() }

    @Test @Config(qualifiers = "sv-w411dp-h1100dp-xxhdpi")
    fun home_swedish() = compose.snapshot("files_home_sv", palette = BrandPalette.Citrus) { Home() }

    @Test @Config(qualifiers = "w411dp-h2000dp-xxhdpi", fontScale = 2.0f)
    fun home_large_font() = compose.snapshot("files_home_font200") { Home() }

    @Test @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    fun home_tablet() = compose.snapshot("files_home_tablet", palette = BrandPalette.Moss) { Home() }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun library_light() = compose.snapshot("library_light") { Library() }

    @Test @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun library_dark() = compose.snapshot("library_dark", ThemeMode.Dark, BrandPalette.Iris) { Library() }

    @Test @Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
    fun library_swedish() = compose.snapshot("library_sv", palette = BrandPalette.Citrus) { Library() }

    @Test @Config(qualifiers = "w411dp-h1600dp-xxhdpi", fontScale = 2.0f)
    fun library_large_font() = compose.snapshot("library_font200") { Library() }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun folder_light() = compose.snapshot("folder_light") { Folder() }

    @Test @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun folder_dark() = compose.snapshot("folder_dark", ThemeMode.Dark, BrandPalette.Iris) { Folder() }

    @Test @Config(qualifiers = "sv-w411dp-h891dp-xxhdpi")
    fun folder_swedish() = compose.snapshot("folder_sv", palette = BrandPalette.Citrus) { Folder() }

    @Test @Config(qualifiers = "w411dp-h1800dp-xxhdpi", fontScale = 2.0f)
    fun folder_large_font() = compose.snapshot("folder_font200") { Folder() }

    @Composable
    private fun Home() = WithFakeThumbnails {
        HomeContent(
            recents = FakePhotos.recents,
            isOpening = false,
            onOpenPhotoPicker = {},
            onOpenFiles = {},
            onBrowseFolders = {},
            onOpenRecent = {},
            onOpenPrivacy = {},
        )
    }

    @Composable
    private fun Library() = LibraryContent(
        folders = FakePhotos.folders,
        onAddFolder = {},
        onOpenFolder = {},
        onRegrant = {},
        onRemove = {},
    )

    @Composable
    private fun Folder() = WithFakeThumbnails {
        FolderContent(
            title = "Camera",
            state = FolderUiState.Loaded(FakePhotos.listing),
            onBack = {},
            onOpenSubfolder = {},
            onOpenPhoto = {},
        )
    }
}
