package io.github.fishpimp.exiflab.ui.selection

import androidx.compose.runtime.Immutable
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Multi-select in a photo grid, by photo URI. [active] is selection mode: taps toggle instead of
 * opening, and the batch toolbar shows. Selecting nothing keeps the mode on until it is left.
 */
@Immutable
data class PhotoSelection(val active: Boolean = false, val selected: Set<String> = emptySet()) {
    val count: Int get() = selected.size

    fun isSelected(uri: String): Boolean = uri in selected

    /** Enters selection mode with nothing selected yet (the top bar's "Select"). */
    fun enter(): PhotoSelection = copy(active = true)

    /** Enters selection mode with [uri] selected (long press); selecting again keeps it selected. */
    fun begin(uri: String): PhotoSelection = PhotoSelection(active = true, selected = selected + uri)

    /** Tap in selection mode. */
    fun toggle(uri: String): PhotoSelection =
        copy(active = true, selected = if (uri in selected) selected - uri else selected + uri)

    fun selectAll(uris: Collection<String>): PhotoSelection = PhotoSelection(active = true, selected = selected + uris)

    fun clear(): PhotoSelection = copy(selected = emptySet())

    /** Leaves selection mode (back, close, after starting a batch). */
    fun exit(): PhotoSelection = PhotoSelection()

    /**
     * Drag-to-select: [base] is what was selected when the drag began; the photos between the
     * drag's first and current photo ([range]) are added to it, so dragging back shrinks the range again.
     */
    fun withRange(base: Set<String>, range: Collection<String>): PhotoSelection = PhotoSelection(active = true, selected = base + range)

    /** Drops photos that are no longer listed, e.g. deleted while the folder was open. */
    fun retain(available: Set<String>): PhotoSelection =
        if (selected.all { it in available }) this else copy(selected = selected.filterTo(HashSet()) { it in available })

    /** The selected photos, in [photos] order. */
    fun selectedIn(photos: List<PhotoRef>): List<PhotoRef> = photos.filter { it.uri in selected }
}

/** Holds a [PhotoSelection] for a view model, so it survives configuration changes. */
class PhotoSelectionHolder {
    private val mutable = MutableStateFlow(PhotoSelection())
    val state: StateFlow<PhotoSelection> = mutable.asStateFlow()

    fun update(transform: (PhotoSelection) -> PhotoSelection) = mutable.update(transform)
}

/** Everything a grid in selection mode can ask for. Defaults do nothing, for screenshot tests. */
@Immutable
data class SelectionActions(
    val onEnter: () -> Unit = {},
    val onBegin: (String) -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onExit: () -> Unit = {},
    val onDragRange: (base: Set<String>, range: Collection<String>) -> Unit = { _, _ -> },
)

/** Builds [SelectionActions] that update [holder]; [allUris] lists the grid's photos for "Select all". */
fun PhotoSelectionHolder.actions(allUris: () -> Collection<String>) = SelectionActions(
    onEnter = { update { it.enter() } },
    onBegin = { uri -> update { it.begin(uri) } },
    onToggle = { uri -> update { it.toggle(uri) } },
    onSelectAll = { update { it.selectAll(allUris()) } },
    onClear = { update { it.clear() } },
    onExit = { update { it.exit() } },
    onDragRange = { base, range -> update { it.withRange(base, range) } },
)
