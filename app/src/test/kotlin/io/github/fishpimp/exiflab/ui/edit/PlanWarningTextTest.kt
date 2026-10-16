package io.github.fishpimp.exiflab.ui.edit

import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.ImageFormat
import io.github.fishpimp.exiflab.metadata.edit.AuthorField
import io.github.fishpimp.exiflab.metadata.edit.DateAction
import io.github.fishpimp.exiflab.metadata.edit.PlanWarning
import io.github.fishpimp.exiflab.metadata.write.ExifIfd
import io.github.fishpimp.exiflab.metadata.write.WriteSkip
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Planner warnings and writer skips are worded from resources, so they follow the app language. */
@RunWith(RobolectricTestRunner::class)
class PlanWarningTextTest {
    @get:Rule val compose = createComposeRule()

    private val warnings = listOf(
        PlanWarning.NoCaptureDate(DateAction.Shift),
        PlanWarning.NoCaptureDate(DateAction.Convert),
        PlanWarning.NoCaptureDate(DateAction.SetZone),
        PlanWarning.LatitudeOutOfRange(91.25),
        PlanWarning.LongitudeOutOfRange(-180.5),
        PlanWarning.AltitudeImplausible(1e7),
        PlanWarning.NonAsciiField(AuthorField.Artist, alsoIptc = true),
        PlanWarning.NonAsciiField(AuthorField.Artist, alsoIptc = false),
        PlanWarning.NonAsciiField(AuthorField.Copyright, alsoIptc = true),
        PlanWarning.NonAsciiField(AuthorField.Copyright, alsoIptc = false),
        PlanWarning.IptcTooLong(AuthorField.Artist, 32),
        PlanWarning.IptcTooLong(AuthorField.Copyright, 128),
    )

    private val skips = listOf(
        WriteSkip.IptcUnsupported(ImageFormat.Png, 2),
        WriteSkip.HeifColorProfileKept,
        WriteSkip.StructuralTag(ExifIfd.Primary, 0x8769),
        WriteSkip.IptcDatasetRejected(2, 80, tooLong = true),
        WriteSkip.IptcDatasetRejected(12, 80, tooLong = false),
        WriteSkip.MakerNoteMoved,
        WriteSkip.ExifDataDropped("Dropped a damaged link to EXIF IFD1"),
    )

    private fun texts(): Pair<List<String>, List<String>> {
        var planTexts = emptyList<String>()
        var skipTexts = emptyList<String>()
        compose.setContent {
            planTexts = warnings.map { planWarningText(it) }
            skipTexts = skips.map { writeSkipText(it) }
        }
        compose.waitForIdle()
        return planTexts to skipTexts
    }

    @Test
    fun `every warning and skip has its own English text`() {
        val (plan, skip) = texts()
        assertThat(plan.distinct()).hasSize(warnings.size)
        assertThat(skip.distinct()).hasSize(skips.size)
        assertThat(plan[3]).contains("91.25")
        assertThat(skip[0]).isEqualTo("PNG files can't hold IPTC, so 2 IPTC changes were not written.")
        assertThat(skip[2]).contains("0x8769")
        // The writer's English detail is for logs only.
        assertThat(skip.last()).doesNotContain("IFD1")
    }

    @Test
    @Config(qualifiers = "sv")
    fun `in Swedish nothing English from the planner or writer shows`() {
        val (plan, skip) = texts()
        assertThat(plan[6]).isEqualTo("Upphovspersonen innehåller tecken som EXIF inte kan lagra, så den sparas bara i XMP och IPTC.")
        assertThat(plan[3]).contains("91,25")
        assertThat(skip[0]).isEqualTo("PNG-filer kan inte innehålla IPTC, så 2 IPTC-ändringar skrevs inte.")
        (plan + skip).forEach { text ->
            assertThat(text).doesNotContain(" the ")
            assertThat(text).doesNotContain(" is ")
        }
    }
}
