package io.github.fishpimp.exiflab.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fishpimp.exiflab.R
import io.github.fishpimp.exiflab.designsystem.component.ShapeIcon
import io.github.fishpimp.exiflab.designsystem.theme.ExifLabTheme
import io.github.fishpimp.exiflab.metadata.edit.EditableField
import io.github.fishpimp.exiflab.metadata.edit.FieldDiff
import io.github.fishpimp.exiflab.metadata.edit.FieldKind
import io.github.fishpimp.exiflab.ui.edit.time.PickDateTimeDialogs
import io.github.fishpimp.exiflab.ui.history.DiffKindChip
import io.github.fishpimp.exiflab.ui.history.labelRes
import java.time.LocalDateTime

/** What the "All fields" list can ask for. */
@Immutable
data class FieldCallbacks(
    val onQueryChange: (String) -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onDraftChange: (String) -> Unit = {},
    val onApply: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onRevert: (String) -> Unit = {},
    val onAdd: (String) -> Unit = {},
)

/**
 * "All fields" as lazy list items: a header with search and "Add field", then the shown fields
 * (common ones first, the rest grouped), each expanding in place into a type-aware editor.
 */
fun LazyListScope.allFieldsItems(
    fields: FieldsState,
    editor: FieldEditor?,
    query: String,
    callbacks: FieldCallbacks,
    gutter: Dp,
) {
    item(key = "fields-header", contentType = "fields-header") {
        FieldsHeader(fields, query, callbacks, Modifier.padding(horizontal = gutter))
    }
    when (fields) {
        FieldsState.Loading -> Unit
        FieldsState.Unavailable -> item(key = "fields-unavailable") {
            EditorBanner(
                icon = Icons.Rounded.Tune,
                text = stringResource(R.string.edit_fields_unavailable),
                modifier = Modifier.padding(horizontal = gutter),
            )
        }
        is FieldsState.Ready -> {
            if (fields.sections.isEmpty()) {
                item(key = "fields-empty") {
                    Text(
                        stringResource(if (fields.searching) R.string.edit_fields_no_match else R.string.edit_fields_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = gutter + 4.dp, vertical = 8.dp),
                    )
                }
            }
            fields.sections.forEach { section ->
                val sectionKey = when (val kind = section.kind) {
                    FieldSectionKind.Common -> "common"
                    is FieldSectionKind.Group -> kind.group.name
                }
                item(key = "section-$sectionKey", contentType = "section") {
                    Text(
                        when (val kind = section.kind) {
                            FieldSectionKind.Common -> stringResource(R.string.edit_fields_common)
                            is FieldSectionKind.Group -> stringResource(kind.group.labelRes)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = gutter + 4.dp)
                            .padding(top = 20.dp, bottom = 6.dp)
                            .semantics { heading() },
                    )
                }
                items(section.rows, key = { "field-${it.field.id}" }, contentType = { "field" }) { row ->
                    val open = editor?.fieldId == row.field.id
                    Column(Modifier.padding(horizontal = gutter - 8.dp)) {
                        if (open) {
                            FieldEditorCard(row, editor!!, callbacks)
                        } else {
                            FieldListRow(row, onClick = { callbacks.onEdit(row.field.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldsHeader(fields: FieldsState, query: String, callbacks: FieldCallbacks, modifier: Modifier) {
    var adding by rememberSaveable { mutableStateOf(false) }
    Column(modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                icon = Icons.Rounded.Tune,
                shape = MaterialShapes.Clover8Leaf,
                size = 48.dp,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.edit_fields_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(R.string.edit_fields_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (fields is FieldsState.Ready) {
            OutlinedTextField(
                value = query,
                onValueChange = callbacks.onQueryChange,
                label = { Text(stringResource(R.string.edit_fields_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { callbacks.onQueryChange("") }) {
                            Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.edit_fields_search_clear))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { adding = true },
                enabled = fields.absent.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.edit_fields_add), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
            if (adding) {
                AddFieldDialog(fields.absent, onAdd = { adding = false; callbacks.onAdd(it) }, onDismiss = { adding = false })
            }
        }
    }
}

/** A field as one row: label, its value (or what it will become) and a change marker. */
@Composable
private fun FieldListRow(row: FieldRow, onClick: () -> Unit) {
    val editLabel = stringResource(R.string.edit_fields_edit_action)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = editLabel, role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.field.label, style = MaterialTheme.typography.bodyLarge)
            val shown = row.shown
            Text(
                shown?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.edit_fields_not_set),
                style = if (shown != null) ExifLabTheme.extendedTypography.monoMedium else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
            )
        }
        row.pending?.let { pending ->
            val kind = when {
                pending.value == null -> FieldDiff.Kind.Removed
                row.current == null -> FieldDiff.Kind.Added
                else -> FieldDiff.Kind.Changed
            }
            DiffKindChip(kind)
        }
        Icon(Icons.Rounded.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The inline editor of one field: a type-aware input, its validation message and the actions. */
@Composable
private fun FieldEditorCard(row: FieldRow, editor: FieldEditor, callbacks: FieldCallbacks) {
    val field = row.field
    val error = editor.error?.let { validationMessage(it, field) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(field.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (row.current != null) {
                Text(
                    stringResource(R.string.edit_fields_current, row.current),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FieldInput(field, editor.draft, error, callbacks)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (row.pending != null) {
                    TextButton(onClick = { callbacks.onRevert(field.id); callbacks.onCancel() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(stringResource(R.string.edit_fields_revert), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
                // Removing makes sense while the field will still have a value; an added field is undone instead.
                if (row.current != null && (row.pending == null || row.pending.value != null)) {
                    TextButton(
                        onClick = { callbacks.onRemove(field.id) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(stringResource(R.string.edit_fields_remove), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
                TextButton(onClick = callbacks.onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = callbacks.onApply, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(stringResource(R.string.edit_apply), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
    }
}

@Composable
private fun FieldInput(field: EditableField, draft: String, error: String?, callbacks: FieldCallbacks) {
    val supporting: @Composable (() -> Unit)? = error?.let {
        { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
    val label: @Composable () -> Unit = { Text(field.label) }
    when (val kind = field.kind) {
        is FieldKind.Text -> OutlinedTextField(
            value = draft,
            onValueChange = callbacks.onDraftChange,
            label = label,
            isError = error != null,
            supportingText = supporting ?: kind.maxLength?.let { max -> { Text(stringResource(R.string.edit_fields_length, draft.length, max)) } },
            singleLine = !kind.multiline,
            minLines = if (kind.multiline) 3 else 1,
            keyboardOptions = KeyboardOptions(imeAction = if (kind.multiline) ImeAction.Default else ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { callbacks.onApply() }),
            modifier = Modifier.fillMaxWidth(),
        )
        is FieldKind.Integer -> OutlinedTextField(
            value = draft,
            onValueChange = callbacks.onDraftChange,
            label = label,
            isError = error != null,
            supportingText = supporting ?: { Text(stringResource(R.string.edit_error_out_of_range_between, kind.min.toString(), kind.max.toString())) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { callbacks.onApply() }),
            textStyle = ExifLabTheme.extendedTypography.monoLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        is FieldKind.Number -> OutlinedTextField(
            value = draft,
            onValueChange = callbacks.onDraftChange,
            label = label,
            isError = error != null,
            supportingText = supporting ?: { Text(stringResource(R.string.edit_fields_number_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { callbacks.onApply() }),
            textStyle = ExifLabTheme.extendedTypography.monoLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldKind.DateTime -> DateTimeInput(draft, label, error, supporting, callbacks)
        is FieldKind.Choice -> ChoiceInput(kind, draft, label, error, supporting, callbacks)
        FieldKind.TextList -> KeywordInput(field, draft, error, supporting, callbacks)
    }
}

@Composable
private fun DateTimeInput(
    draft: String,
    label: @Composable () -> Unit,
    error: String?,
    supporting: @Composable (() -> Unit)?,
    callbacks: FieldCallbacks,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = draft,
        onValueChange = callbacks.onDraftChange,
        label = label,
        placeholder = { Text(DATE_TIME_EXAMPLE) },
        isError = error != null,
        supportingText = supporting ?: { Text(stringResource(R.string.edit_fields_date_hint, DATE_TIME_EXAMPLE)) },
        trailingIcon = {
            IconButton(onClick = { picking = true }) {
                Icon(Icons.Rounded.CalendarMonth, contentDescription = stringResource(R.string.edit_fields_pick_date))
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { callbacks.onApply() }),
        textStyle = ExifLabTheme.extendedTypography.monoLarge,
        modifier = Modifier.fillMaxWidth(),
    )
    if (picking) {
        val initial = remember(draft) { parseFieldDateTime(draft) ?: LocalDateTime.now().withNano(0) }
        PickDateTimeDialogs(
            initial = initial,
            onPick = {
                picking = false
                callbacks.onDraftChange(FieldDateTimeFormat.format(it))
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ChoiceInput(
    kind: FieldKind.Choice,
    draft: String,
    label: @Composable () -> Unit,
    error: String?,
    supporting: @Composable (() -> Unit)?,
    callbacks: FieldCallbacks,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = kind.options.firstOrNull { it.value == draft }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.label ?: draft,
            onValueChange = {},
            readOnly = true,
            label = label,
            isError = error != null,
            supportingText = supporting,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            kind.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        callbacks.onDraftChange(option.value)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** Keywords as removable chips plus a field to add more; the draft is the list joined with [LIST_SEPARATOR]. */
@Composable
private fun KeywordInput(field: EditableField, draft: String, error: String?, supporting: @Composable (() -> Unit)?, callbacks: FieldCallbacks) {
    val items = remember(draft) { splitList(draft) }
    var entry by rememberSaveable(field.id) { mutableStateOf("") }
    fun add() {
        val added = splitList(entry)
        if (added.isNotEmpty()) callbacks.onDraftChange((items + added).distinct().joinToString(LIST_SEPARATOR))
        entry = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { item ->
                    val removeLabel = stringResource(R.string.edit_fields_keyword_remove, item)
                    InputChip(
                        selected = false,
                        onClick = { callbacks.onDraftChange((items - item).joinToString(LIST_SEPARATOR)) },
                        label = { Text(item) },
                        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = removeLabel, modifier = Modifier.size(InputChipDefaults.AvatarSize)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it },
            label = { Text(stringResource(R.string.edit_fields_keyword_add)) },
            isError = error != null,
            supportingText = supporting ?: { Text(stringResource(R.string.edit_fields_keyword_hint)) },
            trailingIcon = {
                IconButton(onClick = ::add, enabled = entry.isNotBlank()) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.edit_fields_keyword_add))
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** "Add field": every catalog field the file does not have, searchable. */
@Composable
private fun AddFieldDialog(absent: List<EditableField>, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(absent, query) {
        val needle = query.trim()
        if (needle.isEmpty()) absent else absent.filter { it.label.contains(needle, ignoreCase = true) || it.id.contains(needle, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
        title = { Text(stringResource(R.string.edit_fields_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.edit_fields_search)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { field ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable(role = Role.Button) { onAdd(field.id) }
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(field.label, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(field.group.labelRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
