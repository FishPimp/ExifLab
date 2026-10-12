package io.github.fishpimp.exiflab.data.shareclean

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.metadata.edit.StripCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The last Share clean choice survives in its DataStore. */
@RunWith(RobolectricTestRunner::class)
class ShareCleanPreferencesTest {
    @Test
    fun `defaults first, then the saved choice`() = runBlocking {
        val preferences = ShareCleanPreferences(ApplicationProvider.getApplicationContext())
        assertThat(preferences.choice.first()).isEqualTo(ShareCleanChoice())

        val custom = ShareCleanChoice(
            preset = ShareCleanPreset.Custom,
            custom = setOf(StripCategory.Dates, StripCategory.PersonNames),
            includeRawPreviews = false,
        )
        preferences.save(custom)
        assertThat(preferences.choice.first()).isEqualTo(custom)

        preferences.save(custom.copy(preset = ShareCleanPreset.Everything))
        // The custom ticks are kept for when Custom is chosen again.
        assertThat(preferences.choice.first()).isEqualTo(custom.copy(preset = ShareCleanPreset.Everything))
    }
}
