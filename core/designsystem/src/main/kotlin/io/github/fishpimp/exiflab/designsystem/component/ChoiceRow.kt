package io.github.fishpimp.exiflab.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

data class Choice<T>(val value: T, val label: String, val icon: ImageVector? = null)

/**
 * Single-choice connected button group (Material 3 Expressive). Behaves like a radio group
 * for accessibility services. When the labels do not fit side by side (narrow windows,
 * large font sizes, long translations) the options stack vertically instead of truncating.
 */
@Composable
fun <T> ChoiceRow(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val requiredWidth = remember(choices, labelStyle, density, measurer) {
            with(density) {
                choices.sumOf { choice ->
                    val text = measurer.measure(choice.label, labelStyle, maxLines = 1).size.width.toDp()
                    val icon = if (choice.icon != null) 18.dp + ToggleButtonDefaults.IconSpacing else 0.dp
                    // Horizontal content padding of a small toggle button on both sides.
                    (text + icon + 32.dp).value.toDouble()
                }.dp + ButtonGroupDefaults.ConnectedSpaceBetween * (choices.size - 1)
            }
        }
        if (requiredWidth <= maxWidth) {
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                choices.forEachIndexed { index, choice ->
                    val shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                    ChoiceButton(choice, choice.value == selected, enabled, shapes, Modifier.weight(1f), onSelect)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                choices.forEachIndexed { index, choice ->
                    val big = 20.dp
                    val small = 6.dp
                    val shape = when (index) {
                        0 -> RoundedCornerShape(topStart = big, topEnd = big, bottomStart = small, bottomEnd = small)
                        choices.lastIndex -> RoundedCornerShape(topStart = small, topEnd = small, bottomStart = big, bottomEnd = big)
                        else -> RoundedCornerShape(small)
                    }
                    val shapes = ToggleButtonShapes(
                        shape = shape,
                        pressedShape = RoundedCornerShape(small),
                        checkedShape = RoundedCornerShape(big),
                    )
                    ChoiceButton(choice, choice.value == selected, enabled, shapes, Modifier.fillMaxWidth(), onSelect)
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceButton(
    choice: Choice<T>,
    checked: Boolean,
    enabled: Boolean,
    shapes: ToggleButtonShapes,
    modifier: Modifier,
    onSelect: (T) -> Unit,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onSelect(choice.value) },
        enabled = enabled,
        shapes = shapes,
        // Unchecked options sit on surfaceContainer groups, so lift them one tone for contrast.
        colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = modifier.semantics { role = Role.RadioButton },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (choice.icon != null) {
                Icon(choice.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
            }
            Text(choice.label, style = LocalTextStyle.current)
        }
    }
}
