package io.github.valeronm.breadcrumb.ui

import android.os.SystemClock
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.graphics.ColorUtils
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategoryGroup
import io.github.valeronm.breadcrumb.domain.TravelLabel
import io.github.valeronm.breadcrumb.util.DistanceSliderScale
import io.github.valeronm.breadcrumb.util.PerLocale
import io.github.valeronm.breadcrumb.util.SliderStops
import io.github.valeronm.breadcrumb.util.snapToStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

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
        // The check reads as the track showing through the thumb, which is the one pairing every
        // palette keeps contrastive. The default `onPrimaryContainer` assumes a dark theme's
        // containers are dark tones; a scheme that inverts them renders the glyph on a thumb of
        // near-identical luminance.
        colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary),
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

/** How much room a relative day has where it renders. */
internal enum class RelativeDayStyle {
    /** An abbreviated month: "29 Nov 2025". */
    SHORT,

    /** The month spelled out: "29 November 2025", for a line with nothing else on it. */
    FULL,
}

/** Coarse relative day for "last seen": today / yesterday / N days ago / a date. */
@Composable
@ReadOnlyComposable
internal fun relativeDay(epochMs: Long, style: RelativeDayStyle = RelativeDayStyle.SHORT): String {
    val zone = ZoneId.systemDefault()
    val then = epochMs.toLocalDate(zone)
    val today = LocalDate.now(zone)
    val days = ChronoUnit.DAYS.between(then, today)
    val full = style == RelativeDayStyle.FULL
    return when {
        days <= 0 -> stringResource(R.string.relative_today)
        days == 1L -> stringResource(R.string.relative_yesterday)
        days < 7 -> pluralStringResource(R.plurals.relative_days_ago, days.toInt(), days.toInt())
        then.year == today.year -> then.format(if (full) fullDayFormat else compactDayFormat)
        else -> then.format(if (full) fullDayYearFormat else compactDayYearFormat)
    }
}

/** What heads a journey, worded: its destinations, or how many nights it ran. */
@Composable
@ReadOnlyComposable
internal fun travelTitle(label: TravelLabel): String = when (label) {
    is TravelLabel.Destinations -> label.title
    is TravelLabel.NightsAway ->
        pluralStringResource(R.plurals.timeline_nights_away, label.nights, label.nights)
}

/**
 * Title-cases a date that stands on its own — a section header, not a date inside a sentence.
 * Portuguese and its neighbours write months and weekdays lowercase, being common nouns, and the
 * platform capitalizes them in this position (the status bar reads "Terça, 4/08"). ICU does it from
 * the stand-alone display context; `java.time` has no equivalent, so headers do it on the way out.
 *
 * Never apply this to a date inside a phrase, where the lowercase form is the correct one.
 *
 * Takes the current default locale, which is where [PerLocale] gets the formatter's — so the
 * casing rule and the words it applies to can never come from two different languages.
 */
internal fun String.standaloneCase(): String =
    replaceFirstChar { it.titlecase(Locale.getDefault()) }

/**
 * A pattern the *locale* chooses, from a skeleton naming only which fields it should contain. Field
 * order and separators differ by language — day before month here, month before day there — so a
 * literal pattern hands every language English conventions.
 *
 * A skeleton naming an hour picks `H` or `h` explicitly rather than `j` — see [ReaderClock], which
 * is the only code here that formats one, and which says why the locale is the wrong authority.
 *
 * **This reaches the Android framework**, so nothing a plain-JVM test can call may format a date.
 * That is why the timeline's grouping returns dates and the screen renders them: a grouping that
 * produced header *text* dragged this call into `TimelineDayGroupingTest`, where it is not mocked.
 */
internal fun localizedDateFormat(skeleton: String, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

internal val compactDayFormat by PerLocale { localizedDateFormat("dMMM", it) }

internal val compactDayYearFormat by PerLocale { localizedDateFormat("dMMMy", it) }

internal val fullDayFormat by PerLocale { localizedDateFormat("dMMMM", it) }

internal val fullDayYearFormat by PerLocale { localizedDateFormat("dMMMMy", it) }

/** A list section's heading, in the primary tint the section titles wear across the stat screens. */
@Composable
internal fun SectionHeading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * The flag emoji for an ISO 3166-1 alpha-2 code — two regional-indicator code points, which is the
 * mechanical mapping every flag rests on, so every country the atlas knows has one without a
 * table to keep in step. A device with no glyph for a given pair renders the two letters instead,
 * which is the same information. Empty for anything that is not a two-letter upper-case code.
 */
internal fun flagOf(country: String): String {
    if (country.length != 2 || !country.all { it in 'A'..'Z' }) return ""
    return country.map { Character.toChars(REGIONAL_INDICATOR_A + (it - 'A')).concatToString() }
        .joinToString("")
}

private const val REGIONAL_INDICATOR_A = 0x1F1E6

/** The device-locale display name of an ISO 3166-1 alpha-2 code, empty when it resolves to
 *  nothing — each caller decides what an unresolvable country should read as. */
internal fun countryNameOf(code: String, locale: Locale): String =
    Locale.Builder().setRegion(code).build().getDisplayCountry(locale)

/** [countryNameOf] remembered per code — the ICU lookup would otherwise re-run on every
 *  recomposition of every row naming one — falling back to the raw code where it resolves to
 *  nothing. */
@Composable
internal fun countryDisplayName(code: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(code, locale) { countryNameOf(code, locale).ifEmpty { code } }
}

// `LLLL`, not `MMMM`: a month named on its own takes the stand-alone form, which in the Slavic
// languages is the nominative — `MMMM` there yields the genitive a full date needs ("of July").
private val monthFormat by PerLocale { DateTimeFormatter.ofPattern("LLLL", it) }

// A month *with* its year is a phrase, not a bare noun, so this one keeps the format form — pt
// writes "julho de 2026", and the connecting word arrives with the locale's own pattern.
private val monthYearFormat by PerLocale { localizedDateFormat("yMMMM", it) }

/**
 * A month and its year, both always stated — for a heading whose reader has no other date on screen
 * to place it by. Stands on its own, so it takes the capital its language gives it there.
 */
internal fun monthYearLabel(month: YearMonth): String =
    month.format(monthYearFormat).standaloneCase()

/** A month heading beside dates that already say the year, so this one drops it where it is [today]'s. */
internal fun monthLabel(month: YearMonth, today: LocalDate): String =
    if (month.year == today.year) {
        month.format(monthFormat).standaloneCase()
    } else {
        monthYearLabel(month)
    }

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
    /** Composable: the step's wording comes from resources, which a plain lambda cannot reach. */
    valueText: @Composable (Float) -> String,
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
    val measures = LocalMeasures.current
    val off = stringResource(R.string.common_off).takeIf { zeroIsOff }
    return remember(measures, off) { measures.sliderScale(metric, feet, off) }
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

/**
 * A settings slider's step. Spelled-out units, unlike the timeline's ladder: a slider label has
 * room for a word where a stat cell does not.
 */
@Composable
@ReadOnlyComposable
internal fun durationSettingLabel(sec: Int): String = when {
    sec <= 0 -> stringResource(R.string.common_off)
    sec < 60 -> stringResource(R.string.duration_seconds_step, sec)
    sec % 60 == 0 -> stringResource(R.string.duration_minutes_step, sec / 60)
    else -> stringResource(R.string.duration_minutes_seconds_step, sec / 60, sec % 60)
}

/**
 * How far [zone]'s clock sat from the reader's own at [epochMs] — `+8h`, `-5h30`, hours written
 * with the language's own symbol ([ReaderClock.shiftHourSymbol]) — or null when they agree and
 * there is nothing to say.
 *
 * **Both zones are read at that instant, not today.** A trip last July is compared against what the
 * reader's own clock said last July, so summer time on either side is already in the answer and a
 * past row does not shift when either place next changes its clocks.
 *
 * A difference, deliberately, and not the UTC offset: `+8h` answers "how much later than me was it
 * there", which is the question someone reading their own history has. `+09:00` answers a question
 * about UTC that nobody asked, and leaves the arithmetic to the reader.
 */
/** [zoneShiftLabel] for a composable caller, with the symbol fetched from the reader's own clock
 *  so it never travels by hand — a hardcoded one would compile and read wrong in one language. */
@Composable
@ReadOnlyComposable
internal fun zoneShiftLabel(epochMs: Long, zone: ZoneId, reader: ZoneId): String? =
    zoneShiftLabel(epochMs, zone, reader, LocalReaderClock.current.shiftHourSymbol)

internal fun zoneShiftLabel(epochMs: Long, zone: ZoneId, reader: ZoneId, hourSymbol: String): String? {
    // The common case by far — a history mostly spent where its reader is — and the offsets below
    // reach the same conclusion the long way, once per row per recomposition.
    if (zone == reader) return null
    val at = Instant.ofEpochMilli(epochMs)
    val minutes = (zone.rules.getOffset(at).totalSeconds - reader.rules.getOffset(at).totalSeconds) / 60
    if (minutes == 0) return null
    val sign = if (minutes > 0) "+" else "−"
    val hours = abs(minutes) / 60
    val rest = abs(minutes) % 60
    return if (rest == 0) "$sign$hours$hourSymbol" else "$sign$hours$hourSymbol$rest"
}

/** The muted treatment a zone shift wears wherever it appears — quieter than the time it trails,
 *  because it answers a question the reader only sometimes has. */
internal val zoneShiftColor: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

/**
 * A resource whose placeholders take **styled** text — a clock time carrying its zone-shift marker,
 * which is drawn rather than interpolated and so cannot be handed to [stringResource].
 *
 * This is what lets a line built around a drawn value stay one whole sentence. Written as a prefix
 * and a suffix instead, the value's position is frozen at the seam between them, and every language
 * inherits the word order of the one the fragments were written in; a translation that opens with the
 * time, or drops the preposition entirely, has nowhere to say so. Here it moves `%1$s` and is done.
 *
 * Arguments bind by position, so a translation may reorder or repeat them. An [AnnotatedString] keeps
 * its spans; anything else is appended as plain text.
 */
@Composable
@ReadOnlyComposable
internal fun annotatedStringResource(
    @StringRes id: Int,
    vararg args: CharSequence,
): AnnotatedString {
    // Formatted twice: once by the platform, which puts each mark where *this language* wants it,
    // then once by [spliceMarks], which swaps the marks for text no format string could have carried.
    // The marks are numbered, and delimited by a character no resource can contain.
    val marks = Array<Any>(args.size) { "\u0000$it\u0000" }
    return spliceMarks(stringResource(id, *marks), args)
}

/**
 * [annotatedStringResource]'s half with no resource table behind it, so a plain-JVM test can drive
 * it — and it is the half worth pinning: the marks are numbered precisely because a translation may
 * reorder them, and an index read back wrongly puts the right words in the wrong places.
 */
internal fun spliceMarks(template: String, args: Array<out CharSequence>): AnnotatedString =
    buildAnnotatedString {
        var from = 0
        SLOT_MARK.findAll(template).forEach { mark ->
            append(template.substring(from, mark.range.first))
            when (val arg = args[mark.groupValues[1].toInt()]) {
                is AnnotatedString -> append(arg)
                else -> append(arg.toString())
            }
            from = mark.range.last + 1
        }
        append(template.substring(from))
    }

private val SLOT_MARK = Regex("\u0000(\\d+)\u0000")

/**
 * A clock time in [zone] with its offset from [reader] raised against it — **the one way a time
 * reaches a screen**, and therefore the whole rule: a thing that is a time is marked, and a thing
 * that is not is not.
 *
 * A duration takes no marker under it, which is the point. An hour is an hour in every zone, so an
 * offset trailing "2h 11m" qualifies something that has no clock behind it and reads as nonsense;
 * marking one of two times instead only says the *other* end was somewhere else. The cost is a row
 * abroad repeating the same offset twice, which is the honest shape of a row whose two ends really
 * do both sit on that clock.
 *
 * Raised and shrunk rather than set level with the text: the offset annotates the time it follows
 * the way a footnote marker attaches to a word. Level with it, it reads as one more figure among the
 * duration and the visit count, and where two times each carry one the eye cannot tell which belongs
 * to which. Raised, it attaches, and needs no separator to do it.
 *
 * **[color] is passed rather than read**, so that nothing `@Composable` is called inside
 * [buildAnnotatedString]. Kotlin allows it — the builder lambda is inline, so it inherits the
 * composable context — but the composition groups it generates do not line up with the ones the
 * enclosing scope expects, and the symptom is a span absent on first paint that appears once the row
 * is rebuilt. Resolve [zoneShiftColor] in the composable body and hand it in.
 */
internal fun AnnotatedString.Builder.appendTime(
    epochMs: Long,
    zone: ZoneId,
    reader: ZoneId,
    color: Color,
    readerClock: ReaderClock,
) = appendMarked(
    readerClock.time(epochMs, zone),
    zoneShiftLabel(epochMs, zone, reader, readerClock.shiftHourSymbol),
    color,
)

/** [appendTime]'s longer form, for a screen naming one moment — see [ReaderClock.dateTime]. */
internal fun AnnotatedString.Builder.appendDateTime(
    epochMs: Long,
    zone: ZoneId,
    reader: ZoneId,
    color: Color,
    readerClock: ReaderClock,
) = appendMarked(
    readerClock.dateTime(epochMs, zone),
    zoneShiftLabel(epochMs, zone, reader, readerClock.shiftHourSymbol),
    color,
)

/**
 * [appendTime]'s value form, for a line assembled by [annotatedStringResource] rather than by a
 * builder — the marked time as a thing that can be handed to a sentence, instead of a thing appended
 * at a position the code chose.
 *
 * Unlike [appendTime] this one owns its builder, so it *could* read [LocalReaderClock] itself. It
 * stays non-composable so that the three of them keep one calling convention — and so a caller
 * assembling a line inside `remember` can still reach it.
 */
internal fun markedTime(
    epochMs: Long,
    zone: ZoneId,
    reader: ZoneId,
    color: Color,
    readerClock: ReaderClock,
): AnnotatedString =
    buildAnnotatedString { appendTime(epochMs, zone, reader, color, readerClock) }

private fun AnnotatedString.Builder.appendMarked(text: String, shift: String?, color: Color) {
    append(text)
    if (shift == null) return
    withStyle(SpanStyle(color = color, baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)) {
        append(shift)
    }
}

/** The screens' shared top-bar back arrow. */
@Composable
internal fun BackNavIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.common_back),
        )
    }
}

/**
 * Haptic CLOCK_TICK when a scrubbed value crosses keys, throttled (30 ms) so a fast drag feels like
 * a picker, not a buzz; a plain holder because gesture lambdas capture a composition and go stale.
 * [tickOnFirst]: does the first non-null key after construction (or a [reset]) tick.
 */
internal class ThrottledTick(private val view: View, private val tickOnFirst: Boolean) {
    private var last: Any? = null
    private var lastTickAt = 0L

    fun onChange(key: Any?) {
        val changedKey = key != null && key != last
        val firstKeyTicks = last != null || tickOnFirst
        if (changedKey && firstKeyTicks) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTickAt >= 30) {
                lastTickAt = now
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        last = key
    }

    fun reset() {
        last = null
    }
}

/**
 * How many screenfuls a list must run to before a scrubber earns its place. Counted in screenfuls
 * rather than rows because that is the thing the reader feels — a hundred rows are nothing to a
 * flick if they are all short, and thirty tall ones are a journey. Measured, so a list that reaches
 * the bar on a phone need not on a tablet, which is the same answer given honestly.
 */
private const val SCREENFULS_BEFORE_SCRUBBER = 3

/**
 * One position a fast scroller can put a list in: the [itemIndex] it scrolls to and the [band] it
 * belongs to.
 *
 * **A band is the structure the list is showing, so that a click means something the eye can also
 * see.** A grouped list bands by its groups — the timeline's days, a place's months — and a click
 * lands where a heading passes. A list with no groups on screen has only its rows, and there each
 * stop is its own band: the rhythm is then honest about being a rhythm, which a rule invented for
 * the occasion would not be, having nothing on screen to correspond to.
 *
 * The band is also what the bubble is drawn from, which is why it is the group itself rather than a
 * key standing for one — a month, a year, the place at that row — and why it carries its type. Only
 * the stop under a moving thumb is ever read, so a list states what its stops *are* and leaves the
 * wording to be asked for at the one place it is wanted.
 */
internal class ScrollStop<out T>(val band: T, val itemIndex: Int)

/**
 * Stops for a list that draws a heading and then that group's rows, given each group's band and how
 * many rows follow it: one stop per emitted item, so the drag travels at the list's own resolution
 * while the tick still marks a group boundary.
 *
 * **The order is the contract** — this counts what a `LazyListScope` block elsewhere emits, and
 * nothing checks the two agree, so a header, footer or banner added to that block belongs here too
 * or every stop after it points a row short.
 */
internal fun <T> groupedScrollStops(groups: List<Pair<T, Int>>): List<ScrollStop<T>> = buildList {
    var index = 0
    for ((band, rows) in groups) {
        repeat(rows + 1) { add(ScrollStop(band, index++)) }
    }
}

/**
 * A finger-sized handle that fades in while a list scrolls and can be grabbed and dragged through
 * it. The drag lands on [stops], a bubble reads [label] of the one under the thumb, and crossing
 * into another band ticks like the track scrubber.
 *
 * A list with headings can stop on those alone (the timeline's days), which lands the drag on a
 * heading and never inside one; a list without them stops per row and bands them by whatever it is
 * ordered by. So the caller decides both what the scrubber is a scale *of* and how finely it moves,
 * and nothing here knows what a row holds.
 *
 * [label] is asked only for the stop being shown, and only while a drag is in flight — a list of
 * thousands would otherwise format a date or a plural per row, on entering the screen and again
 * whenever the rows change, for text no one is looking at.
 */
@Composable
internal fun <T> BoxScope.FastScroller(
    state: LazyListState,
    stops: List<ScrollStop<T>>,
    contentDescription: String,
    label: @Composable (T) -> String,
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // Tick when the drag crosses into another band (never on the one under the initial grab).
    val bandTick = remember { ThrottledTick(view, tickOnFirst = false) }
    // Linger after the scroll stops so there's time to reach for the handle before it fades.
    var shown by remember { mutableStateOf(false) }
    val active = dragging || state.isScrollInProgress
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(1_500.milliseconds)
            shown = false
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (shown) 100 else 500),
        label = "fastScrollerAlpha",
    )
    // Whether the list runs far enough to be worth a handle. Latched, because the measurement moves
    // under the reader: rows here are of unequal height by design, so a list near the bar fits fewer
    // of them over a run of tall ones, and a handle appearing and vanishing mid-scroll fails during
    // the very gesture it exists to replace.
    var longEnough by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.size to state.layoutInfo.totalItemsCount }
            .first { (visible, total) -> visible > 0 && total >= visible * SCREENFULS_BEFORE_SCRUBBER }
            .let { longEnough = true }
    }
    // A single stop is a handle that can only ever land where it already is.
    if (alpha == 0f || stops.size < 2 || !longEnough) return

    // Where the thumb sits when the finger isn't driving it: the stop currently at the top, on the
    // same scale the drag uses (so grabbing the handle doesn't jump). Read off
    // `firstVisibleItemIndex` rather than `layoutInfo`, which notifies on every measure pass and
    // would re-run this per frame of a fling; the search is a seek because a stop list ascends.
    val listFraction by remember(state, stops) {
        derivedStateOf {
            val first = state.firstVisibleItemIndex
            val found = stops.binarySearch { it.itemIndex.compareTo(first) }
            val stopIdx = (if (found >= 0) found else -found - 2).coerceAtLeast(0)
            stopIdx.toFloat() / (stops.size - 1)
        }
    }
    val density = LocalDensity.current
    val thumbHeight = 56.dp
    val thumbWidth = 32.dp
    val touchPad = 12.dp
    val thumbPx = with(density) { thumbHeight.toPx() }
    val touchPadPx = with(density) { touchPad.toPx() }
    val bubbleGap = with(density) { (thumbWidth + 12.dp).roundToPx() }
    val bubbleLift = with(density) { 16.dp.roundToPx() }
    // The track, measured rather than subcomposed: `BoxWithConstraints` would put every read below
    // inside a `SubcomposeLayout`, and only the height is wanted.
    var trackPx by remember { mutableFloatStateOf(1f) }

    fun stopAt(f: Float): ScrollStop<T> =
        stops[(f * (stops.size - 1)).roundToInt().coerceIn(stops.indices)]

    // Where the thumb is, read as a function rather than held as a value: called from the offset
    // lambdas it is a layout-phase read, so a drag moves the handle without recomposing it.
    fun fraction(): Float = if (dragging) dragFraction else listFraction

    fun thumbTop(): Float = trackPx * fraction()

    fun applyFraction(f: Float) {
        dragFraction = f.coerceIn(0f, 1f)
        val stop = stopAt(dragFraction)
        bandTick.onChange(stop.band)
        // Requested rather than scrolled to: this runs per pointer event, and a suspending scroll
        // would take the scroll mutex and force a relayout outside the frame for each one. A
        // request folds into the frame's own measure pass.
        state.requestScrollToItem(stop.itemIndex)
    }

    Box(
        Modifier
            .matchParentSize()
            .onSizeChanged { trackPx = (it.height - thumbPx).coerceAtLeast(1f) },
    ) {
        // The handle: a half-circle hugging the edge inside a larger touch box that captures on
        // first touch-down — no slop wait, so grabs aren't eaten by drag detection (which loses
        // slow or slightly diagonal starts). Only the handle area takes input; the rest of the
        // edge scrolls the list as usual.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (thumbTop() - touchPadPx).roundToInt()) }
                .size(width = thumbWidth + touchPad, height = thumbHeight + touchPad * 2)
                .pointerInput(stops.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragFraction = fraction()
                        dragging = true
                        bandTick.reset()
                        // This box moves with the thumb, so map local positions to track space
                        // through the thumb's current offset; anchor the grab point so the
                        // handle doesn't jump under the finger.
                        fun trackY(localY: Float) = localY + trackPx * dragFraction - touchPadPx
                        val grabDelta = (trackPx * dragFraction + thumbPx / 2) - trackY(down.position.y)
                        try {
                            drag(down.id) { change ->
                                change.consume()
                                val center = trackY(change.position.y) + grabDelta
                                applyFraction((center - thumbPx / 2) / trackPx)
                            }
                        } finally {
                            dragging = false
                        }
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                modifier = Modifier.size(width = thumbWidth, height = thumbHeight).alpha(alpha),
                shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                color = if (dragging) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.UnfoldMore,
                        contentDescription = contentDescription,
                        tint = if (dragging) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        // Which stop is being named, derived so the bubble recomposes when the answer changes
        // rather than on every event of the drag that asks it.
        val naming by remember(stops) {
            derivedStateOf { if (dragging) stopAt(dragFraction) else null }
        }
        naming?.let { stop ->
            val shown = label(stop.band)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(-bubbleGap, (thumbTop() + thumbPx / 2).roundToInt() - bubbleLift)
                    },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Text(
                    shown,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * The shared top-bar commit for the editors that hold their changes — the trip form and the place
 * editor. A word rather than a tick, because the control that ends an editor is the one saying what
 * becomes of what was typed, and a glyph leaves that to be guessed at. [enabled] is the editor's own
 * test of whether it has enough to write.
 */
@Composable
internal fun SaveAction(enabled: Boolean, onSave: () -> Unit) {
    TextButton(onClick = onSave, enabled = enabled) {
        Text(stringResource(R.string.common_save))
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

/**
 * Centered placeholder for a list with nothing to show, plus optional content below the message.
 *
 * **[message] reads "No <things> yet. They appear here <when>."** — the fact, then the thing that
 * fills it. A screen that only states the fact leaves the reader to guess whether they are waiting
 * on the app or the app is waiting on them, and every one of these lists fills itself as a
 * consequence of something: recording capturing movement, a place being tagged Home, a track ending
 * inside a capture area.
 *
 * Two kinds of message deliberately do *not* take that shape, and both are on the Places tab: a list
 * emptied by a **filter or a search** says which filter emptied it, because the history behind it is
 * not empty at all and "no places yet" would be a lie. Say what the reader can act on.
 */
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
    disc: DiscStyle,
    title: String,
    titleColor: Color,
    subtitle: AnnotatedString,
    modifier: Modifier = Modifier,
    iconDescription: String? = null,
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
            IconDisc(
                icon,
                disc,
                contentDescription = iconDescription,
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
 * How an icon disc is painted: the circle's fill (with [fillAlpha]) and its glyph's ink. The named
 * recipes — [DiscStyle.tonal]'s wash, [placeDiscStyle]'s solid pin fill — are the vocabulary; a
 * surface picks one rather than re-deciding weights.
 */
internal data class DiscStyle(val fill: Color, val fillAlpha: Float, val glyph: Color) {
    companion object {
        /** A soft wash of [tint] under a glyph in the same color (M3 "tonal") — one home for the
         *  weight, so retuning the wash can't miss a surface. */
        fun tonal(tint: Color) = DiscStyle(fill = tint, fillAlpha = 0.22f, glyph = tint)
    }
}

/**
 * The list rows' category token: a glyph on a circle, painted per [DiscStyle].
 * [badge] marks a *second*, unrelated fact about the row without spending the glyph on it: it rides
 * the bottom-end corner the circle leaves empty inside its own square (so a badged disc takes no
 * more room), saturated rather than tonal — at this size a soft fill reads as a smudge on the edge.
 */
@Composable
internal fun IconDisc(
    icon: ImageVector,
    style: DiscStyle,
    contentDescription: String?,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
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
                .background(style.fill.copy(alpha = style.fillAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = style.glyph,
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
@Composable
internal fun canvasTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
)

/**
 * The tab row over a pager, on the scaffold canvas: the default surface reads as a pale stripe
 * between the top bar and the content on the light theme's dipped canvas ([canvasTopBarColors] is
 * the same rule one bar up). A tab row promises a swipe, which is why this takes the pager it
 * indicates rather than a bare index — an indicator that slides while a drag does nothing is the
 * promise broken.
 */
@Composable
internal fun PagerTabRow(pager: PagerState, labels: List<String>) {
    val scope = rememberCoroutineScope()
    PrimaryTabRow(
        selectedTabIndex = pager.currentPage,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        labels.forEachIndexed { index, label ->
            Tab(
                selected = pager.currentPage == index,
                onClick = { scope.launch { pager.animateScrollToPage(index) } },
                text = { Text(label) },
            )
        }
    }
}

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
    ActivityType.TRANSIT -> Icons.Filled.DirectionsTransit
    ActivityType.FLIGHT -> Icons.Filled.Flight
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
 * A hue per activity, wherever movement is drawn — the Record tab's totals, the Timeline's track
 * rows and day totals, the Insights stats. It shares screens with the place palette, and the two
 * stay apart by weight rather than by surface: an activity is a tonal wash under a hued glyph,
 * while a categorized place's disc is its map pin's solid fill — so a kind of travel can't be
 * mistaken for a kind of stop even at neighboring hues.
 */
@Composable
internal fun activityColor(activity: ActivityType?): Color =
    activityColorOr(activity, MaterialTheme.colorScheme.onSurfaceVariant)

/** [activityColor] for a caller outside composition, handed the theme fallback it read there. */
internal fun activityColorOr(activity: ActivityType?, fallback: Color): Color = when (activity) {
    ActivityType.DRIVING -> Color.hsl(210f, ACTIVITY_SAT, ACTIVITY_LUM) // blue
    ActivityType.TAXI -> Color.hsl(48f, ACTIVITY_SAT, ACTIVITY_LUM)     // taxi yellow
    ActivityType.FERRY -> Color.hsl(330f, ACTIVITY_SAT, ACTIVITY_LUM)   // magenta
    ActivityType.FLIGHT -> Color.hsl(195f, ACTIVITY_SAT, ACTIVITY_LUM)  // sky cyan
    ActivityType.TRANSIT -> Color.hsl(242f, ACTIVITY_SAT, ACTIVITY_LUM) // indigo
    ActivityType.CYCLING -> Color.hsl(165f, ACTIVITY_SAT, ACTIVITY_LUM) // teal-green
    ActivityType.RUNNING -> Color.hsl(30f, ACTIVITY_SAT, ACTIVITY_LUM)  // orange
    ActivityType.WALKING -> Color.hsl(275f, ACTIVITY_SAT, ACTIVITY_LUM) // violet
    else -> fallback
}

// The places' categorical palette: what a place was for, by category group rather than by category —
// fifteen colors would be a legend to memorize, five are a pattern picked up by scrolling. Built like
// the activity set above (fixed saturation and lightness, hue rotates, so no group outweighs another)
// and kept a step quieter than it, so where the two share a screen — the Timeline, a day's totals —
// a group still can't be mistaken for an activity at a neighboring hue.
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
 * which is the same thing the lists say with [placeDiscStyle]'s neutral, said in the map's own terms.
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
 * Category tint where the glyph sits beside its own words — the suggestion chips and the picker's
 * rows: a categorized place takes its **group's** color (kinds of stop read as a pattern down a
 * list); an untagged one — and an unnamed cluster, which can have no category at all — stays
 * neutral, so neutral is never a group's color (see [categoryColor], where the transient pair is
 * faint but still hued for exactly that reason). The list *discs* wear [placeDiscStyle] instead.
 */
@Composable
internal fun categoryGlyphTint(category: PlaceCategory?) =
    if (category != null) {
        categoryColor(category)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

/**
 * The tonal disc an activity wears wherever it is a token — the Record tab's totals, the Timeline's
 * track rows, the stats page: a wash of its own hue under a glyph in the same hue. Deliberately a
 * step quieter than [placeDiscStyle]'s solid fill; the weight difference is what keeps a kind of
 * travel from being mistaken for a kind of stop.
 */
@Composable
internal fun activityDiscStyle(activity: ActivityType?): DiscStyle =
    DiscStyle.tonal(activityColor(activity))

/**
 * The disc anything place-like wears in a list row (Timeline stays, the Places list): a categorized
 * place takes its map pin's own fill — solid, white glyph, the same token the map draws, so a stop
 * reads as one thing on both surfaces — while an untagged one stays a faint neutral tonal disc.
 * That asymmetry is deliberate: neutral recedes so the categorized pattern is what a scroll picks
 * up, and neutral is never a group's color. Deliberately a second channel beside [placeTitleColor]:
 * the title says whether you *named* the place, the disc whether you said what it's *for* — one row
 * answers both at a glance, and the pin → category glyph swap alone isn't left to carry a
 * distinction that would read as a shape change rather than a state.
 */
@Composable
internal fun placeDiscStyle(category: PlaceCategory?): DiscStyle =
    if (category != null) {
        categorizedDiscStyles.getValue(category)
    } else {
        val neutral = MaterialTheme.colorScheme.onSurfaceVariant
        DiscStyle(fill = neutral, fillAlpha = 0.12f, glyph = neutral)
    }

/**
 * The categorized styles are theme-free and a pure function of the category, so they are built
 * once rather than re-running [forWhiteGlyph]'s contrast walk on every composition of every list
 * row. Lazy for the same reason as [untaggedPinColor].
 */
private val categorizedDiscStyles: Map<PlaceCategory, DiscStyle> by lazy {
    PlaceCategory.entries.associateWith {
        DiscStyle(fill = categoryPinColor(it), fillAlpha = 1f, glyph = Color.White)
    }
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

/**
 * Consumes taps without click semantics or ripple — for an opaque sticky header floating over
 * tappable rows. Hit testing ignores a background, so without this a tap on the header falls
 * through to whatever row has scrolled beneath it. Scrolls still work from the header: the
 * detector releases the gesture the moment the finger moves.
 */
internal fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) { detectTapGestures {} }

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
 * The switch over a tab whose views are the same content drawn two ways — a connected button group
 * rather than a tab row, because a tab promises different content over there, and a pager's swipe
 * fights a map for every horizontal drag. The selection is the caller's state; nothing here
 * scrolls or settles, so a map view owns every gesture that reaches it. Tabs remain the right
 * control where the pages genuinely differ, which is [PagerTabRow]'s job.
 *
 * Connected rather than the segmented row it reads as, that being the control Material retired in
 * favour of this one, and connected rather than a *standard* button group because the join is the
 * whole statement: spaced buttons say several things can be pressed, a shared outline says one of
 * these is chosen. The shapes carry it — each button is told whether it leads, follows or sits
 * between — so the row must stay in position order, and wants two labels at least: a lone one is
 * drawn as a leading button, there being no standalone shape in the connected set to give it.
 *
 * Re-selecting the chosen button never calls back, so callers switch unguarded. Labels arrive as
 * string resources and resolve here, so a caller can hand over one static list and the row skips
 * with it.
 */
@Composable
internal fun ViewSwitchRow(labelsRes: List<Int>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    // An unchecked button's own default is the role this app's light scheme puts on `background`,
    // which leaves it the exact colour of the page it sits on — no container, no join, just a word.
    // Stated as the role the cards take instead, so it reads as raised in both schemes rather than
    // only in the dark one.
    val colors = ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        labelsRes.forEachIndexed { index, labelRes ->
            ToggleButton(
                checked = index == selectedIndex,
                // Toggling reports the value asked for, so the chosen button reports false and
                // drops out: the "never calls back on a re-select" rule, stated once.
                onCheckedChange = { if (it) onSelect(index) },
                modifier = Modifier.weight(1f),
                colors = colors,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    labelsRes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { Text(stringResource(labelRes)) }
        }
    }
}
