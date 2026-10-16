package io.github.fishpimp.exiflab.ui.folder

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.photos.PhotoAccessError
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A folder that can't be opened offers the way out that fits the error. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class FolderErrorInteractionTest {
    @get:Rule val compose = createComposeRule()

    private fun show(error: PhotoAccessError, onRetry: () -> Unit = {}, onOpenLibrary: () -> Unit = {}) = compose.setContent {
        ExifLabTheme(dynamicColor = false) {
            FolderContent(
                title = "Camera",
                state = FolderUiState.Failed(error),
                onBack = {},
                onOpenSubfolder = {},
                onOpenPhoto = {},
                onRetry = onRetry,
                onOpenLibrary = onOpenLibrary,
            )
        }
    }

    @Test
    fun `a read failure can be tried again`() {
        var retried = 0
        show(PhotoAccessError.ReadFailed, onRetry = { retried++ })
        compose.onNodeWithText("Try again").performClick()
        assertThat(retried).isEqualTo(1)
        compose.onNodeWithText("Go to Library").assertDoesNotExist()
    }

    @Test
    fun `lost access leads to the Library, where it is granted again`() {
        var opened = 0
        show(PhotoAccessError.PermissionDenied, onOpenLibrary = { opened++ })
        compose.onNodeWithText("Go to Library").performClick()
        assertThat(opened).isEqualTo(1)
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }
}
