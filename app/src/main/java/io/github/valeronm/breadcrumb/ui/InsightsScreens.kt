package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.TravelDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import java.time.LocalDate

/**
 * What the history adds up to, as against what happened on a given day — the Timeline's question is
 * "what did I do then", this tab's is "where have I been".
 *
 * **The figures live on the year headings, not on a page of their own.** Journeys, nights, cities
 * and countries are all sums over a year's journeys, so they belong beside the journeys they are
 * made of; a separate stats view would restate the same numbers somewhere you had to go and look.
 */
@Composable
internal fun InsightsTab(viewModel: TrackListViewModel, onOpenDay: (LocalDate) -> Unit) {
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
        tint = MaterialTheme.colorScheme.tertiary,
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
