package io.github.fishpimp.exiflab.data.save

import io.github.fishpimp.exiflab.data.history.EditMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * A save the editor just finished, waiting for the photo's viewer to reload and to offer Undo.
 *
 * @property photoUri the photo the viewer shows: the edited photo, or the new copy.
 * @property skipped how many changes the file format could not hold.
 * @property canUndo true for in-place and sidecar saves, which History can restore.
 */
data class SavedEditNotice(
    val recordId: String,
    val photoUri: String,
    val mode: EditMode,
    val skipped: Int = 0,
    val canUndo: Boolean = mode != EditMode.Copy,
)

/**
 * Hands finished saves from the editor to the viewer. A notice stays until a viewer of its photo
 * consumes it, so a viewer that is created after the save (a new copy) still sees it.
 */
class SavedEditNotices {
    private val pending = MutableStateFlow<List<SavedEditNotice>>(emptyList())

    fun post(notice: SavedEditNotice) = pending.update { list -> list.filterNot { it.photoUri == notice.photoUri } + notice }

    /** The newest unconsumed notice for [photoUri], or null. */
    fun forPhoto(photoUri: String): Flow<SavedEditNotice?> =
        pending.map { list -> list.lastOrNull { it.photoUri == photoUri } }.distinctUntilChanged()

    fun consume(notice: SavedEditNotice) = pending.update { it - notice }
}
