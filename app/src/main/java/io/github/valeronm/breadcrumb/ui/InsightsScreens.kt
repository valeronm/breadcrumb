package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.MonthReach
import io.github.valeronm.breadcrumb.domain.MonthTotals
import io.github.valeronm.breadcrumb.domain.MonthlyTotals
import io.github.valeronm.breadcrumb.domain.TravelDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * What the history adds up to, as against what happened on a given day — the Timeline's question is
 * "what did I do then", this tab's is "where have I been" and "how much".
 *
 * Two readings of one history, and they are separate pages because they are read at different
 * scales: a journey is an episode with a name and dates, a month's figures are a shape you compare
 * against the shapes before it. Nothing on either page restates the other.
 *
 * **Tabs rather than the Places tab's segmented pill**, and the difference is what is being chosen:
 * a pill picks a *view* of one set of content (the same places, on a map or in a list), tabs move
 * between content that shares nothing. A tab row promises a swipe, so there is a pager under it —
 * an indicator that slides while a drag does nothing is the promise broken.
 *
 * Which page is open is deliberately **not** persisted, unlike the Places view: that one is a
 * standing preference about how you read your places, this is where you happened to be last time.
 */
@Composable
internal fun InsightsTab(viewModel: TrackListViewModel, onOpenDay: (LocalDate) -> Unit) {
    val pages = InsightsPage.entries
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = pager.currentPage) {
            pages.forEachIndexed { index, tab ->
                Tab(
                    selected = pager.currentPage == index,
                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                    text = { Text(stringResource(tab.labelRes)) },
                )
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
            when (pages[index]) {
                InsightsPage.JOURNEYS -> JourneysPage(viewModel, onOpenDay)
                InsightsPage.STATISTICS -> StatisticsPage(viewModel)
            }
        }
    }
}

private enum class InsightsPage(@StringRes val labelRes: Int) {
    JOURNEYS(R.string.insights_tab_journeys),
    STATISTICS(R.string.insights_tab_statistics),
}

/**
 * The journeys, newest first.
 *
 * **A year's figures live on its heading, not on a page of their own.** Journeys, nights, cities and
 * countries are all sums over a year's journeys, so they belong beside the journeys they are made
 * of; a separate view would restate the same numbers somewhere you had to go and look.
 */
@Composable
private fun JourneysPage(viewModel: TrackListViewModel, onOpenDay: (LocalDate) -> Unit) {
    val travels by viewModel.travels.collectAsStateWithLifecycle()
    val rows = travels
    when {
        // Not derived yet is not the same answer as none, and saying "you have never travelled"
        // while the history is still being read is the worse of the two to get wrong.
        rows == null -> DerivingState(Modifier.fillMaxSize())
        rows.isEmpty() -> EmptyState(
            stringResource(R.string.insights_empty),
            Modifier.fillMaxSize().padding(32.dp),
        )
        else -> TravelsList(rows, onOpenDay)
    }
}

/** Journeys newest first, in year sections — the deriver hands them over oldest first. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TravelsList(travels: List<TravelNaming.Summary>, onOpenDay: (LocalDate) -> Unit) {
    val zone = timelineZone()
    val today = LocalDate.now(zone)
    // Journeys are dated by the days they cover, never by their nights: a one-night journey covers
    // two days, and a card saying otherwise disagrees with the Timeline about the same trip. Days
    // are resolved once here — the row shows them, the year files by them, and a tap lands on them.
    val byYear = remember(travels, zone) {
        travels.map { it to TravelDeriver.daysCovered(it.travel, zone) }
            .groupBy { (_, days) -> days.first().year }
            .toSortedMap(reverseOrder())
            // What a year came to is summed here, with the grouping, rather than inside the heading:
            // a sticky heading recomposes every time its section scrolls, and none of this moves.
            // The heading still words the sums — that part is language, and it is cheap.
            .map { (year, ofYear) -> yearSectionOf(year, ofYear) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // No padding at the top: content padding does not clip, so cards would scroll through it
        // while the sticky heading pins below, showing one year's journeys above another's heading.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        // Rows within a year sit tight so the group reads as one block, as the Timeline's days do.
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (section in byYear) {
            stickyHeader(key = "year:${section.year}") { YearHeading(section) }
            itemsIndexed(section.rows, key = { _, (row, _) -> row.travel.firstNightAt }) { index, entry ->
                val (summary, days) = entry
                // Opens the Timeline at the journey's latest day, where its heading first meets the
                // eye — the rows there run newest first.
                TravelRow(summary, days, today, groupedRowShape(index, section.rows.size)) {
                    onOpenDay(days.last())
                }
            }
        }
    }
}

/** One year's journeys, newest first, and what they came to. */
private class YearSection(
    val year: Int,
    val journeys: Int,
    val nights: Int,
    val cities: Int,
    val countries: Int,
    val rows: List<Pair<TravelNaming.Summary, List<LocalDate>>>,
)

private fun yearSectionOf(
    year: Int,
    ofYear: List<Pair<TravelNaming.Summary, List<LocalDate>>>,
): YearSection {
    val travels = ofYear.map { (row, _) -> row }
    return YearSection(
        year = year,
        journeys = travels.size,
        nights = travels.sumOf { it.travel.nightCount },
        cities = travels.flatMap { it.cities }.toSet().size,
        countries = travels.flatMap { it.countries }.toSet().size,
        rows = ofYear.asReversed(),
    )
}

/** A year and what it came to. Sticky, so the figures stay with the journeys being read. */
@Composable
private fun YearHeading(section: YearSection) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 14.dp, bottom = 8.dp),
    ) {
        Text(section.year.toString(), style = MaterialTheme.typography.titleMedium)
        Text(
            listOf(
                pluralStringResource(R.plurals.insights_journeys, section.journeys, section.journeys),
                pluralStringResource(R.plurals.insights_nights, section.nights, section.nights),
                pluralStringResource(R.plurals.insights_cities, section.cities, section.cities),
                pluralStringResource(R.plurals.insights_countries, section.countries, section.countries),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TravelRow(
    summary: TravelNaming.Summary,
    days: List<LocalDate>,
    today: LocalDate,
    shape: RoundedCornerShape,
    onOpen: () -> Unit,
) {
    val travel = summary.travel
    ListRowCard(
        shape = shape,
        icon = Icons.Filled.Luggage,
        disc = DiscStyle.tonal(MaterialTheme.colorScheme.tertiary),
        title = travelTitle(TravelNaming.label(summary.destinations, travel.nightCount)),
        titleColor = MaterialTheme.colorScheme.onSurface,
        subtitle = AnnotatedString(
            dateRange(days.first(), days.last(), today) + " · " +
                pluralStringResource(R.plurals.insights_nights, travel.nightCount, travel.nightCount),
        ),
        iconDescription = stringResource(R.string.insights_journey),
        onClick = onOpen,
    )
}

/**
 * A journey's dates, dropping what the two ends share: "12–17 May", "28 Apr – 3 May 2019". Never a
 * single date — a journey covers one more day than it has nights, so its ends always differ.
 */
// The full month, not `compactDayFormat`'s abbreviation: a journey row is the screen's headline
// and has the width for it.
private fun dateRange(from: LocalDate, to: LocalDate, today: LocalDate): String {
    val year = if (from.year == today.year && to.year == today.year) "" else " ${to.year}"
    return when {
        from.year != to.year ->
            "${from.format(fullDayFormat)} ${from.year} – ${to.format(fullDayFormat)} ${to.year}"
        from.month == to.month -> "${from.dayOfMonth}–${to.format(fullDayFormat)}$year"
        else -> "${from.format(fullDayFormat)} – ${to.format(fullDayFormat)}$year"
    }
}

/**
 * One month read against the year behind it: how far each activity carried the user, and how long
 * each kind of place held them.
 *
 * **A metric per row, each scaled to itself**, rather than one chart stacking them together. Two
 * things follow, and both are the point. A month of walking is legible beside a month of flying,
 * where a shared axis would flatten it to nothing; and no series needs a colour to be told from its
 * neighbours, so this page adds no vocabulary — a row is named by the same glyph, word and hue its
 * Timeline rows wear ([activityColor], [categoryColor]).
 *
 * What it cannot show is composition: a month's *total* distance is not on this page, because
 * summing a flight and a walk answers no question anyone has.
 */
@Composable
private fun StatisticsPage(viewModel: TrackListViewModel) {
    val months by viewModel.monthlyTotals.collectAsStateWithLifecycle()
    val rows = months
    when {
        // As on the journeys page: still deriving is not the same answer as nothing recorded.
        rows == null -> DerivingState(Modifier.fillMaxSize())
        rows.isEmpty() -> EmptyState(
            stringResource(R.string.insights_stats_empty),
            Modifier.fillMaxSize().padding(32.dp),
        )
        else -> MonthlyStats(rows)
    }
}

@Composable
private fun MonthlyStats(months: List<MonthTotals>) {
    val zone = remember { timelineZone() }
    var selected by rememberSaveable(zone) { mutableStateOf(YearMonth.now(zone)) }
    // Which months are reachable and where a step lands is one rule, and it is [MonthReach]'s —
    // including holding the selection inside the bounds, which matters because the history grows
    // under an open screen and the month the reader is sitting on can stop being the last one.
    val reach = remember(months, zone, selected) {
        MonthReach.of(months, YearMonth.now(zone), selected)
    }
    val window = remember(months, reach.shown) { MonthlyTotals.window(months, reach.shown) }
    val activities = remember(window) { MonthlyTotals.activitySeries(window) }
    val categories = remember(window) { MonthlyTotals.categorySeries(window) }
    val step: (Long) -> Unit = { by -> reach.stepped(by)?.let { selected = it } }
    // A tapped bar is a step like the arrows are, measured from the window's last slot — which is
    // the shown month, the window being built to end there.
    val pickMonth = { index: Int -> step((index - (window.size - 1)).toLong()) }
    Column(Modifier.fillMaxSize()) {
        // Outside the list, not the first row of it: every figure below is *of* the shown month, so
        // scrolling the month out of sight would leave a page of numbers answering an unstated
        // question — and the arrows have to stay reachable from wherever the reader has scrolled to.
        MonthSelector(
            reach = reach,
            onStep = step,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            // Rows within a section sit tight, so the section reads as one block — as a year's
            // journeys do on the page beside this one.
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (activities.isEmpty() && categories.isEmpty()) {
                item(key = "empty") {
                    // Not [EmptyState]: the history is not empty, this stretch of it is, and the
                    // reader can act on that by stepping the selector rather than by recording.
                    Text(
                        stringResource(R.string.insights_stats_window_empty),
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (activities.isNotEmpty()) {
                item(key = "movement") {
                    SectionHeading(stringResource(R.string.insights_stats_movement))
                }
                itemsIndexed(activities, key = { _, series -> "activity:${series.key}" }) { index, series ->
                    val type = ActivityType.ofName(series.key)
                    // The activity's own hue, as its Timeline rows and day totals wear it — the
                    // bars deliberately in the same color the disc is washed with.
                    val disc = activityDiscStyle(type)
                    MetricRow(
                        fractions = series.fractions,
                        icon = activityIcon(type),
                        disc = disc,
                        tint = disc.fill,
                        label = activityLabel(LocalContext.current, series.key),
                        value = distanceText(series.latest),
                        second = durationText(series.secondary.toLong()),
                        shape = groupedRowShape(index, activities.size),
                        onPickMonth = pickMonth,
                    )
                }
            }
            if (categories.isNotEmpty()) {
                item(key = "places") { SectionHeading(stringResource(R.string.insights_stats_places)) }
                itemsIndexed(categories, key = { _, series -> "category:${series.key.code}" }) { index, series ->
                    MetricRow(
                        fractions = series.fractions,
                        icon = series.key.icon,
                        // The group colour the stays themselves were drawn in, so a section of this
                        // page reads as the same palette as the days it was summed from — the disc
                        // solid as their rows', the bars in the group's list tint.
                        disc = placeDiscStyle(series.key),
                        tint = categoryColor(series.key),
                        label = stringResource(series.key.labelRes),
                        value = durationText(series.latest.toLong()),
                        second = series.secondary.toInt().let {
                            pluralStringResource(R.plurals.insights_stats_visits, it, it)
                        },
                        shape = groupedRowShape(index, categories.size),
                        onPickMonth = pickMonth,
                    )
                }
            }
        }
    }
}

/**
 * Which month the figures are for — the month and its year, and nothing else. The window the bars
 * cover is not stated: it is twelve months back from this one wherever the reader has stepped to,
 * and a second date line beside the first invites reading the two as a range the page never means.
 */
@Composable
private fun MonthSelector(
    reach: MonthReach,
    onStep: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onStep(-1) }, enabled = reach.canStepBack) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.insights_stats_previous_month),
            )
        }
        // The year always, unlike the Places tab's month headings: there the dates on the rows below
        // place the reader, and here stepping the arrows is the only thing that says where they are.
        Text(
            monthYearLabel(reach.shown),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onStep(1) }, enabled = reach.canStepForward) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.insights_stats_next_month),
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * One metric: what it is and what it came to on the left, the shape of the months behind it on the
 * right. The figure is what is read; the bars are what it is read *against*, so they sit **beside**
 * it — stacked, the strip reads as a third line of the same statement rather than as its context,
 * and each row then costs the height of four.
 *
 * Both figures are the **shown month's**, [second] being the other measure of it — how long the
 * distance took, how many visits the hours were spread over. Only the first is plotted; the strip
 * would have to carry two axes otherwise, and the row's whole claim is that one shape is comparable
 * across twelve months.
 *
 * The two halves take equal weight and the strip is as tall as the text beside it, summed from the
 * three line heights rather than measured off the row: `IntrinsicSize.Min` would answer the same
 * question by laying every one of those `Text`s out twice on each pass, which a list of these pays
 * on every scroll frame. The sum still tracks the reader's font size, that being what a `sp` line
 * height is — which was the reason for measuring in the first place.
 */
@Composable
private fun MetricRow(
    fractions: List<Float>,
    icon: ImageVector,
    /** The series' token as its Timeline rows wear it; [tint] colors the bars beside it. */
    disc: DiscStyle,
    tint: Color,
    label: String,
    value: String,
    second: String,
    shape: RoundedCornerShape,
    onPickMonth: (Int) -> Unit,
) {
    // The three styles the left half is written in, named once: the strip's height is their line
    // heights summed, so reading them from one place is what keeps a restyled row from silently
    // mismeasuring the bars beside it.
    val labelStyle = MaterialTheme.typography.bodyLarge
    val valueStyle = MaterialTheme.typography.titleMedium
    val secondStyle = MaterialTheme.typography.labelSmall
    val stripHeight = with(LocalDensity.current) {
        labelStyle.lineHeight.toDp() + valueStyle.lineHeight.toDp() + secondStyle.lineHeight.toDp()
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = shape) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Named by the text beside it — a second reading of the same word buys nothing.
                    IconDisc(
                        icon,
                        disc,
                        contentDescription = null,
                        size = 24.dp,
                        iconSize = 14.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = labelStyle)
                }
                Text(value, style = valueStyle)
                Text(
                    second,
                    style = secondStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            MonthBars(
                fractions = fractions,
                tint = tint,
                onPick = onPickMonth,
                modifier = Modifier.weight(1f).height(stripHeight),
            )
        }
    }
}

private val BAR_GAP = 3.dp

/** A bar this short still has to be visibly a bar, or a month with something in it reads as a
 *  month with nothing — which is the one distinction the strip has to keep. */
private val BAR_MIN_HEIGHT = 2.dp

/** How much of the tint the months *before* the shown one keep — enough to read as a series, quiet
 *  enough that the month being reported on is found without hunting for it. */
private const val QUIET_BAR_ALPHA = 0.38f

/**
 * The mark an empty month wears in place of a bar. In `dp` rather than a device pixel so it holds a
 * fixed ratio to [BAR_MIN_HEIGHT] at every density — a raw pixel keeps its own size while the
 * shortest real bar scales, so the two would sit six apart on a dense screen and two apart on a
 * coarse one, and only one of those readings can be the one that was designed.
 */
private val EMPTY_MONTH_TICK = 1.dp

/**
 * How far apart two months' slots start — **the one answer**, read by the drawing and by the hit
 * test alike. Spelled twice they diverge by a gap's width across the strip, so a tap near a boundary
 * lands on the neighbour of the bar it was aimed at; and the gap belongs to the bar before it, since
 * at this width a strip of bare bars would leave dead stripes between the months.
 */
private fun slotPitch(width: Float, count: Int, gapPx: Float) = (width + gapPx) / count

/**
 * The window's months as bars, oldest at the left, the shown month last and at full tint.
 *
 * [fractions] are of this series' own peak, so the tallest bar always touches the top: the strip
 * answers "how does this month compare with those" and never "how far is this in kilometres", which
 * the figure above it already says.
 *
 * A month with nothing in it draws a **hairline** in its slot rather than nothing — and there is no
 * continuous baseline, because a month with a bar already shows where the floor is. So the rule
 * appears exactly where it is needed and nowhere else, and a sparse row spends no ink on ground its
 * own bars have already drawn. The hairline runs against a shortest-bar floor of [BAR_MIN_HEIGHT],
 * which is what keeps "nothing" from reading as "a little".
 */
@Composable
private fun MonthBars(
    fractions: List<Float>,
    tint: Color,
    onPick: (Int) -> Unit,
    modifier: Modifier,
) {
    val quiet = tint.copy(alpha = QUIET_BAR_ALPHA)
    val density = LocalDensity.current
    val gapPx = with(density) { BAR_GAP.toPx() }
    val minPx = with(density) { BAR_MIN_HEIGHT.toPx() }
    val tickPx = with(density) { EMPTY_MONTH_TICK.toPx() }
    val count = fractions.size
    // Read through so the gesture, which outlives a recomposition, never calls a stale handler —
    // the alternative is keying pointerInput on a lambda whose identity changes every frame.
    val pick by rememberUpdatedState(onPick)
    Canvas(
        modifier.pointerInput(count) {
            detectTapGestures { offset ->
                pick((offset.x / slotPitch(size.width.toFloat(), count, gapPx)).toInt().coerceIn(0, count - 1))
            }
        },
    ) {
        val pitch = slotPitch(size.width, count, gapPx)
        val barWidth = (pitch - gapPx).coerceAtLeast(1f)
        fractions.forEachIndexed { index, fraction ->
            // An empty month wears the same tint as a full one — it is the series' own slot, and the
            // shown month is marked whether or not it holds anything.
            val color = if (index == count - 1) tint else quiet
            val left = index * pitch
            if (fraction <= 0f) {
                // Square, not rounded: a rounded cap on a mark this thin renders as a fainter mark.
                drawRect(
                    color = color,
                    topLeft = Offset(left, size.height - tickPx),
                    size = Size(barWidth, tickPx),
                )
            } else {
                val height = (fraction * size.height).coerceAtLeast(minPx)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 4f),
                )
            }
        }
    }
}
