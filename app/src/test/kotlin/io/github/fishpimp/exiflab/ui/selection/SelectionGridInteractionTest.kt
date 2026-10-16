package io.github.fishpimp.exiflab.ui.selection

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.batch.BatchPhotos
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Selecting photos in a grid by long press, taps and the TalkBack action, then a batch action. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1200dp-xxhdpi")
class SelectionGridInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val photos = listOf(BatchPhotos.folder("a.jpg"), BatchPhotos.folder("b.jpg"), BatchPhotos.folder("c.jpg"))

    @Test
    fun `long press selects, taps toggle, TalkBack hears the state, and actions get the selection`() {
        val holder = PhotoSelectionHolder()
        val actions = holder.actions { photos.map { it.uri } }
        val opened = mutableListOf<String>()
        val batch = mutableListOf<BatchKind>()
        compose.setContent {
            val selection by holder.state.collectAsState()
            ExifLabTheme(dynamicColor = false) {
                SelectionContent(
                    refs = photos,
                    onBack = {},
                    onOpenPhoto = { opened += it.uri },
                    selection = selection,
                    selectionActions = actions,
                    onBatchAction = { batch += it },
                )
            }
        }

        // Outside selection mode a tap opens the photo; "Select" is offered as a TalkBack action.
        compose.onNodeWithContentDescription("a.jpg").performClick()
        assertThat(opened).containsExactly(photos[0].uri)
        compose.onNodeWithContentDescription("a.jpg").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))

        compose.onNodeWithContentDescription("b.jpg").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.onNodeWithText("1 selected").assertExists()
        compose.onNodeWithContentDescription("b.jpg")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        compose.onNodeWithContentDescription("a.jpg")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not selected"))

        // In selection mode a tap toggles instead of opening.
        compose.onNodeWithContentDescription("a.jpg").performClick()
        assertThat(opened).hasSize(1)
        assertThat(holder.state.value.selected).containsExactly(photos[0].uri, photos[1].uri)

        compose.onNodeWithContentDescription("Select all").performClick()
        compose.onNodeWithText("3 selected").assertExists()
        compose.onAllNodesWithContentDescription("Strip metadata").assertCountEquals(1)
        compose.onNodeWithContentDescription("Strip metadata").performClick()
        assertThat(batch).containsExactly(BatchKind.Strip)

        compose.onNodeWithContentDescription("Stop selecting").performClick()
        assertThat(holder.state.value.active).isFalse()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun `on a 360dp phone the actions that don't fit are in More options, with their names`() {
        val shared = mutableListOf<Unit>()
        val exported = mutableListOf<Unit>()
        compose.setContent {
            ExifLabTheme(dynamicColor = false) {
                SelectionContent(
                    refs = photos,
                    onBack = {},
                    onOpenPhoto = {},
                    selection = PhotoSelection(active = true, selected = setOf(photos[0].uri)),
                    onShareClean = { shared += Unit },
                    onExport = { exported += Unit },
                )
            }
        }
        compose.onNodeWithContentDescription("Strip metadata").assertIsDisplayed()
        compose.onNodeWithContentDescription("Share clean").assertDoesNotExist()

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Share clean").performClick()
        assertThat(shared).hasSize(1)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Export").performClick()
        assertThat(exported).hasSize(1)
    }

    @Test
    fun `with room for every action there is no More options`() {
        compose.setContent {
            ExifLabTheme(dynamicColor = false) {
                SelectionContent(
                    refs = photos,
                    onBack = {},
                    onOpenPhoto = {},
                    selection = PhotoSelection(active = true, selected = setOf(photos[0].uri)),
                    onShareClean = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Share clean").assertIsDisplayed()
        compose.onNodeWithContentDescription("More options").assertDoesNotExist()
    }
}
