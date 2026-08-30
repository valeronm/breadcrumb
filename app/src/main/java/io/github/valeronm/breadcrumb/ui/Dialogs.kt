package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Confirm-style dialog: icon, message, a confirmation action and a Cancel button. */
@Composable
internal fun ConfirmDialog(
    icon: ImageVector,
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * "Undo" snackbars: the action happens on the spot and Undo puts it back, not a dialog asking first.
 * A new snackbar replaces whatever is on screen — rapid swipes shouldn't queue up, so only the latest
 * stays undoable (the rest still recoverable: tracks from Recently deleted, places by naming the cluster).
 */
internal class UndoSnackbar(
    private val scope: CoroutineScope,
    private val host: SnackbarHostState,
    /** Resolved by the caller: this class outlives any composition and holds no context. */
    private val undoLabel: String,
) {
    private var showing: Job? = null

    fun show(message: String, onUndo: () -> Unit) {
        showing?.cancel()
        showing = scope.launch {
            // Explicit duration: passing an actionLabel defaults it to Indefinite, which would
            // leave the snackbar parked over the nav bar until something else replaced it.
            val result = host.showSnackbar(
                message,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }
}

@Composable
internal fun rememberUndoSnackbar(host: SnackbarHostState): UndoSnackbar {
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.common_undo)
    return remember(scope, host, undoLabel) { UndoSnackbar(scope, host, undoLabel) }
}

/**
 * A [DatePickerDialog] speaking [LocalDate]: opens at [initial], and confirm — enabled only once a
 * date is chosen — hands the choice back without closing anything, the caller owning what follows
 * (plain dismissal, or the add-trip form's time picker). Also the one home for the picker's
 * UTC-midnight convention: a date crosses into the picker as millis at UTC midnight and comes
 * back the same way, whatever zone the reader is in.
 */
@Composable
internal fun LocalDateDialog(
    initial: LocalDate,
    confirmLabel: String,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    dateState.selectedDateMillis?.let {
                        onConfirm(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate())
                    }
                },
                enabled = dateState.selectedDateMillis != null,
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    ) { DatePicker(dateState) }
}

/**
 * A single-choice option in a dialog: glyph, label, and the tick marking the current choice. Shared
 * by both option dialogs — the track-type and place-category pickers — so the corner radius,
 * paddings and tick affordance are stated once, not copied.
 *
 * `selectable` rather than `clickable`: the row is one option out of a set, so which one is current
 * is state the row carries and a screen reader states with it. The tick is then the sighted reading
 * of that same state and describes nothing of its own.
 */
@Composable
internal fun OptionRow(
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
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            // 12 rather than 10 against the 24.dp glyph: `selectable` carries no minimum-size
            // enforcement of its own, and a picker row is a finger's target like any other.
            .padding(horizontal = 8.dp, vertical = 12.dp),
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
