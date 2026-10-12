package io.github.fishpimp.exiflab.ui.selection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toSize
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.data.batch.BatchKind
import io.github.fishpimp.exiflab.data.photos.PhotoRef
import io.github.fishpimp.exiflab.data.photos.formatLabel
import io.github.fishpimp.exiflab.ui.batch.icon
import io.github.fishpimp.exiflab.ui.batch.labelRes
import io.github.fishpimp.exiflab.ui.components.FormatBadge
import io.github.fishpimp.exiflab.ui.components.PhotoThumbnail
import io.github.fishpimp.exiflab.ui.components.photoDescription
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min

/**
 * A photo grid cell that supports multi-select: a tap opens the photo, a long press (or the
 * TalkBack action "Select") starts selection mode; in selection mode a tap toggles it. Selected
 * tiles shrink a little and show a filled check, so the state never relies on color alone, and
 * TalkBack reads "Selected" or "Not selected".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectablePhotoTile(
    ref: PhotoRef,
    selectionActive: Boolean,
    selected: Boolean,
    onOpen: () -> Unit,
    onBegin: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = photoDescription(ref)
    val selectLabel = stringResource(R.string.selection_select)
    val openLabel = stringResource(R.string.selection_open)
    val state = stringResource(if (selected) R.string.selection_state_selected else R.string.selection_state_not_selected)
    val inset by animateDpAsState(if (selected) 10.dp else 0.dp, label = "selectedInset")
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClickLabel = if (selectionActive) null else openLabel,
                onLongClickLabel = selectLabel,
                onLongClick = onBegin,
                onClick = if (selectionActive) onToggle else onOpen,
            )
            .clearAndSetSemantics {
                contentDescription = description
                if (selectionActive) {
                    role = Role.Checkbox
                    this.selected = selected
                    stateDescription = state
                } else {
                    role = Role.Button
                    customActions = listOf(CustomAccessibilityAction(selectLabel) { onBegin(); true })
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(inset)
                .clip(shape),
        ) {
            PhotoThumbnail(ref = ref, contentDescription = null, showFormatLabel = false, modifier = Modifier.fillMaxSize())
            ref.formatLabel?.let { label -> FormatBadge(label, Modifier.align(Alignment.BottomStart).padding(6.dp)) }
            if (!ref.writable) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.padding(4.dp).size(16.dp))
                }
            }
        }
        if (selected) Box(Modifier.matchParentSize().border(3.dp, MaterialTheme.colorScheme.primary, shape))
        AnimatedVisibility(
            visible = selectionActive,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        ) {
            SelectionMark(selected)
        }
    }
}

/** A filled check when selected, an empty ring otherwise, on a contrasting disc. */
@Composable
private fun SelectionMark(selected: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f),
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * Drag-to-select on a photo grid: long press a photo and drag; every photo between it and the
 * one under the finger is selected, and the grid scrolls when the finger nears an edge. Item keys
 * must be photo URIs; other items (folders, headers) are ignored.
 *
 * @param photos the grid's photos in order.
 */
@Composable
fun Modifier.dragToSelect(
    gridState: LazyGridState,
    photos: List<PhotoRef>,
    selection: PhotoSelection,
    actions: SelectionActions,
): Modifier {
    val index = remember(photos) { HashMap<String, Int>(photos.size * 2).apply { photos.forEachIndexed { i, ref -> put(ref.uri, i) } } }
    val currentPhotos by rememberUpdatedState(photos)
    val currentIndex by rememberUpdatedState(index)
    val currentSelection by rememberUpdatedState(selection)
    val currentActions by rememberUpdatedState(actions)
    val autoScrollSpeed = remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }

    LaunchedEffect(autoScrollSpeed.floatValue) {
        val speed = autoScrollSpeed.floatValue
        if (speed != 0f) {
            while (isActive) {
                gridState.scrollBy(speed / 4)
                delay(16)
            }
        }
    }

    return pointerInput(gridState) {
        var start: Int? = null
        var current: Int? = null
        var base: Set<String> = emptySet()

        fun photoAt(offset: Offset): Int? {
            val hit = offset.round()
            val item = gridState.layoutInfo.visibleItemsInfo.firstOrNull { IntRect(it.offset, it.size).contains(hit) } ?: return null
            return (item.key as? String)?.let { currentIndex[it] }
        }

        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val at = photoAt(offset) ?: return@detectDragGesturesAfterLongPress
                val uri = currentPhotos[at].uri
                base = currentSelection.selected + uri
                start = at
                current = at
                currentActions.onBegin(uri)
            },
            onDragEnd = {
                start = null
                autoScrollSpeed.floatValue = 0f
            },
            onDragCancel = {
                start = null
                autoScrollSpeed.floatValue = 0f
            },
            onDrag = { change, _ ->
                val from = start ?: return@detectDragGesturesAfterLongPress
                val height = size.toSize().height
                val y = change.position.y
                autoScrollSpeed.floatValue = when {
                    y > height - threshold -> y - (height - threshold)
                    y < threshold -> y - threshold
                    else -> 0f
                }
                val at = photoAt(change.position) ?: return@detectDragGesturesAfterLongPress
                if (at != current) {
                    current = at
                    val range = currentPhotos.subList(min(from, at), max(from, at) + 1).map { it.uri }
                    currentActions.onDragRange(base, range)
                }
            },
        )
    }
}

/**
 * The batch actions for a selection, as an expressive floating toolbar of icon buttons with
 * tooltips (their names are also their TalkBack labels). Disabled while nothing is selected.
 *
 * @param onExport exports the selection's metadata; the button is left out when null.
 */
@Composable
fun BatchActionsToolbar(
    visible: Boolean,
    enabled: Boolean,
    onAction: (BatchKind) -> Unit,
    modifier: Modifier = Modifier,
    onExport: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            modifier = Modifier.padding(FloatingToolbarDefaults.ScreenOffset),
        ) {
            BatchKind.entries.forEach { kind ->
                ToolbarAction(icon = kind.icon, label = stringResource(kind.labelRes), enabled = enabled) { onAction(kind) }
            }
            if (onExport != null) {
                ToolbarAction(icon = Icons.Rounded.FileDownload, label = stringResource(R.string.export_action), enabled = enabled, onClick = onExport)
            }
        }
    }
}

@Composable
private fun ToolbarAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** Room under the last row of a grid for the floating batch toolbar. */
val SelectionBottomPadding = 120.dp

/**
 * Top bar actions of a selectable grid. Outside selection mode: "Select" (empty, or every photo
 * with [enterSelectsAll]); in it: "Select all" and "Clear".
 */
@Composable
fun RowScope.SelectionBarActions(selection: PhotoSelection, actions: SelectionActions, total: Int, enterSelectsAll: Boolean) {
    if (selection.active) {
        IconButton(onClick = actions.onSelectAll, enabled = selection.count < total) {
            Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.selection_select_all))
        }
        IconButton(onClick = actions.onClear, enabled = selection.count > 0) {
            Icon(Icons.Rounded.Deselect, contentDescription = stringResource(R.string.selection_clear))
        }
    } else if (enterSelectsAll) {
        IconButton(onClick = actions.onSelectAll) {
            Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.selection_select_all))
        }
    } else {
        IconButton(onClick = actions.onEnter) {
            Icon(Icons.Rounded.Checklist, contentDescription = stringResource(R.string.selection_start))
        }
    }
}

/** "3 selected", for the top bar in selection mode. */
@Composable
fun selectionTitle(selection: PhotoSelection): String =
    pluralStringResource(R.plurals.selection_count, selection.count, selection.count)
