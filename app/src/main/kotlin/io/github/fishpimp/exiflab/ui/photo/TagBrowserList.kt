package io.github.fishpimp.exiflab.ui.photo

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.component.Choice
import io.github.fishpimp.exiflab.designsystem.component.ChoiceRow
import io.github.fishpimp.exiflab.designsystem.component.EmptyState
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.model.SensitivityCategory

/** What the tag browser asks of its host. */
@Immutable
class TagBrowserCallbacks(
    val onQueryChange: (String) -> Unit = {},
    val onModeChange: (ValueMode) -> Unit = {},
    val onToggleSection: (String) -> Unit = {},
    val onSetAllExpanded: (Boolean) -> Unit = {},
    val onClearFilter: () -> Unit = {},
    val onCopyValue: (TagRow) -> Unit = {},
    val onCopyNameAndValue: (TagRow) -> Unit = {},
    val onShowDetails: (TagRow) -> Unit = {},
)

/** Lazy list keys of the browser's fixed items, for scrolling to it. */
const val BROWSER_HEADER_KEY = "browser-header"

private val SectionCorner = 20.dp
private val RowCorner = 4.dp
private val RowGap = 2.dp
private const val COLLAPSED_VALUE_LINES = 8

/**
 * The searchable, grouped tag list: a header with counts, a sticky search field, the
 * readable/raw switch, then one collapsible section per directory and a final warnings section.
 * Rows come precomputed from [TagBrowserBuilder]; nothing heavy happens here.
 *
 * @param gutter horizontal padding of every item; the list itself has none so the sticky search
 *   field can span its full width.
 */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.tagBrowser(
    browser: TagBrowser?,
    query: String,
    mode: ValueMode,
    filter: SensitivityCategory?,
    filterCount: Int,
    callbacks: TagBrowserCallbacks,
    gutter: Dp,
) {
    item(key = BROWSER_HEADER_KEY, contentType = "browser-header") {
        BrowserHeader(browser, callbacks.onSetAllExpanded, Modifier.padding(horizontal = gutter).padding(top = 8.dp))
    }
    stickyHeader(key = "browser-search", contentType = "browser-search") {
        Surface(color = MaterialTheme.colorScheme.surface) {
            SearchField(query, callbacks.onQueryChange, Modifier.padding(horizontal = gutter, vertical = 8.dp))
        }
    }
    item(key = "browser-controls", contentType = "browser-controls") {
        BrowserControls(mode, filter, filterCount, callbacks, Modifier.padding(horizontal = gutter).padding(bottom = 4.dp))
    }
    when {
        browser == null -> item(key = "browser-loading", contentType = "browser-loading") {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
        }
        browser.isEmptyResult -> item(key = "browser-empty", contentType = "browser-empty") {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    shape = MaterialShapes.Clover8Leaf,
                    title = stringResource(R.string.photo_no_matches_title),
                    body = stringResource(R.string.photo_no_matches_body, query.trim()),
                    actionLabel = stringResource(R.string.photo_search_clear),
                    onAction = { callbacks.onQueryChange("") },
                )
            }
        }
        else -> items(browser.rows, key = { it.key }, contentType = { it.contentType }) { row ->
            val itemModifier = Modifier.animateItem().padding(horizontal = gutter)
            when (row) {
                is SectionHeaderRow -> SectionHeader(row, callbacks.onToggleSection, itemModifier)
                is TagRow -> TagRowItem(row, mode, callbacks, itemModifier)
                is WarningRow -> WarningRowItem(row, itemModifier)
            }
        }
    }
}

private val BrowserRow.contentType: String
    get() = when (this) {
        is SectionHeaderRow -> "section"
        is TagRow -> "tag"
        is WarningRow -> "warning"
    }

@Composable
private fun BrowserHeader(browser: TagBrowser?, onSetAllExpanded: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ViewerSectionTitle(stringResource(R.string.photo_tags_title))
            if (browser != null) {
                val summary = if (browser.isFiltering) {
                    pluralStringResource(R.plurals.photo_tags_matching, browser.totalTags, browser.matchingTags, browser.totalTags)
                } else {
                    listOf(
                        pluralStringResource(R.plurals.photo_tag_count, browser.totalTags, browser.totalTags),
                        pluralStringResource(R.plurals.photo_group_count, browser.sectionCount, browser.sectionCount),
                    ).joinToString(" · ")
                }
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (browser != null && browser.rows.any { it is SectionHeaderRow }) {
            val expandAll = !browser.anyExpanded
            IconButton(onClick = { onSetAllExpanded(expandAll) }) {
                Icon(
                    if (expandAll) Icons.Rounded.UnfoldMore else Icons.Rounded.UnfoldLess,
                    contentDescription = stringResource(if (expandAll) R.string.photo_expand_all else R.string.photo_collapse_all),
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.photo_search_hint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.photo_search_clear))
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedIndicatorColor = container,
            unfocusedIndicatorColor = container,
        ),
    )
}

@Composable
private fun BrowserControls(
    mode: ValueMode,
    filter: SensitivityCategory?,
    filterCount: Int,
    callbacks: TagBrowserCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceRow(
            choices = listOf(
                Choice(ValueMode.Readable, stringResource(R.string.photo_mode_readable), Icons.AutoMirrored.Rounded.TextSnippet),
                Choice(ValueMode.Raw, stringResource(R.string.photo_mode_raw), Icons.Rounded.DataObject),
            ),
            selected = mode,
            onSelect = callbacks.onModeChange,
        )
        if (filter != null) ActiveFilter(filter, filterCount, callbacks.onClearFilter)
    }
}

/** The sensitivity filter in effect, with a way out. Icon and text, never color alone. */
@Composable
private fun ActiveFilter(filter: SensitivityCategory, count: Int, onClear: () -> Unit) {
    val colors = ExifLabTheme.extendedColors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = colors.sensitiveContainer, contentColor = colors.onSensitiveContainer) {
            Row(
                Modifier.heightIn(min = 40.dp).padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(filter.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(filter.labelRes), style = MaterialTheme.typography.labelLarge)
                Text(pluralStringResource(R.plurals.photo_tag_count, count, count), style = MaterialTheme.typography.labelMedium)
            }
        }
        TextButton(onClick = onClear) {
            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.photo_filter_clear), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SectionHeader(row: SectionHeaderRow, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    val badge = sectionBadge(row.group)
    val isWarnings = row.group == null
    val name = if (isWarnings) stringResource(R.string.photo_warnings_title) else row.name
    val count = if (isWarnings) {
        pluralStringResource(R.plurals.photo_warning_count, row.itemCount, row.itemCount)
    } else {
        pluralStringResource(R.plurals.photo_tag_count, row.itemCount, row.itemCount)
    }
    val subtitle = row.matchCount?.let { pluralStringResource(R.plurals.photo_match_count, it, it) } ?: count
    val bottomCorner by animateDpAsState(if (row.expanded) RowCorner else SectionCorner, label = "sectionCorner")
    val chevron by animateFloatAsState(if (row.expanded) 180f else 0f, label = "sectionChevron")
    val shape = RoundedCornerShape(topStart = SectionCorner, topEnd = SectionCorner, bottomStart = bottomCorner, bottomEnd = bottomCorner)
    val state = stringResource(if (row.expanded) R.string.photo_section_expanded else R.string.photo_section_collapsed)
    val action = stringResource(if (row.expanded) R.string.photo_section_collapse else R.string.photo_section_expand)
    Row(
        modifier = modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = action) { onToggle(row.sectionId) }
            .semantics {
                heading()
                stateDescription = state
            }
            .heightIn(min = 64.dp)
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ShapeIcon(icon = badge.icon, shape = badge.shape, size = 40.dp, containerColor = badge.container, contentColor = badge.content)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(chevron),
        )
    }
}

private fun rowShape(isLast: Boolean): RoundedCornerShape =
    if (isLast) {
        RoundedCornerShape(topStart = RowCorner, topEnd = RowCorner, bottomStart = SectionCorner, bottomEnd = SectionCorner)
    } else {
        RoundedCornerShape(RowCorner)
    }

@Composable
private fun TagRowItem(row: TagRow, mode: ValueMode, callbacks: TagBrowserCallbacks, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var isLong by remember(row.value) { mutableStateOf(false) }
    val highlight = SpanStyle(
        background = MaterialTheme.colorScheme.tertiaryContainer,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    val name = remember(row.tag.name, row.nameMatches, highlight) { highlighted(row.tag.name, row.nameMatches, highlight) }
    val value = remember(row.value, row.valueMatches, highlight) { highlighted(row.value, row.valueMatches, highlight) }
    val sensitivity = row.tag.sensitivity
    val raw = mode == ValueMode.Raw

    val copyValue = stringResource(R.string.photo_copy_value)
    val copyBoth = stringResource(R.string.photo_copy_name_value)
    val details = stringResource(R.string.photo_tag_details)
    val toggleLength = stringResource(if (expanded) R.string.photo_show_less else R.string.photo_show_more)
    val sensitiveText = sensitivity?.let { stringResource(R.string.photo_tag_sensitive, stringResource(it.labelRes)) }
    val description = listOfNotNull(row.tag.name, row.tag.hexId?.takeIf { row.showId }, row.value, sensitiveText).joinToString(", ")

    Row(
        modifier = modifier
            .padding(top = RowGap)
            .fillMaxWidth()
            .clip(rowShape(row.isLast))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                onClickLabel = details,
                onLongClickLabel = copyValue,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    callbacks.onCopyValue(row)
                },
                onClick = { callbacks.onShowDetails(row) },
            )
            .clearAndSetSemantics {
                contentDescription = description
                customActions = listOfNotNull(
                    CustomAccessibilityAction(copyValue) {
                        callbacks.onCopyValue(row)
                        true
                    },
                    CustomAccessibilityAction(copyBoth) {
                        callbacks.onCopyNameAndValue(row)
                        true
                    },
                    if (isLong) {
                        CustomAccessibilityAction(toggleLength) {
                            expanded = !expanded
                            true
                        }
                    } else {
                        null
                    },
                )
            }
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = if (isLong) 2.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val hexId = row.tag.hexId
                if (row.showId && hexId != null) {
                    Text(
                        remember(hexId, row.idMatches, highlight) { highlighted(hexId, row.idMatches, highlight) },
                        style = ExifLabTheme.extendedTypography.monoSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                value,
                style = if (raw) ExifLabTheme.extendedTypography.monoMedium else MaterialTheme.typography.bodyLarge,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_VALUE_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow) isLong = true },
                modifier = Modifier.padding(top = 2.dp),
            )
            row.otherMatch?.let { other ->
                val label = stringResource(if (other.mode == ValueMode.Raw) R.string.photo_value_raw else R.string.photo_value_readable)
                Text(
                    remember(other, highlight, label) {
                        buildAnnotatedString {
                            append(label)
                            append(": ")
                            append(highlighted(other.text, other.matches, highlight))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (isLong) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(toggleLength)
                }
            }
        }
        if (sensitivity != null) SensitiveMark(sensitivity, Modifier.padding(top = 2.dp))
    }
}

/** A small sensitive-colored icon marking a privacy-sensitive tag; the row's description says it in words. */
@Composable
private fun SensitiveMark(category: SensitivityCategory, modifier: Modifier = Modifier) {
    val colors = ExifLabTheme.extendedColors
    Box(
        modifier.size(28.dp).clip(CircleShape).background(colors.sensitiveContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(category.icon, contentDescription = null, tint = colors.onSensitiveContainer, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun WarningRowItem(row: WarningRow, modifier: Modifier = Modifier) {
    val highlight = SpanStyle(
        background = MaterialTheme.colorScheme.tertiaryContainer,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    Row(
        modifier = modifier
            .padding(top = RowGap)
            .fillMaxWidth()
            .clip(rowShape(row.isLast))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        Text(
            remember(row.text, row.matches, highlight) { highlighted(row.text, row.matches, highlight) },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** [text] with [ranges] styled as search hits. */
internal fun highlighted(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { range ->
            val end = (range.last + 1).coerceAtMost(text.length)
            if (range.first in 0 until end) addStyle(style, range.first, end)
        }
    }
}
