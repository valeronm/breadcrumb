package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.JourneySlice
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TravelDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.journeyTotals
import java.time.LocalDate

/**
 * One journey as a whole: its map, what moved it, and where its time went — the Insights row's
 * episode opened up, where the Timeline can only show it a day at a time.
 *
 * **The map, the figures and the cities are pages, not sections of one scroller**: a map inside a
 * scrolling column fights it for every pan, which is the same conflict the Places tab resolves the
 * same way — a pager whose swipe yields to a settled map.
 *
 * The day chips ride the map page and scope only what it draws — the figures and the cities
 * describe the journey. A day here is the Timeline's own filing ([filedOn]), so a chip's date
 * holds exactly the rows the Timeline files under it; a track crossing midnight sits, as there,
 * wholly under the day it set out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JourneyDetailScreen(
    summary: TravelNaming.Summary,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    /** Open a place tapped on the map — its detail stacks above this screen. */
    onOpenPlace: (String) -> Unit,
) {
    val travel = summary.travel
    val zone = timelineZone()
    val today = remember(zone) { LocalDate.now(zone) }
    val nowMs = remember { System.currentTimeMillis() }
    val days = remember(travel, zone) { TravelDeriver.daysCovered(travel, zone) }
    // The selection survives recomposition and process death as an index into [days]; -1 is "All".
    var selectedIndex by rememberSaveable(travel.firstNightAt) { mutableIntStateOf(-1) }
    val selectedDay = days.getOrNull(selectedIndex)

    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val journeyItems = remember(timeline, travel) {
        JourneySlice.itemsWithin(timeline.orEmpty(), travel.windowStart, travel.windowEnd, nowMs)
    }
    val shownItems = remember(journeyItems, selectedDay) {
        if (selectedDay == null) journeyItems else journeyItems.filter { it.filedOn == selectedDay }
    }

    // Every line is loaded once per open, whatever day is showing — switching a chip then only
    // filters what is already in hand. Keyed on the tracks' (id, endedAt) pairs rather than the
    // list, so a timeline emission that changed no track in the window doesn't restart the load
    // and reset the map to empty mid-fill.
    val journeyTracks = remember(journeyItems) {
        journeyItems.filterIsInstance<TimelineItem.TrackItem>().map { it.summary }
    }
    val trackKeys = remember(journeyTracks) { journeyTracks.map { it.id to it.endedAt } }
    val lines by produceState(emptyList<JourneyLine>(), trackKeys) {
        viewModel.journeyPolylines.linesFor(journeyTracks).collect { value = it }
    }
    val shownTrackIds = remember(shownItems) {
        shownItems.filterIsInstance<TimelineItem.TrackItem>().mapTo(HashSet()) { it.summary.id }
    }
    val shownLines = remember(lines, shownTrackIds) { lines.filter { it.trackId in shownTrackIds } }

    val placeSummaries by viewModel.places.collectAsStateWithLifecycle()
    val mapPlaces = rememberStayPlaces(shownItems, placeSummaries)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    Text(
                        travelTitle(TravelNaming.label(summary.destinations, travel.nightCount)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    // The old row tap, kept reachable: the Timeline at this journey's latest day.
                    IconButton(onClick = { onOpenDay(days.last()) }) {
                        Icon(
                            Icons.Filled.CalendarMonth,
                            contentDescription = stringResource(R.string.journey_view_in_timeline),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                travelSubtitle(days, today, travel.nightCount),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val pages = JourneyPage.entries
            val pager = rememberPagerState { pages.size }
            PagerTabRow(pager, pages.map { stringResource(it.labelRes) })
            // Drags reach the pager only while a scrolling page is settled: Compose claims a
            // horizontal drag before the map's own view sees it, so an always-swipeable pager
            // turns every pan into a page switch. A settled map is left by the tab tap.
            HorizontalPager(
                state = pager,
                userScrollEnabled = pages[pager.settledPage] != JourneyPage.MAP,
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                when (pages[pageIndex]) {
                    // The chips ride the map page: they choose what it draws, and nothing else
                    // reads the selection. The card is the same one the track detail draws its
                    // map in — a block of content, not the page's own ground.
                    JourneyPage.MAP -> Column(Modifier.fillMaxSize()) {
                        DayChips(
                            days = days,
                            selectedIndex = selectedIndex,
                            onSelect = { selectedIndex = it },
                        )
                        Card(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                            shape = groupedRowShape(0, 1),
                        ) {
                            Box(Modifier.fillMaxSize().clipToBounds()) {
                                MapLibreJourneyMap(
                                    lines = shownLines,
                                    places = mapPlaces,
                                    frameKey = selectedIndex,
                                    linesComplete = lines.size == journeyTracks.size,
                                    onOpenPlace = onOpenPlace,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    // Each list page derives its own rows: the pager composes only visible pages,
                    // so a reader who never leaves the map never pays for the tables.
                    JourneyPage.STATS -> StatsPage(journeyItems, nowMs)
                    JourneyPage.CITIES -> CitiesPage(summary)
                }
            }
        }
    }
}

private enum class JourneyPage(@StringRes val labelRes: Int) {
    MAP(R.string.journey_tab_map),
    STATS(R.string.journey_tab_stats),
    CITIES(R.string.journey_tab_cities),
}

@Composable
private fun StatsPage(journeyItems: List<TimelineItem>, nowMs: Long) {
    val totals = remember(journeyItems) { journeyTotals(journeyItems, nowMs) }
    val activityRows = totals.activities
    val categoryRows = totals.categories
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (activityRows.isNotEmpty()) {
            item(key = "movement") { SectionHeading(stringResource(R.string.journey_movement)) }
            itemsIndexed(activityRows, key = { _, row -> "activity:${row.activityType}" }) { index, row ->
                val type = ActivityType.ofName(row.activityType)
                JourneyStatRow(
                    shape = groupedRowShape(index, activityRows.size),
                    label = activityLabel(LocalContext.current, row.activityType),
                    trailing = listOf(distanceText(row.meters), durationText(row.durationMs))
                        .joinToString(" · "),
                    icon = activityIcon(type),
                    disc = activityDiscStyle(type),
                )
            }
        }
        if (categoryRows.isNotEmpty()) {
            item(key = "places") { SectionHeading(stringResource(R.string.journey_places)) }
            itemsIndexed(categoryRows, key = { _, row -> "category:${row.category.code}" }) { index, row ->
                JourneyStatRow(
                    shape = groupedRowShape(index, categoryRows.size),
                    label = stringResource(row.category.labelRes),
                    trailing = listOf(
                        durationText(row.durationMs),
                        pluralStringResource(R.plurals.journey_visits, row.visits, row.visits),
                    ).joinToString(" · "),
                    icon = row.category.icon,
                    disc = placeDiscStyle(row.category),
                )
            }
        }
    }
}

@Composable
private fun CitiesPage(summary: TravelNaming.Summary) {
    val sections = remember(summary) { TravelNaming.citySections(summary.timeByCity) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "header") {
            StatHeaderRow(
                stringResource(R.string.journey_stat_cities) to "${summary.cities.size}",
                stringResource(R.string.journey_stat_countries) to "${summary.countries.size}",
            )
        }
        for (section in sections) {
            item(key = "country:${section.country}") {
                // The flag leading the country's name, as the place detail's locality line wears
                // it; the bare code where the device can name neither.
                val flag = flagOf(section.country)
                val name = countryDisplayName(section.country)
                SectionHeading(if (flag.isEmpty()) name else "$flag $name")
            }
            itemsIndexed(section.cities, key = { _, (city, _) -> "city:${section.country}:$city" }) { index, (city, ms) ->
                JourneyStatRow(
                    shape = groupedRowShape(index, section.cities.size),
                    label = city,
                    trailing = durationText(ms),
                )
            }
        }
    }
}

@Composable
private fun DayChips(days: List<LocalDate>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterToggleChip(
                selected = selectedIndex == -1,
                label = stringResource(R.string.journey_all_days),
            ) { onSelect(-1) }
        }
        // The date itself, not "Day N": a chip is a target to find, and "the 14th" is how a day of
        // a trip is remembered — the Timeline's own band still numbers them for the reader counting.
        itemsIndexed(days, key = { _, day -> "day:$day" }) { index, day ->
            FilterToggleChip(
                selected = selectedIndex == index,
                label = day.format(compactDayFormat),
            ) { onSelect(index) }
        }
    }
}

@Composable
private fun JourneyStatRow(
    shape: RoundedCornerShape,
    label: String,
    trailing: String,
    icon: ImageVector? = null,
    disc: DiscStyle? = null,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = shape) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null && disc != null) {
                IconDisc(icon, disc, contentDescription = null, size = 24.dp, iconSize = 14.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
