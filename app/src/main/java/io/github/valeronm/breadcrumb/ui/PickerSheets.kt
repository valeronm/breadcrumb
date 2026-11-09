package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.isNamed
import io.github.valeronm.breadcrumb.domain.placeCategory
import kotlinx.coroutines.launch

/**
 * A titled sheet of choices, each applied on the tap that picks it. No Cancel button: dismissing a
 * sheet is the drag or the scrim. [content] runs a choice through `pick`, which applies it at once and
 * then slides the sheet closed before calling [onDismiss]; a tap while it closes is ignored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(pick: (apply: () -> Unit) -> Unit) -> Unit,
) {
    val state = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val pick: (() -> Unit) -> Unit = { apply ->
        if (!closing) {
            closing = true
            apply()
            scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            PickerTitle(title)
            content(pick)
        }
    }
}

@Composable
internal fun PickerTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

/** A place a stay can be put at, with [detail] tying it to that stay; the [current] one takes no tap. */
internal data class PlaceChoice(val place: Place, val detail: AnnotatedString, val current: Boolean = false)

@Composable
internal fun PlaceChoiceRows(choices: List<PlaceChoice>, onPick: (Place) -> Unit) {
    RowGroup(choices) { (place, detail, current), shape ->
        val category = place.placeCategory
        ListRowCard(
            shape = shape,
            icon = category.discIcon,
            disc = placeDiscStyle(category),
            iconDescription = category?.let { stringResource(it.labelRes) },
            title = place.label ?: stringResource(R.string.timeline_unnamed_place),
            titleColor = statedTitleColor(place.isNamed),
            subtitle = detail,
            marks = listOfNotNull(place.mark),
            onClick = { onPick(place) }.takeUnless { current },
            trailing = if (current) {
                {
                    Text(
                        stringResource(R.string.stay_current_place),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                null
            },
        )
    }
}

@Composable
internal fun ActivityTypeSheet(
    options: List<ActivityType>,
    selected: ActivityType?,
    onDismiss: () -> Unit,
    onPick: (ActivityType) -> Unit,
) {
    PickerSheet(stringResource(R.string.track_type_title), onDismiss) { pick ->
        Column(Modifier.selectableGroup()) {
            RowGroup(options) { option, shape ->
                OptionRow(
                    shape = shape,
                    icon = activityIcon(option),
                    label = stringResource(option.labelRes),
                    tint = activityColor(option),
                    selected = option == selected,
                ) { pick { onPick(option) } }
            }
        }
    }
}

/**
 * `selectable` rather than `clickable`: the row is one option out of a set, so which one is current
 * is state a screen reader states with it, and the tick is the sighted reading of that same state.
 */
@Composable
internal fun OptionRow(
    shape: Shape,
    icon: ImageVector,
    label: String,
    tint: Color,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(rowCardColors(outlined = false).containerColor)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            // 12 rather than 10 against the 24.dp glyph: `selectable` carries no minimum-size
            // enforcement of its own, and a picker row is a finger's target like any other.
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
