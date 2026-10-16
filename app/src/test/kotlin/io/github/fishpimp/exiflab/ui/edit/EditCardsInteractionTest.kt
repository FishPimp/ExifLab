package io.github.fishpimp.exiflab.ui.edit

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.edit.EditOperation
import io.github.fishpimp.exiflab.screenshots.FakeReports
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Editor cards: what TalkBack hears for queued edits, and no removal a sidecar can't carry out. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class EditCardsInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a queued edit is a button that removes it, not a checked checkbox`() {
        val removed = mutableListOf<Long>()
        val edit = PendingEdit(7, EditSlot.TimeShift, EditOperation.ShiftCaptureTime(Duration.ofHours(-1)))
        compose.setContent {
            ExifLabTheme(dynamicColor = false) { PendingEditsCard(listOf(edit), onRemove = { removed += it }, onDiscardAll = {}) }
        }
        val chip = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and SemanticsMatcher("names the edit") { node ->
            node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }.orEmpty().any { it.startsWith("Shift time") }
        })
        chip.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        chip.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
        chip.assert(SemanticsMatcher("one name, read once") { it.config[SemanticsProperties.ContentDescription].size == 1 })
        assertThat(removed).isEmpty()

        chip.performSemanticsAction(SemanticsActions.OnClick)
        assertThat(removed).containsExactly(7L)
    }

    @Test
    fun `a sidecar-only photo explains instead of offering Remove location`() {
        compose.setContent {
            ExifLabTheme(dynamicColor = false) {
                LocationCard(FakeReports.phoneJpeg, emptyList(), readingOtherPhoto = false, onChangeLocation = {}, onCopyFromPhoto = {}, onRemoveLocation = {}, sidecarOnly = true)
            }
        }
        compose.onNodeWithText("Remove location").assertDoesNotExist()
        compose.onNodeWithText("The location can't be removed from this file", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a writable photo offers Remove location`() {
        compose.setContent {
            ExifLabTheme(dynamicColor = false) {
                LocationCard(FakeReports.phoneJpeg, emptyList(), readingOtherPhoto = false, onChangeLocation = {}, onCopyFromPhoto = {}, onRemoveLocation = {})
            }
        }
        compose.onNodeWithText("Remove location").assertIsDisplayed()
    }
}
