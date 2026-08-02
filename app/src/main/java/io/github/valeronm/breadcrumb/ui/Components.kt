package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategoryGroup
import io.github.valeronm.breadcrumb.util.DistanceSliderScale
import io.github.valeronm.breadcrumb.util.PerLocale
import io.github.valeronm.breadcrumb.util.SliderStops
import io.github.valeronm.breadcrumb.util.snapToStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

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
 * Chips occupy an invisible touch target (48dp minimum) around their 32dp visual height; an inset
 * that should read from a chip's *visible* edge subtracts this overshoot.
 */
internal val chipHalo: Dp
    @Composable get() = ((LocalMinimumInteractiveComponentSize.current - FilterChipDefaults.Height) / 2)
        .coerceAtLeast(0.dp)

/** Single-choice/filter chip: checkmark when selected. */
@Composable
internal fun FilterToggleChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = selectedCheck(selected),
    )
}

/**
 * A filter over what a map draws, in the map's top-left corner — the whole thing, corner and inset
 * included, because *where* it sits is as much the idiom as how it looks. The Places map's rare
 * stops and the track map's noisy points are the same control over different maps.
 *
 * Elevated on an opaque surface: the default chip tones all but vanish against a basemap. The inset
 * subtracts [chipHalo] so the visible gap is the 12dp it looks like rather than 12dp from the
 * invisible touch target.
 */
@Composable
internal fun BoxScope.MapFilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    Box(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp - chipHalo)) {
        ElevatedFilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label) },
            leadingIcon = selectedCheck(selected),
            colors = FilterChipDefaults.elevatedFilterChipColors(
                containerColor = MaterialTheme.colorScheme.surface,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            elevation = FilterChipDefaults.elevatedFilterChipElevation(elevation = 3.dp),
        )
    }
}

private fun selectedCheck(selected: Boolean): (@Composable () -> Unit)? =
    if (selected) {
        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
    } else {
        null
    }

/**
 * Corner shape for a row in a day group: large outer corners on the group's first/last edge,
 * small inner corners between neighbors — the rows read as one grouped block.
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

internal val compactDayFormat by PerLocale { DateTimeFormatter.ofPattern("d MMM", it) }

private val compactDayYearFormat by PerLocale { DateTimeFormatter.ofPattern("d MMM yyyy", it) }

private val monthOfYearFormat by PerLocale { DateTimeFormatter.ofPattern("MMM yyyy", it) }

/**
 * Android-settings-style group: each row is its own card, large corners on the group's outer
 * edges and small ones between neighbors (same look as the track list's day groups).
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

/**
 * Settings row with a title, explanatory subtitle and an [IconSwitch]. A row the device can't
 * currently satisfy stays visible with its switch disabled and the precondition in the subtitle —
 * the same shape `KeepScreenOnRow` uses for "available while charging".
 */
@Composable
internal fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
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
        IconSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
 * unit (round feet for imperial users), storing meters only on commit.
 */
@Composable
internal fun SliderSetting(
    label: String,
    meters: Int,
    scale: DistanceSliderScale,
    onChange: (Int) -> Unit,
) {
    val display = scale.displayOf(meters)
    LabeledSlider(label, scale.label(display), display, scale.range) { raw ->
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
        )
    }
}

internal fun durationSettingLabel(sec: Int): String = when {
    sec <= 0 -> "Off"
    sec < 60 -> "$sec s"
    sec % 60 == 0 -> "${sec / 60} min"
    else -> "${sec / 60}m ${sec % 60}s"
}

internal val dateFormat by PerLocale { SimpleDateFormat("MMM d, HH:mm", it) }

internal val timeFormat by PerLocale { SimpleDateFormat("HH:mm", it) }

private val hourMinute by PerLocale { DateTimeFormatter.ofPattern("HH:mm", it) }

/**
 * A clock time in [zone] — what a row shows when the zone it happened in is not the device's.
 *
 * Not [timeFormat] with a zone set on it: [SimpleDateFormat] carries a mutable calendar, so a shared
 * instance retimed per row is a data race between two rows in different zones and a wrong time in
 * whichever loses. [java.time.format.DateTimeFormatter] is immutable, which is what makes one
 * instance safe to share across rows that disagree about the clock.
 */
internal fun timeAt(epochMs: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(hourMinute)

/**
 * How far [zone]'s clock sat from the reader's own at [epochMs] — `+8h`, `-5h30` — or null when
 * they agree and there is nothing to say.
 *
 * **Both zones are read at that instant, not today.** A trip last July is compared against what the
 * reader's own clock said last July, so summer time on either side is already in the answer and a
 * past row does not shift when either place next changes its clocks.
 *
 * A difference, deliberately, and not the UTC offset: `+8h` answers "how much later than me was it
 * there", which is the question someone reading their own history has. `+09:00` answers a question
 * about UTC that nobody asked, and leaves the arithmetic to the reader.
 */
internal fun zoneShiftLabel(epochMs: Long, zone: ZoneId, reader: ZoneId): String? {
    // The common case by far — a history mostly spent where its reader is — and the offsets below
    // reach the same conclusion the long way, once per row per recomposition.
    if (zone == reader) return null
    val at = Instant.ofEpochMilli(epochMs)
    val minutes = (zone.rules.getOffset(at).totalSeconds - reader.rules.getOffset(at).totalSeconds) / 60
    if (minutes == 0) return null
    val sign = if (minutes > 0) "+" else "−"
    val hours = abs(minutes) / 60
    val rest = abs(minutes) % 60
    return if (rest == 0) "$sign${hours}h" else "$sign${hours}h$rest"
}

/** The screens' shared top-bar back arrow. */
@Composable
internal fun BackNavIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

/**
 * Long enough that a derivation which beats it shows nothing at all — an indicator that appears and
 * vanishes within a few frames reads as a glitch, and most cold starts are over before this elapses.
 */
private const val DERIVING_SPINNER_DELAY_MS = 300L

/**
 * Placeholder for a screen whose contents are still being derived — **not** interchangeable with
 * [EmptyState]. The stay derivation walks the whole history, so until it lands a full history is
 * indistinguishable from an empty one, and the messages [EmptyState] carries here ("no places yet",
 * and on the Timeline an offer to restore a backup) are then wrong in the alarming direction.
 */
@Composable
internal fun DerivingState(modifier: Modifier = Modifier) {
    val visible by produceState(false) {
        delay(DERIVING_SPINNER_DELAY_MS)
        value = true
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (visible) CircularProgressIndicator()
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

/**
 * The lists' shared row skeleton — category disc, title and subtitle on a card — shared by Timeline track
 * and stay rows and the Places list so padding, disc placement and type scale can't drift. [onClick] is a
 * plain tap; richer gestures (long press) go in [modifier] with [onClick] null. Title color is explicit:
 * dynamic color dims the inherited card color to onSurfaceVariant (contentColorFor matches surfaceVariant first).
 */
@Composable
internal fun ListRowCard(
    shape: RoundedCornerShape,
    icon: ImageVector,
    tint: Color,
    title: String,
    titleColor: Color,
    subtitle: AnnotatedString,
    modifier: Modifier = Modifier,
    iconDescription: String? = null,
    discAlpha: Float = 0.22f,
    /** A second fact about the row, marked on the disc's corner instead of replacing its glyph. */
    badge: ImageVector? = null,
    badgeDescription: String? = null,
    /** What the badge *means* is the caller's, so its color is too — the default is only a default. */
    badgeColor: Color = MaterialTheme.colorScheme.tertiary,
    badgeContentColor: Color = MaterialTheme.colorScheme.onTertiary,
    colors: CardColors? = null,
    onClick: (() -> Unit)? = null,
) {
    val cardColors = colors ?: CardDefaults.cardColors()
    val rowContent: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TonalIconDisc(
                icon,
                tint,
                contentDescription = iconDescription,
                discAlpha = discAlpha,
                badge = badge,
                badgeDescription = badgeDescription,
                badgeColor = badgeColor,
                badgeContentColor = badgeContentColor,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = cardColors,
            content = rowContent,
        )
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = cardColors, content = rowContent)
    }
}

/**
 * The list rows' category token: a glyph on a soft tonal disc of the same color (M3 "tonal").
 * [badge] marks a *second*, unrelated fact about the row without spending the glyph on it: it rides
 * the bottom-end corner the circle leaves empty inside its own square (so a badged disc takes no
 * more room), saturated rather than tonal — at this size a soft fill reads as a smudge on the edge.
 */
@Composable
internal fun TonalIconDisc(
    icon: ImageVector,
    tint: Color,
    contentDescription: String?,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    discAlpha: Float = 0.22f,
    badge: ImageVector? = null,
    badgeDescription: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.tertiary,
    badgeContentColor: Color = MaterialTheme.colorScheme.onTertiary,
) {
    Box(modifier = Modifier.size(size)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(tint.copy(alpha = discAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * BADGE_FRACTION)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = badge,
                    contentDescription = badgeDescription,
                    tint = badgeContentColor,
                    modifier = Modifier.size(size * BADGE_FRACTION * 0.68f),
                )
            }
        }
    }
}

/** Badge diameter as a share of the disc's: big enough to read, small enough to stay a badge. */
private const val BADGE_FRACTION = 0.42f

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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
 *
 * The scrolled colour is the same one. M3 tints a bar that has content under it, which would make a
 * collapsing bar a different colour from a fixed one on the screen beside it — and on the one screen
 * carrying both, a place whose name fits would sit on the canvas while a longer-named neighbour sat
 * on the tint. The canvas is what these bars are, in every state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun canvasTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
)

@Composable
internal fun HeaderStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A hairline between two figures in a row of them — the height of the cells beside it, not of the
 * row's padding, so they read as separated without the card gaining a grid that looks like it
 * continues past its own edge.
 *
 * The row it sits in must be measured at [IntrinsicSize.Min] for `fillMaxHeight` to mean the
 * tallest cell rather than the whole row: one rule sized to each row's own type scale, instead of a
 * constant tuned against one of them and overhanging the other.
 */
@Composable
internal fun StatSeparator() {
    VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
}

/** The detail screens' stats header: equal-width label→value cells in one padded row. */
@Composable
internal fun StatHeaderRow(vararg stats: Pair<String, String>) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stats.forEachIndexed { index, (label, value) ->
            if (index > 0) StatSeparator()
            HeaderStat(label, value, Modifier.weight(1f))
        }
    }
}

internal fun activityIcon(activity: ActivityType?): ImageVector = when (activity) {
    ActivityType.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityType.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityType.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
    ActivityType.DRIVING -> Icons.Filled.DirectionsCar
    ActivityType.TAXI -> Icons.Filled.LocalTaxi
    ActivityType.FERRY -> Icons.Filled.DirectionsBoat
    // Route, not Place: the pin means "a stay" in the timeline, and UNKNOWN tracks (e.g. a GPX
    // import without a <type>) are still movement.
    else -> Icons.Filled.Route
}

// A qualitative (categorical) palette for activity type. M3 has no categorical roles, so this is a
// derived set: one fixed saturation + lightness, only the hue rotates, so every activity carries
// equal visual weight. It's a calmer sibling of the map's speed ramp (lower saturation) so a screen
// stays quiet. Green is nudged toward teal to avoid colliding with the app's green theme accent.
// STILL/UNKNOWN fall back to the neutral scheme color.
private const val ACTIVITY_SAT = 0.5f

private const val ACTIVITY_LUM = 0.62f

/**
 * A hue per activity — for the **Record tab only**, where movement is the whole subject and no place
 * appears to be coded. Elsewhere use [travelColor]: see the split described there.
 */
@Composable
internal fun activityColor(activity: ActivityType?): Color = when (activity) {
    ActivityType.DRIVING -> Color.hsl(210f, ACTIVITY_SAT, ACTIVITY_LUM) // blue
    ActivityType.TAXI -> Color.hsl(48f, ACTIVITY_SAT, ACTIVITY_LUM)     // taxi yellow
    ActivityType.FERRY -> Color.hsl(330f, ACTIVITY_SAT, ACTIVITY_LUM)   // magenta
    ActivityType.CYCLING -> Color.hsl(165f, ACTIVITY_SAT, ACTIVITY_LUM) // teal-green
    ActivityType.RUNNING -> Color.hsl(30f, ACTIVITY_SAT, ACTIVITY_LUM)  // orange
    ActivityType.WALKING -> Color.hsl(275f, ACTIVITY_SAT, ACTIVITY_LUM) // violet
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * One neutral for every kind of travel wherever places share the screen — the Timeline and anything
 * reached from it. There color is spent on places ([categoryColor]); an activity hue would compete
 * while saying nothing the row's glyphs (car, boots, bike) don't, and a day's shape is in where the
 * user stopped. The two palettes are separated by surface, not by tone — [activityColor] belongs to
 * the Record tab, which holds no places at all; that invariant is what makes both readable, and the
 * saturation split below is only the fallback if a screen ever shows both. (The web viewer colors
 * per activity throughout: its map draws overlapping *lines*, with no glyph to tell them apart.)
 */
@Composable
internal fun travelColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

// The places' categorical palette: what a place was for, by category group rather than by category —
// fifteen colors would be a legend to memorize, five are a pattern picked up by scrolling. Built like
// the activity set above (fixed saturation and lightness, hue rotates, so no group outweighs another)
// and kept a step quieter than it, so that if the two ever do meet on one screen a group still can't
// be mistaken for an activity at a neighboring hue.
private const val CATEGORY_SAT = 0.34f

private const val CATEGORY_LUM = 0.60f

private const val CATEGORY_TRANSIENT_SAT = 0.16f

/**
 * A map pin is read at a glance against a basemap, alone, with nothing to compete with — where a
 * disc behind a list row's text is read *with* the text and has to stay quiet enough for it. Same
 * hues, so the two surfaces still name the same groups; only how loudly they say it differs.
 */
private const val PIN_SAT = 0.85f

/**
 * The *lightest* a pin fill may be, not the lightness it gets: [forWhiteGlyph] darkens each hue
 * from here only as far as white ink needs, so every fill comes out as bright as its own hue allows
 * rather than as bright as the dullest one does.
 */
private const val PIN_MAX_LUM = 0.66f

private const val PIN_TRANSIENT_SAT = 0.38f

/** The share of its saturation a muted pin keeps — see [categoryMutedPinColor]. */
private const val PIN_MUTED_SAT = 0.28f

/**
 * The hue a group reads as — the only thing that separates the five, and the one thing every
 * surface coloring a place shares. Whatever else moves, this table is the vocabulary.
 *
 * The four carrying full chroma sit ~95° apart, as far as five slots allow, so no two are near
 * neighbours at pin size. Where a hue *means* something it follows that meaning — blue for the
 * places you belong to, green for being out, orange for the errand — and violet takes the routine
 * because the wheel's remaining gap is there. The transient pair is the odd one: kept close to
 * blue but drained of chroma, and deliberately **not** warm, which would read as a faded errand at
 * exactly the moment that matters (the car park on the way to the shop).
 */
private val PlaceCategoryGroup.hue: Float
    get() = when (this) {
        PlaceCategoryGroup.HOME_PEOPLE -> 217f // blue
        PlaceCategoryGroup.ERRANDS -> 22f      // orange
        PlaceCategoryGroup.ROUTINE -> 285f     // violet
        PlaceCategoryGroup.AWAY -> 116f        // green
        PlaceCategoryGroup.TRANSIENT -> 200f   // blue-gray
    }

/**
 * [sat], except for the transient pair, which stays deliberately the faintest of the five — the
 * stops that are passed through rather than spent. Still a hue, though, never a true neutral: that
 * is what an *untagged* place wears, and it can't be a group's color as well.
 */
private fun PlaceCategoryGroup.saturation(sat: Float, transientSat: Float) =
    if (this == PlaceCategoryGroup.TRANSIENT) transientSat else sat

/**
 * The color a categorized place reads as: its **group's**, never its own — a colour per category
 * would be a legend to memorize. Deliberately theme-free (a fixed hue, saturation and lightness, as
 * [activityColor] is), which is also what lets the map bake it into a pin image outside composition.
 */
internal fun categoryColor(category: PlaceCategory): Color =
    Color.hsl(category.group.hue, category.group.saturation(CATEGORY_SAT, CATEGORY_TRANSIENT_SAT), CATEGORY_LUM)

/** [categoryColor] as a map pin wears it — see [PIN_SAT] for why the map gets its own weight. */
internal fun categoryPinColor(category: PlaceCategory): Color = pinFill(category, satScale = 1f)

/**
 * The one recipe a pin fill is built by, so a neighbour's differs from a subject's in exactly the
 * one term that is meant to. [satScale] must apply *inside* it rather than to the colour it returns:
 * [forWhiteGlyph] darkens by measured contrast, so draining chroma afterwards would leave a fill
 * lightened for a saturation it no longer has.
 */
private fun pinFill(category: PlaceCategory, satScale: Float): Color = forWhiteGlyph(
    Color.hsl(
        category.group.hue,
        category.group.saturation(PIN_SAT, PIN_TRANSIENT_SAT) * satScale,
        PIN_MAX_LUM,
    ),
)

/**
 * What a *neighbouring* place's pin keeps of its chroma: the same hue and the same lightness, most of
 * the saturation gone. Colour is the only thing a pin spends to be noticed — hue is what the coding
 * says and lightness is what the glyph needs — so draining it is what makes a pin recede without
 * making it faint, which is the state a pin can't afford: the glyph still has to be recognisable, or
 * a neighbour is drawn for nothing.
 *
 * A *fraction* of the group's saturation rather than a flat value, so the transient groups — already
 * the quietest, deliberately — stay quietest here too instead of all five flattening to one wash.
 */
internal fun categoryMutedPinColor(category: PlaceCategory): Color =
    pinFill(category, satScale = PIN_MUTED_SAT)

/**
 * The pin an untagged place wears: chroma-free, so it can never be mistaken for a group's colour —
 * which is the same thing the lists say with [placeDiscTint]'s neutral, said in the map's own terms.
 *
 * Fixed rather than the theme's neutral, as the five hues are. The map bakes its colours into
 * bitmaps outside composition, and one colour reaching back into the theme would be the only reason
 * a pin image depends on anything but its category. It is a *quieter* rule than it looks: the theme
 * neutral was never worn as-is either, since white ink has to read on it.
 *
 * Lazy because [forWhiteGlyph] reaches `android.graphics.Color`: computed eagerly it runs in this
 * file's static initializer, and every pure function beside it becomes unreachable from a plain JVM
 * test — the whole file fails to load, not just this value.
 */
internal val untaggedPinColor: Color by lazy { forWhiteGlyph(Color.hsl(0f, 0f, PIN_MAX_LUM)) }

/**
 * What white ink needs against the fill under it. 3:1 is the bar for a *graphical object* (WCAG
 * 1.4.11) — a glyph is a shape to recognize, not text to read, and holding it to the 4.5:1 text bar
 * costs about a fifth of the palette's brightness for legibility a Material silhouette never needed.
 */
private const val PIN_GLYPH_CONTRAST = 3.0

/** No hue is dragged below this trying to reach it — a fill that dark stops reading as its hue. */
private const val PIN_MIN_LUM = 0.18f

private const val LUM_STEP = 0.01f

/**
 * [color] darkened, hue and saturation held, until white ink on it reads. Every glyph on the map is
 * white — one ink, so a category is never told apart by the color of its *glyph* — and that is only
 * true if no fill can be too light to carry it.
 *
 * This is what the map spends instead of the lists' fixed lightness: equal *contrast* rather than
 * equal HSL, which is the same principle (no group outweighing another) measured where it shows.
 * HSL lightness is not perceived lightness — at a shared 50% a green sits nearly three times as
 * luminous as a blue — so holding the number would be holding the wrong thing.
 */
internal fun forWhiteGlyph(color: Color): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    while (hsl[2] > PIN_MIN_LUM &&
        ColorUtils.calculateContrast(Color.White.toArgb(), ColorUtils.HSLToColor(hsl)) < PIN_GLYPH_CONTRAST
    ) {
        hsl[2] -= LUM_STEP
    }
    return Color(ColorUtils.HSLToColor(hsl))
}

/**
 * Title color for anything place-like (Places list rows, stay cards, gap sides): named reads
 * at full onSurface, unnamed at the variant. Explicit because the inherited card color dims
 * to onSurfaceVariant under dynamic color (contentColorFor matches surfaceVariant first).
 */
@Composable
internal fun placeTitleColor(named: Boolean) =
    if (named) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Icon-disc tint for anything place-like: a categorized place takes its **group's** color (kinds of
 * stop read as a pattern down a list); an untagged one — and an unnamed cluster, which can have no
 * category at all — stays neutral, so neutral is never a group's color (see [categoryColor], where
 * the transient pair is faint but still hued for exactly that reason). Deliberately a second channel
 * beside [placeTitleColor]: the title says whether you *named* the place, the disc whether you said
 * what it's *for* — one row answers both at a glance, and the pin → category glyph swap alone isn't
 * left to carry a distinction that would read as a shape change rather than a state.
 */
@Composable
internal fun placeDiscTint(category: PlaceCategory?) =
    if (category != null) {
        categoryColor(category)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

/** The matching fill: a categorized disc is solid enough to pick out while scrolling a long list. */
internal fun placeDiscAlpha(category: PlaceCategory?) = if (category != null) 0.24f else 0.12f

/**
 * A single-choice option in a dialog: glyph, label, and either the current-choice tick or a
 * Shared by both option dialogs — the track-type and place-category
 * pickers — so the corner radius, paddings and tick affordance are stated once, not copied.
 */
@Composable
internal fun OptionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
    selected: Boolean = false,
    selectedDescription: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = labelColor)
        Spacer(Modifier.weight(1f))
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = selectedDescription,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
