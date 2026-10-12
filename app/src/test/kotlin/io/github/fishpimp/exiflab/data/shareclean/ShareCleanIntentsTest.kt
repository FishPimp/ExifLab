package io.github.fishpimp.exiflab.data.shareclean

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.fishpimp.exiflab.MainActivity
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.ui.shareclean.ShareCleanActivity
import io.github.fishpimp.exiflab.ui.shareclean.ownShareTargets
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/** The intents that hand cleaned copies on: send, chooser, grants, excludes, FileProvider URIs, the shortcut. */
@RunWith(RobolectricTestRunner::class)
class ShareCleanIntentsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val authority = "${context.packageName}.shareclean"
    private val first = Uri.parse("content://$authority/shared/1/IMG_1.jpg")
    private val second = Uri.parse("content://$authority/shared/2/IMG_2.heic")

    @Test
    fun `one copy is sent with ACTION_SEND, its type and a read grant`() {
        val send = ShareCleanIntents.send(listOf(first), listOf("image/jpeg"))

        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("image/jpeg")
        assertThat(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)).isEqualTo(first)
        assertThat(send.clipData!!.itemCount).isEqualTo(1)
        assertThat(send.clipData!!.getItemAt(0).uri).isEqualTo(first)
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
    }

    @Test
    fun `several copies are sent with ACTION_SEND_MULTIPLE, every URI in the clip and a common type`() {
        val send = ShareCleanIntents.send(listOf(first, second), listOf("image/jpeg", "image/heif"))

        assertThat(send.action).isEqualTo(Intent.ACTION_SEND_MULTIPLE)
        assertThat(send.type).isEqualTo("image/*")
        assertThat(IntentCompat.getParcelableArrayListExtra(send, Intent.EXTRA_STREAM, Uri::class.java)).containsExactly(first, second).inOrder()
        assertThat((0 until send.clipData!!.itemCount).map { send.clipData!!.getItemAt(it).uri }).containsExactly(first, second).inOrder()
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)

        assertThat(ShareCleanIntents.mimeTypeFor(listOf("image/png", "image/png"))).isEqualTo("image/png")
    }

    @Test
    fun `the chooser leaves out ExifLab's own share targets and keeps the grant`() {
        val send = ShareCleanIntents.send(listOf(first), listOf("image/jpeg"))
        val chooser = ShareCleanIntents.chooser(send, "Share cleaned photo", ownShareTargets(context))

        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)?.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString()).isEqualTo("Share cleaned photo")
        @Suppress("DEPRECATION")
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.map { it as ComponentName }
        assertThat(excluded).containsExactly(
            ComponentName(context, MainActivity::class.java),
            ComponentName(context, ShareCleanActivity::class.java),
        )
        assertThat(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun `copies in the shared cache get URIs of the shareclean provider`() {
        val directory = context.cacheDir.resolve("${SharedFilesStore.DIRECTORY_NAME}/abc").apply { mkdirs() }
        val file = directory.resolve("IMG_1.jpg").apply { writeText("x") }

        val uri = ShareCleanIntents.uriFor(context, file)

        assertThat(ShareCleanIntents.authority(context)).isEqualTo(authority)
        assertThat(uri.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo(authority)
        assertThat(uri.lastPathSegment).isEqualTo("IMG_1.jpg")
    }

    @Test
    fun `the launcher shortcut opens the pick flow of this build's package`() {
        val parser = context.resources.getXml(R.xml.shortcuts)
        val intent = mutableMapOf<String, String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "intent") {
                for (i in 0 until parser.attributeCount) intent[parser.getAttributeName(i)] = parser.getAttributeValue(i)
            }
        }
        assertThat(intent["action"]).isEqualTo(ShareCleanActivity.ACTION_PICK_AND_SHARE)
        assertThat(intent["targetClass"]).isEqualTo(ShareCleanActivity::class.java.name)
        assertThat(intent["targetPackage"]).isEqualTo(context.packageName)
    }
}
