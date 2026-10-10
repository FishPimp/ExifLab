package io.github.fishpimp.exiflab.ui.photo

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.model.MetadataReport
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory
import io.github.fishpimp.exiflab.screenshots.FakeReports
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Copying, TalkBack actions and the controls of the viewer, on the stateless [PhotoContent]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2400dp-xxhdpi")
class PhotoContentInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val clipboard: ClipboardManager
        get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)

    private fun show(
        report: MetadataReport = FakeReports.phoneJpeg,
        query: String = "",
        filter: SensitivityCategory? = null,
        actions: PhotoActions = PhotoActions(),
        scrolledToTags: Boolean = true,
    ) {
        val state = PhotoUiState(
            load = PhotoLoad.Loaded(report),
            browser = TagBrowserBuilder.build(report, query, ValueMode.Readable, filter, emptySet()),
            filter = filter,
        )
        compose.setContent {
            ExifLabTheme(dynamicColor = false) {
                PhotoContent(
                    ref = FakeReports.phoneRef,
                    state = state,
                    query = query,
                    actions = actions,
                    listState = rememberLazyListState(initialFirstVisibleItemIndex = if (scrolledToTags) 1 else 0),
                )
            }
        }
    }

    private fun hasCustomAction(label: String) = SemanticsMatcher("has custom action $label") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
    }

    @Test
    fun `tag rows read as one node and offer copy actions to TalkBack`() {
        show()
        val make = compose.onNode(hasContentDescription("Make, Google"))
        make.assert(hasCustomAction("Copy value")).assert(hasCustomAction("Copy name and value"))

        make.performCustomAccessibilityActionWithLabel("Copy name and value")
        compose.waitForIdle()
        assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo("Make: Google")

        make.performCustomAccessibilityActionWithLabel("Copy value")
        compose.waitForIdle()
        assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo("Google")
    }

    @Test
    fun `long-press copies the value and confirms with a snackbar`() {
        show()
        compose.onNode(hasContentDescription("Model, Pixel 8 Pro")).performTouchInput { longClick() }
        compose.waitForIdle()
        assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo("Pixel 8 Pro")
        compose.onNodeWithText("Model copied").assertIsDisplayed()
    }

    @Test
    fun `sensitive rows say so in words`() {
        show(query = "latitude")
        compose.onNode(hasContentDescription("GPS Latitude, 59° 19' 35.29\", Sensitive: Location")).assertIsDisplayed()
    }

    @Test
    fun `section headers toggle their section and report their state`() {
        var toggled: String? = null
        show(actions = PhotoActions(onToggleSection = { toggled = it }))
        val header = compose.onNode(hasText("Exif IFD0") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = false)
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        header.performClick()
        assertThat(toggled).isEqualTo("exif-ifd0")
    }

    @Test
    fun `privacy badges toggle the sensitivity filter`() {
        var filtered: SensitivityCategory? = null
        show(filter = SensitivityCategory.Location, actions = PhotoActions(onToggleFilter = { filtered = it }), scrolledToTags = false)
        val badge = compose.onNode(hasText("Location") and hasText("11 tags") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
        badge.assertIsOn()
        badge.performClick()
        assertThat(filtered).isEqualTo(SensitivityCategory.Location)
    }

    @Test
    fun `typing in the search field reports the query`() {
        val typed = mutableListOf<String>()
        show(actions = PhotoActions(onQueryChange = { typed += it }))
        compose.onNodeWithText("Search tags and values").performTextInput("iso")
        assertThat(typed.last()).isEqualTo("iso")
    }
}
