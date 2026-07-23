package io.github.valeronm.breadcrumb.ui

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.util.DistanceSliderScale
import io.github.valeronm.breadcrumb.util.SliderStops
import io.github.valeronm.breadcrumb.util.snapToStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

/** Settings-style switch with a check/cross icon in the thumb mirroring its state. */
@Composable
internal fun IconSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        thumbContent = {
            Icon(
                if (checked) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}

/**
 * Corner shape for a row in a day group: large outer corners on the group's first/last edge,
 * small inner corners between neighbours — the rows read as one grouped block.
 */
internal fun groupedRowShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 12.dp
    val inner = 4.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Epoch millis → the local calendar date in [zone]. */
internal fun Long.toLocalDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/** Coarse relative day for "last seen": today / yesterday / N days ago / a date. */
internal fun relativeDay(epochMs: Long): String = relativeDay(epochMs, compact = false)

/** [relativeDay] squeezed for the big stat cells, where "5 days ago" or a full date overflows:
 *  "5d ago", "29 Nov", "Nov 2025" — always one line; exact dates live in the visit history. */
internal fun relativeDayCompact(epochMs: Long): String = relativeDay(epochMs, compact = true)

internal fun relativeDay(epochMs: Long, compact: Boolean): String {
    val zone = ZoneId.systemDefault()
    val then = epochMs.toLocalDate(zone)
    val today = LocalDate.now(zone)
    val days = ChronoUnit.DAYS.between(then, today)
    return when {
        days <= 0 -> "today"
        days == 1L && !compact -> "yesterday"
        days < 7 -> if (compact) "${days}d ago" else "$days days ago"
        // Compact beyond a week — this renders inside stat cells and one-line row subtitles.
        then.year == today.year -> then.format(compactDayFormat)
        else -> then.format(if (compact) monthOfYearFormat else compactDayYearFormat)
    }
}

private val compactDayFormat = DateTimeFormatter.ofPattern("d MMM")

private val compactDayYearFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

private val monthOfYearFormat = DateTimeFormatter.ofPattern("MMM yyyy")

/**
 * Android-settings-style group: each row is its own card, large corners on the group's outer
 * edges and small ones between neighbours (same look as the track list's day groups).
 */
@Composable
internal fun GroupedRows(vararg rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            Card(modifier = Modifier.fillMaxWidth(), shape = groupedRowShape(index, rows.size)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { row() }
            }
        }
    }
}

/** Settings-style navigation row: label + chevron, opening a stacked screen. */
@Composable
internal fun NavRow(
    label: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // No vertical padding of its own: the GroupedRows card already pads the row.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * One settings value on this screen: a local mirror of the pref plus its persist and default, so a
 * section's canReset/reset derive from the prefs themselves instead of hand-listing every write.
 */
internal class Pref<T>(initial: T, val default: T, private val persist: (T) -> Unit) {
    var value by mutableStateOf(initial)
        private set

    val isDefault: Boolean get() = value == default

    fun set(newValue: T) {
        value = newValue
        persist(newValue)
    }

    fun reset() = set(default)
}

@Composable
internal fun <T> rememberPref(default: T, load: () -> T, save: (T) -> Unit): Pref<T> =
    remember { Pref(load(), default, save) }

/** Settings row with a title, explanatory subtitle and an [IconSwitch]. */
@Composable
internal fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        IconSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Int,
    valueText: (Float) -> String,
    onChange: (Float) -> Unit,
) = LabeledSlider(label, valueText(value), value, range) { raw ->
    // Snap to the nearest step so values land on round numbers.
    onChange(snapToStep(raw, step, range))
}

/** The current unit system's scale for a distance slider, cached until the units change. */
@Composable
internal fun rememberDistanceScale(
    metric: SliderStops,
    feet: SliderStops,
    zeroIsOff: Boolean = false,
): DistanceSliderScale {
    val units = LocalUnits.current
    return remember(units) { units.sliderScale(metric, feet, zeroIsOff) }
}

/**
 * A distance slider riding a [DistanceSliderScale]: it drags and labels in the scale's display
 * unit (round feet for imperial users), storing metres only on commit.
 */
@Composable
internal fun SliderSetting(
    label: String,
    meters: Int,
    scale: DistanceSliderScale,
    onDragEnd: (() -> Unit)? = null,
    onChange: (Int) -> Unit,
) {
    val display = scale.displayOf(meters)
    LabeledSlider(label, scale.label(display), display, scale.range, onDragEnd) { raw ->
        onChange(scale.metersOf(scale.snap(raw)))
    }
}

/** The one label-row-plus-slider layout every slider setting renders. */
@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onDragEnd: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            onValueChangeFinished = onDragEnd,
        )
    }
}

internal fun durationSettingLabel(sec: Int): String = when {
    sec <= 0 -> "Off"
    sec < 60 -> "$sec s"
    sec % 60 == 0 -> "${sec / 60} min"
    else -> "${sec / 60}m ${sec % 60}s"
}

internal val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

internal val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** The screens' shared top-bar back arrow. */
@Composable
internal fun BackNavIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

/** Centered placeholder for a list with nothing to show, plus optional content below the message. */
@Composable
internal fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        content()
    }
}

/** The list rows' category token: a glyph on a soft tonal disc of the same colour (M3 "tonal"). */
@Composable
internal fun TonalIconDisc(
    icon: ImageVector,
    tint: Color,
    contentDescription: String?,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    discAlpha: Float = 0.22f,
) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = discAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Confirm-style dialog: icon, message, a confirm action and a Cancel button. */
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * "Undo" snackbars for the swipe actions: the action happens on the swipe and Undo puts it back,
 * rather than a dialog interrupting the gesture to ask first. A new snackbar replaces whatever is
 * on screen — rapid swipes shouldn't stack up a queue, so only the latest action stays undoable
 * (the rest are still recoverable: tracks from Recently deleted, places by naming the cluster again).
 */
internal class UndoSnackbar(
    private val scope: CoroutineScope,
    private val host: SnackbarHostState,
) {
    private var showing: Job? = null

    fun show(message: String, onUndo: () -> Unit) {
        showing?.cancel()
        showing = scope.launch {
            // Explicit duration: passing an actionLabel defaults it to Indefinite, which would
            // leave the snackbar parked over the nav bar until something else replaced it.
            val result = host.showSnackbar(
                message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }
}

@Composable
internal fun rememberUndoSnackbar(host: SnackbarHostState): UndoSnackbar {
    val scope = rememberCoroutineScope()
    return remember(scope, host) { UndoSnackbar(scope, host) }
}

/**
 * Row with a swipe-left action revealed behind it. The swipe *completes*: [onDismiss] performs the
 * action immediately and the caller offers an Undo snackbar. The row stays swiped away until the
 * action drops it from the list (an undo brings it back as a fresh, un-swiped row).
 */
@Composable
internal fun SwipeActionRow(
    shape: RoundedCornerShape,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector,
    iconDescription: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Plain remember, NOT rememberSwipeToDismissBoxState: that saves the dismissed state under the
    // lazy item's key, and an undone row returns under the same key — it would come back already
    // dismissed and re-fire onDismiss, deleting itself again on the spot.
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold) }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { onDismiss() },
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(containerColor)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(icon, contentDescription = iconDescription, tint = contentColor)
            }
        },
    ) { content() }
}

/**
 * Top bars sit on the scaffold canvas instead of the default lighter surface — visible since
 * the light theme dips the canvas below the cards; identical tones in dark.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun canvasTopBarColors() =
    TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)

internal fun formatDuration(startedAt: Long, endedAt: Long?): String {
    val end = endedAt ?: return "recording"
    return formatDurationMs(end - startedAt)
}

/** Minute-rounded like [formatDurationMs], except below a minute, where seconds are the point —
 *  edge stays start at half a minute and "0m" would say nothing. */
internal fun formatShortDurationMs(durationMs: Long): String =
    if (durationMs < 60_000L) "${durationMs / 1000}s" else formatDurationMs(durationMs)

internal fun formatDurationMs(durationMs: Long): String {
    val minutes = (durationMs / 60000.0).roundToLong()
    // A day or more: minutes stop mattering — round to whole hours and split off days.
    if (minutes >= 24 * 60) {
        val hours = ((minutes + 30) / 60)
        return if (hours % 24 == 0L) "%dd".format(hours / 24)
        else "%dd %dh".format(hours / 24, hours % 24)
    }
    return when {
        minutes >= 60 && minutes % 60 == 0L -> "%dh".format(minutes / 60)
        minutes >= 60 -> "%dh %02dm".format(minutes / 60, minutes % 60)
        else -> "%dm".format(minutes)
    }
}

@Composable
internal fun HeaderStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun activityIcon(activity: ActivityType?): ImageVector = when (activity) {
    ActivityType.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityType.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityType.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
    ActivityType.DRIVING -> Icons.Filled.DirectionsCar
    ActivityType.TAXI -> Icons.Filled.LocalTaxi
    // Route, not Place: the pin means "a stay" in the timeline, and UNKNOWN tracks (e.g. a GPX
    // import without a <type>) are still movement.
    else -> Icons.Filled.Route
}


// A qualitative (categorical) palette for activity type. M3 has no categorical roles, so this is a
// derived set: one fixed saturation + lightness, only the hue rotates, so every category carries
// equal visual weight. It's a calmer sibling of the map's speed ramp (lower saturation) so the list
// stays quiet. Green is nudged toward teal to avoid colliding with the app's green theme accent.
// STILL/UNKNOWN fall back to the neutral scheme colour.
private const val ACTIVITY_SAT = 0.5f

private const val ACTIVITY_LUM = 0.62f

@Composable
internal fun activityColor(activity: ActivityType?): Color = when (activity) {
    ActivityType.DRIVING -> Color.hsl(210f, ACTIVITY_SAT, ACTIVITY_LUM) // blue
    ActivityType.TAXI -> Color.hsl(48f, ACTIVITY_SAT, ACTIVITY_LUM)     // taxi yellow
    ActivityType.CYCLING -> Color.hsl(165f, ACTIVITY_SAT, ACTIVITY_LUM) // teal-green
    ActivityType.RUNNING -> Color.hsl(30f, ACTIVITY_SAT, ACTIVITY_LUM)  // orange
    ActivityType.WALKING -> Color.hsl(275f, ACTIVITY_SAT, ACTIVITY_LUM) // violet
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
