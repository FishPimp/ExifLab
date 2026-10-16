package io.github.fishpimp.exiflab.data.photos

import android.content.ContentResolver
import android.content.Intent
import androidx.core.net.toUri

/** A persisted URI grant: its URI and when it was taken (or last taken again). */
data class PersistedGrant(val uri: String, val persistedAt: Long)

/**
 * Keeps ExifLab's persisted URI grants under Android's per-app limit (128 on Android 10 and on
 * low-RAM devices, 512 otherwise). Past the limit the system silently drops the grants taken
 * longest ago, and those are the library folders', taken once when each folder was added. So
 * at start-up the library folders' grants are taken again (see
 * [io.github.fishpimp.exiflab.data.folders.FolderRepository.refreshGrants]), and [toRelease]
 * picks the grants nothing needs any more: photos opened with the picker or as documents that
 * have left Recents, and folders chosen for batch copies once their jobs ended.
 */
object GrantJanitor {
    /** Grants younger than this are kept: the photo or folder may be in use right now. */
    const val GRACE_MS = 60L * 60 * 1000

    /**
     * The URIs of the [grants] to release: older than [GRACE_MS], not a library folder in
     * [libraryTrees], and not covering any URI in [needed] (recent photos, files History may still
     * write to, photos and folders of unfinished batch jobs), directly or as the folder tree a
     * needed document lies in.
     */
    fun toRelease(grants: List<PersistedGrant>, libraryTrees: Set<String>, needed: Set<String>, now: Long): List<String> {
        val neededTrees = needed.mapNotNullTo(HashSet()) { treeUriOf(it.toUri())?.toString() }
        return grants
            .filter { grant -> now - grant.persistedAt >= GRACE_MS }
            .map { it.uri }
            .filter { uri -> uri !in libraryTrees && uri !in needed && uri !in neededTrees }
    }

    /** Applies [toRelease] to the grants [resolver] holds. Blocking; call off the main thread. */
    fun releaseUnneeded(resolver: ContentResolver, libraryTrees: Set<String>, needed: Set<String>, now: Long): Int {
        val permissions = resolver.persistedUriPermissions
        val grants = permissions.map { PersistedGrant(it.uri.toString(), it.persistedTime) }
        val release = toRelease(grants, libraryTrees, needed, now).toSet()
        var released = 0
        permissions.filter { it.uri.toString() in release }.forEach { permission ->
            var flags = 0
            if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                resolver.releasePersistableUriPermission(permission.uri, flags)
                released++
            } catch (_: SecurityException) {
                // Already gone.
            }
        }
        return released
    }
}
