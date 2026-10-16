package io.github.fishpimp.exiflab.ui.batch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.batch.batchKindOfTitle
import io.github.fishpimp.exiflab.data.batch.storedTitle
import io.github.fishpimp.exiflab.data.settings.withLocales
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Batch titles are stored as a key and shown in the language the app has now; text made outside
 * an activity (notifications, the PDF report) gets the in-app language from a localized context.
 */
@RunWith(RobolectricTestRunner::class)
class BatchTitleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a stored batch title follows the current language`() {
        val stored = BatchKind.Location.storedTitle
        assertThat(batchKindOfTitle(stored)).isEqualTo(BatchKind.Location)
        assertThat(context.withLocales("en").batchTitle(stored)).isEqualTo("Set location")
        assertThat(context.withLocales("sv").batchTitle(stored)).isEqualTo("Ange plats")
        BatchKind.entries.forEach { assertThat(batchKindOfTitle(it.storedTitle)).isEqualTo(it) }
    }

    @Test
    fun `titles older versions stored as text are shown as they are`() {
        assertThat(batchKindOfTitle("Ange plats")).isNull()
        assertThat(context.withLocales("en").batchTitle("Ange plats")).isEqualTo("Ange plats")
    }

    @Test
    fun `a localized context carries the language for strings and formats`() {
        val swedish = context.withLocales("sv")
        assertThat(swedish.resources.configuration.locales[0].language).isEqualTo("sv")
        assertThat(swedish.getString(R.string.batch_action_strip)).isNotEqualTo(
            context.withLocales("en").getString(R.string.batch_action_strip),
        )
    }
}
