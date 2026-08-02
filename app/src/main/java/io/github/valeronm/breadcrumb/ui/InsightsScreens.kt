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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            "No journeys yet.\n\nA journey is a run of nights spent away from home — tag a place " +
                "as Home in Places, and any nights spent elsewhere gather here.",
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
            .map { (year, ofYear) -> YearSection(year, figuresOf(ofYear), ofYear.asReversed()) }
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
    val figures: String,
    val rows: List<Pair<TravelNaming.Summary, List<LocalDate>>>,
)

private fun figuresOf(ofYear: List<Pair<TravelNaming.Summary, List<LocalDate>>>): String {
    val travels = ofYear.map { (row, _) -> row }
    return listOf(
        count(travels.size, "journey", "journeys"),
        count(travels.sumOf { it.travel.nightCount }, "night", "nights"),
        count(travels.flatMap { it.cities }.toSet().size, "city", "cities"),
        count(travels.flatMap { it.countries }.toSet().size, "country", "countries"),
    ).joinToString(" · ")
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
            section.figures,
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
        title = TravelNaming.label(summary.destinations, travel.nightCount),
        titleColor = MaterialTheme.colorScheme.onSurface,
        subtitle = AnnotatedString(
            dateRange(days.first(), days.last(), today) +
                " · ${count(travel.nightCount, "night", "nights")}",
        ),
        iconDescription = "Journey",
        onClick = onOpen,
    )
}

private fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

/**
 * A journey's dates, dropping what the two ends share: "12–17 May", "28 Apr – 3 May 2019". Never a
 * single date — a journey covers one more day than it has nights, so its ends always differ.
 */
private fun dateRange(from: LocalDate, to: LocalDate, today: LocalDate): String {
    val year = if (from.year == today.year && to.year == today.year) "" else " ${to.year}"
    return when {
        from.year != to.year ->
            "${from.format(compactDayFormat)} ${from.year} – ${to.format(compactDayFormat)} ${to.year}"
        from.month == to.month -> "${from.dayOfMonth}–${to.format(compactDayFormat)}$year"
        else -> "${from.format(compactDayFormat)} – ${to.format(compactDayFormat)}$year"
    }
}
