package io.github.fishpimp.exiflab.ui.selection

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
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
}
