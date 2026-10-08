package io.github.fishpimp.exiflab.core.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Every navigation destination of the app (type-safe Navigation Compose routes). Lists of images are passed as a
 * [sessionId] created with SessionRepository, never as arguments, so hundreds of URIs survive process death.
 */
public sealed interface Route

/** Top-level: the photo library (gallery grid, entry points, quick actions). */
@Serializable
public data object PhotosRoute : Route

/** Top-level: edit history with restore. */
@Serializable
public data object HistoryRoute : Route

/** Top-level: settings. */
@Serializable
public data object SettingsRoute : Route

/** Images inside a SAF folder the user granted. */
@Serializable
public data class FolderRoute(val treeUri: String) : Route

/** Metadata viewer and single-image editor; swipes between the images of the session. */
@Serializable
public data class ViewerRoute(val sessionId: String, val index: Int = 0) : Route

/**
 * Full-screen location picker. The result (a GeoPoint as JSON, or "remove") is delivered to the previous back stack
 * entry's SavedStateHandle under [LocationPickerRoute.RESULT_KEY].
 */
@Serializable
public data class LocationPickerRoute(
    val initialLatitude: Double? = null,
    val initialLongitude: Double? = null,
    /** Session whose photos can be used for "copy position from another photo". */
    val sessionId: String? = null,
    val title: String? = null,
) : Route {
    public companion object {
        public const val RESULT_KEY: String = "location_picker_result"
    }
}

/** Batch editor for the images of a session. */
@Serializable
public data class BatchRoute(val sessionId: String) : Route

/** Progress and results of a running or finished batch. */
@Serializable
public data class BatchProgressRoute(val batchId: String) : Route

/** Share without metadata. */
@Serializable
public data class ShareCleanRoute(val sessionId: String) : Route

/** Export metadata as CSV, JSON or PDF. */
@Serializable
public data class ExportRoute(val sessionId: String) : Route

@Serializable
public data class HistoryDetailRoute(val entryId: Long) : Route

@Serializable
public data object PrivacyRoute : Route

@Serializable
public data object AboutRoute : Route

@Serializable
public data object MapSettingsRoute : Route
