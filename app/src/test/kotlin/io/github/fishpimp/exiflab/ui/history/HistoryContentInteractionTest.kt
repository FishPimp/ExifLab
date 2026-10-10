package io.github.fishpimp.exiflab.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.screenshots.FakeHistory
import io.github.fishpimp.exiflab.ui.photo.breakableFileName
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opening edits, expanding a batch and the confirmations of restore actions. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp-xxhdpi")
class HistoryContentInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `tapping an edit opens it`() {
        val opened = mutableListOf<String>()
        compose.setContent {
            ExifLabTheme(dynamicColor = false) {
                HistoryContent(FakeHistory.list, onOpenRecord = { opened += it }, onRestoreBatch = {})
            }
        }

        // Titles carry invisible break opportunities after "_".
        compose.onNodeWithText(breakableFileName("export_final.webp")).performClick()

        assertThat(opened).containsExactly(FakeHistory.failed.id)
    }

    @Test
    fun `a batch expands, and restoring it needs a confirmation`() {
        val restored = mutableListOf<String>()
        compose.setContent {
            ExifLabTheme(dynamicColor = false) {
                HistoryContent(FakeHistory.list, onOpenRecord = {}, onRestoreBatch = { restored += it })
            }
        }
        val header = compose.onNodeWithText(FakeHistory.batch.title, useUnmergedTree = true)
        compose.onNodeWithText("Restore all").assertDoesNotExist()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).assertIsDisplayed()

        header.performClick()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).assertIsDisplayed()
        compose.onNodeWithText("Restore all").performClick()
        assertThat(restored).isEmpty()
        compose.onNodeWithText("Restore all originals?").assertIsDisplayed()
        // 3 of the 4 photos were saved and can be restored.
        compose.onNodeWithText("3 photos", substring = true).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Restore all"))) and isInDialog())
            .performClick()

        assertThat(restored).containsExactly(FakeHistory.batch.id)
    }

    @Test
    fun `restore original asks first, and copies offer no restore`() {
        var restores = 0
        var record by mutableStateOf(FakeHistory.inPlace)
        compose.setContent {
            ExifLabTheme(dynamicColor = false) {
                HistoryDetailContent(FakeHistory.detail(record), HistoryDetailActions(onRestore = { restores++ }), onBack = {})
            }
        }

        compose.onNodeWithText("Restore original").performClick()
        compose.onNodeWithText("Restore the original?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(restores).isEqualTo(0)

        compose.onNodeWithText("Restore original").performClick()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Restore original"))) and isInDialog())
            .performClick()
        assertThat(restores).isEqualTo(1)

        record = FakeHistory.copy
        compose.waitForIdle()
        compose.onNodeWithText("Restore original").assertDoesNotExist()
        compose.onNodeWithText("No backup needed").assertIsDisplayed()
        compose.onNodeWithText("Copy").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
    }

    private fun isInDialog() = androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog())
}
