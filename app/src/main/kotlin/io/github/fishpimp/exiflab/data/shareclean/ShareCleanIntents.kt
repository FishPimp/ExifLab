package io.github.fishpimp.exiflab.data.shareclean

import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Serves the cleaned copies in the cache's `shared` directory, read-only, to the app the user
 * shares them with (through per-URI read grants). A subclass so its manifest entry cannot clash
 * with another FileProvider in the app.
 */
class ShareCleanFileProvider : FileProvider()

/** Builds the intents that hand cleaned copies to another app. */
object ShareCleanIntents {
    /** The [ShareCleanFileProvider] authority: `<applicationId>.shareclean`. */
    fun authority(context: Context): String = "${context.packageName}$AUTHORITY_SUFFIX"

    /** A content URI for [file], which must be in the [SharedFilesStore]. */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    /** The MIME type for a share of [mimeTypes]: the common one, or any image type for a mix. */
    fun mimeTypeFor(mimeTypes: List<String>): String = mimeTypes.distinct().singleOrNull() ?: "image/*"

    /**
     * [Intent.ACTION_SEND] for one file, [Intent.ACTION_SEND_MULTIPLE] for several, with every
     * URI in [Intent.EXTRA_STREAM] and in the clip data, which is where read grants travel.
     */
    fun send(uris: List<Uri>, mimeTypes: List<String>): Intent {
        require(uris.isNotEmpty()) { "Nothing to share" }
        val type = mimeTypeFor(mimeTypes)
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val clip = ClipData(ClipDescription("", arrayOf(type)), ClipData.Item(uris.first()))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return intent.apply {
            setType(type)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * The system share sheet for [send], titled [title], without [exclude] (ExifLab's own share
     * targets, so the user is not offered to share the copies back into ExifLab).
     */
    fun chooser(send: Intent, title: CharSequence, exclude: List<ComponentName>): Intent =
        Intent.createChooser(send, title).apply {
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, exclude.toTypedArray())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private const val AUTHORITY_SUFFIX = ".shareclean"
}
