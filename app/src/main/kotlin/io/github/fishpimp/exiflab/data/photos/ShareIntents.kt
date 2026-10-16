package io.github.fishpimp.exiflab.data.photos

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.IntentCompat

/**
 * Content URIs shared with [Intent.ACTION_SEND] or [Intent.ACTION_SEND_MULTIPLE], in order and
 * without duplicates; empty for any other intent.
 *
 * Senders put the streams in [Intent.EXTRA_STREAM], in [Intent.getClipData] (where the URI grants
 * live), or both, so both are read. [IntentCompat] uses the typed `getParcelableExtra` APIs where
 * the platform implements them reliably and the older ones elsewhere. Only `content://` URIs are
 * accepted: a `file://` URI could point into ExifLab's own private storage.
 */
fun Intent.sharedStreamUris(): List<Uri> {
    val fromExtras: List<Uri> = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filterNotNull()
        else -> return emptyList()
    }
    val fromClip = clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    return (fromExtras + fromClip)
        .filter { it.scheme == ContentResolver.SCHEME_CONTENT }
        .distinct()
}

/**
 * The shared [this] URIs without the ones ExifLab could read only with its own access, never
 * with the sender's: its own providers (such as cleaned copies), documents inside a folder tree
 * (the tree grant is ExifLab's), and URIs ExifLab holds a persisted grant for ([grants], from
 * earlier picks and documents). Exported share targets would otherwise read them on behalf of any
 * app that sends their URI (a confused deputy) and offer them to be shared on. A real share
 * grants its photos to ExifLab with the intent, so it never needs these.
 */
fun List<Uri>.withoutOwnAccess(ownPackage: String, grants: GrantSnapshot): List<Uri> = filterNot { uri ->
    val authority = uri.authority.orEmpty()
    authority == ownPackage || authority.startsWith("$ownPackage.") ||
        DocumentsContract.isTreeUri(uri) || grants.canRead(uri.toString())
}
