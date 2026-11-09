package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.HiddenStay
import io.github.valeronm.breadcrumb.domain.TrackMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Confirm-style dialog: icon, message, a confirmation action, an optional second action, and a Cancel button. */
@Composable
internal fun ConfirmDialog(
    icon: ImageVector,
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = {
            Row {
                secondaryLabel?.let { TextButton(onClick = onSecondary) { Text(it) } }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
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

/** Deletes [trackIds], with one undo bringing them all back; nothing shows when the delete is refused. */
internal fun UndoSnackbar.deleteTracks(viewModel: TrackListViewModel, trackIds: List<Long>, message: String) =
    viewModel.delete(trackIds) { show(message) { viewModel.importExport.restoreDeleted(trackIds) } }

/** Merges two tracks and offers the split back under [message]; nothing shows when the merge is
 *  refused. */
internal fun UndoSnackbar.mergeTracks(viewModel: TrackListViewModel, plan: TrackMerge.Plan, message: String) =
    viewModel.mergeTracks(plan) { mergedId -> show(message) { viewModel.unmergeTracks(mergedId, plan) } }

/** Hides those of [stays] still hideable, with one undo showing them all again; [message] takes how many it hid. */
internal fun UndoSnackbar.hideStays(viewModel: TrackListViewModel, stays: List<HiddenStay>, message: (Int) -> String) =
    viewModel.hideStays(stays) { hidden -> show(message(hidden.size)) { viewModel.showStays(hidden) } }

internal fun UndoSnackbar.showStays(viewModel: TrackListViewModel, stays: List<HiddenStay>, message: String) =
    viewModel.showStays(stays) { show(message) { viewModel.hideStays(stays) } }

@Composable
internal fun rememberUndoSnackbar(host: SnackbarHostState): UndoSnackbar {
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.common_undo)
    return remember(scope, host, undoLabel) { UndoSnackbar(scope, host, undoLabel) }
}

/** The Compose host carries no dismiss gesture of its own. */
@Composable
internal fun SwipeDismissSnackbar(data: SnackbarData) {
    SwipeToDismissBox(
        state = rememberSwipeToDismissBoxState(),
        backgroundContent = {},
        onDismiss = { data.dismiss() },
    ) { Snackbar(data) }
}

/**
 * A [DatePickerDialog] speaking [LocalDate]: opens at [initial], offering only [days] where given,
 * and confirm — enabled only once a date is chosen — hands the choice back without closing
 * anything, the caller owning what follows. Also the one home for the picker's UTC-midnight
 * convention: a date crosses into the picker as millis at UTC midnight and comes back the same
 * way, whatever zone the reader is in.
 */
@Composable
internal fun LocalDateDialog(
    initial: LocalDate,
    confirmLabel: String,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    days: ClosedRange<LocalDate>? = null,
) {
    fun LocalDate.millis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.utcDate() = Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC).toLocalDate()
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = (days?.let(initial::coerceIn) ?: initial).millis(),
        selectableDates = days?.let {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis.utcDate() in it

                override fun isSelectableYear(year: Int) = year in it.start.year..it.endInclusive.year
            }
        } ?: DatePickerDefaults.AllDates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    dateState.selectedDateMillis?.let {
                        onConfirm(it.utcDate())
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
