package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.CityAtlas
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.util.PerLocale
import io.github.valeronm.breadcrumb.util.openInMaps
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Whether [title] would be cut on the single line a closed top bar gives it — the question of
 * whether an expanding bar has anything to offer this particular name.
 *
 * The width is *reckoned*, not observed: the bar's own layout isn't available before it is composed,
 * and reading it back afterwards would make the answer depend on the state it decides. So the title
 * slot is taken to be the screen less the navigation icon and [actionSlots] action icons, each a
 * standard touch target, plus the bar's own inset. An estimate a few dp out only ever misjudges a
 * name that almost exactly fills the line, where either answer is defensible.
 */
@Composable
private fun titleNeedsMoreThanOneLine(title: String, actionSlots: Int): Boolean {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleLarge
    val density = LocalDensity.current
    val iconSlot = LocalMinimumInteractiveComponentSize.current
    val screenWidth = LocalConfiguration.current.screenWidthDp
    return remember(title, actionSlots, screenWidth, style, density, iconSlot) {
        val slot = screenWidth.dp - iconSlot * (actionSlots + 1) - TOP_BAR_TITLE_INSET
        measurer.measure(
            AnnotatedString(title),
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = with(density) { slot.roundToPx() }.coerceAtLeast(0)),
        ).hasVisualOverflow
    }
}

/** The bar's own start padding plus the gap it keeps between the title and the first action. */
private val TOP_BAR_TITLE_INSET = 12.dp

/**
 * Full-screen detail for one place: its name and category, its stats, and its visits. **It reads and
 * does not edit** — bar the category, whose one-tap chips are read off the name and belong beside the
 * suggestions that produced them. Name, area and pin are [PlaceEditScreen]'s, reached through
 * [onAdjustArea]: one screen changes what the user said about a place, and the title is a heading
 * rather than a second way in. Removing a place is that screen's own button, with an Undo.
 *
 * **No map.** One framed on the capture circle answers neither question worth asking here — at this
 * size it is a texture swatch rather than a locator, it cannot say where a place *is* without
 * zooming out past the circle it was drawn for, and its pin repeats the category glyph sitting a
 * finger's width above it. Both questions have better answers already: [PlaceEditScreen], which
 * [onAdjustArea] opens as a layer above this one, is a full-height map of the area and what
 * competes for it, and the maps-app action hands the pin to something built to say where. The
 * visits take the space instead, which is what this screen is actually for.
 *
 * That holds only while [PlaceEditScreen] is reachable, and for a **detected stop** it once wasn't:
 * with no row to edit, the action was hidden and the maps app was the only way to see anything at all
 * — on the one screen whose whole job is working out what the place is. So the editor takes a name
 * too, and a stop's single filled "Create place" button opens it. No edit action there: nothing
 * exists to edit, and the button is the page's one offer.
 */
@Composable
internal fun PlaceDetailScreen(
    summary: PlaceResolver.PlaceSummary,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onOpenVisit: (StayDeriver.Stay) -> Unit,
    onAdjustArea: () -> Unit,
) {
    val context = LocalContext.current
    val place = summary.place
    val suggester by viewModel.categorySuggester.collectAsStateWithLifecycle()
    // The place's own clock, not the reader's — a visit abroad is read here exactly as the timeline
    // row for that same visit reads it, and the two disagreeing was worse than either alone. One
    // value, so the month grouping, the day headings and the times can't drift apart.
    //
    // No offset tag beside them, unlike the timeline: every row on this screen is the same place, so
    // no two times here can look alike and mean different things, and the locality line under the
    // title already says which country's clock this is.
    val zone = zoneOrDevice(summary.zoneId)
    val nowMs = remember { System.currentTimeMillis() }
    // Stays arrive newest first, so groupBy preserves month order and in-month order.
    val visitGroups = remember(summary.stays) {
        summary.stays.groupBy { YearMonth.from(it.start.toLocalDate(zone)) }
    }
    val visitsState = rememberLazyListState()
    // One value for the headings and for the scrubber's labels alike: a month names itself against
    // the current year, and two readings of "now" could disagree across a midnight.
    val today = remember(zone) { LocalDate.now(zone) }
    // A stop per visit, so the drag reaches inside a month a place was visited daily for, banded by
    // the month so the tick still marks the crossing rather than every row.
    val visitStops = remember(visitGroups) {
        groupedScrollStops(visitGroups.map { (month, visits) -> month to visits.size })
    }
    val title = place?.label ?: stringResource(R.string.place_detected_stop)
    // A bar that expands is only worth having when there is something to expand *to*. Most names fit
    // the one line a closed bar gives them, and for those the pull-down reveals the same words in
    // bigger type — an affordance that costs a gesture to learn and returns nothing. So the question
    // is asked ahead of drawing: measured rather than read back off the rendered title, because the
    // rendered one is two lines while expanded, and deciding on that would flip the answer every
    // time the bar moved.
    // Counted rather than read back off the rendered bar — an action added or withheld would
    // otherwise leave the title silently measured against a slot it doesn't have.
    val titleTruncated = titleNeedsMoreThanOneLine(title, actionSlots = if (place == null) 1 else 2)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // `collapsedFraction` moves with every scroll delta and every fling frame, while the only thing
    // read off it flips once. Derived, so the title recomposes on the crossing rather than the frame.
    val collapsed by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.collapsedFraction > 0.5f }
    }
    // Opens closed up. A place is arrived at from a row the user just tapped, so the name is a
    // confirmation rather than a question, and the visits are what the screen was opened for — an
    // expanded title would spend the top of it restating what was already read. The limit is only
    // known once the bar has measured, hence the effect rather than an initial state.
    LaunchedEffect(scrollBehavior) {
        // Waited for rather than read as a key: the limit is written during the bar's layout, and a
        // key would put that read in this function's restart scope — so settling it would recompose
        // the whole screen and rebuild the visit list's intervals.
        snapshotFlow { scrollBehavior.state.heightOffsetLimit }
            .first { it != 0f }
            .let { scrollBehavior.state.heightOffset = it }
    }
    Scaffold(
        modifier = if (titleTruncated) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier,
        topBar = {
            // A place name runs long, and in a one-line bar it either wraps into the icons or gets
            // cut where the identifying words are. Expanded it gets its own rows at the screen's
            // full width; collapsed it is one truncated line, and the page under it has scrolled far
            // enough that the name is no longer the question. It is only a heading: editing the name
            // is the edit action's, along with the radius and the pin, so there is one screen that
            // changes what the user said about a place and no second way in.
            val barTitle: @Composable () -> Unit = {
                Text(
                    title,
                    // Two lines while the bar is open, one once it has closed up.
                    maxLines = if (collapsed) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val barActions: @Composable RowScope.() -> Unit = {
                // Handing the pin to a maps app is offered for unnamed clusters too — the
                // point is there to look up whether or not it has a name.
                IconButton(
                    onClick = { context.openInMaps(summary.anchor.lat, summary.anchor.lon, place?.label) },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.places_open_in_maps),
                    )
                }
                // Everything the user gets to say about a place — its name, its area, its pin — is
                // edited behind this one action, which is why it wears a pencil rather than the
                // target it did while it only tuned a radius. Nothing here for a detected stop:
                // there is no place to edit yet, and "edit" is the wrong offer for a thing that
                // doesn't exist — creating it is the button below, at the emphasis a first step wants.
                if (place != null) {
                    IconButton(onClick = onAdjustArea) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.places_edit),
                        )
                    }
                }
            }
            if (titleTruncated) {
                MediumTopAppBar(
                    colors = canvasTopBarColors(),
                    scrollBehavior = scrollBehavior,
                    title = barTitle,
                    navigationIcon = { BackNavIcon(onBack) },
                    actions = barActions,
                )
            } else {
                TopAppBar(
                    colors = canvasTopBarColors(),
                    title = barTitle,
                    navigationIcon = { BackNavIcon(onBack) },
                    actions = barActions,
                )
            }
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Directly under the name it qualifies, before anything the user has a say in: where a
            // place is is a fact about it, where the category and the counts are what has been made
            // of it.
            PlaceLocality(summary.anchor, nowMs, viewModel)
            // What this is for and what it adds up to, held above the visits rather than read once
            // and scrolled past — the counts summarise the list moving under them, and the chip is
            // the screen's one control. Above the list rather than a sticky header inside it: at
            // index 0 it would never unstick, so the stickiness was machinery for a header that is
            // simply fixed, and it needed an opaque background only because a sticky header is
            // drawn over rows that here never pass beneath it.
            if (place != null) {
                PlaceCategorySection(
                    place = place,
                    suggester = suggester,
                    onPick = { viewModel.setPlaceCategory(place.id, it) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // The screen's one offer, and filled rather than tonal because it is the only thing
                // to do here — a detected stop is a candidate, and everything else on the page is
                // evidence for deciding. Opens the editor rather than a dialog: a name typed against
                // nothing is a guess, and that screen is the one that can show what is being named.
                Button(
                    onClick = onAdjustArea,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.places_create)) }
            }
            Card(Modifier.fillMaxWidth()) { PlaceStatsHeader(summary) }
            if (summary.stays.isEmpty()) {
                EmptyState(
                    stringResource(R.string.places_no_visits_detail),
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                )
            } else {
                Box(Modifier.weight(1f)) {
                    // The scroller the collapsing title reads. Lazy because a long-lived place
                    // accumulates visits by the hundred.
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = visitsState,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        placeVisits(visitGroups, zone, today, nowMs, onOpenVisit)
                    }
                    FastScroller(
                        state = visitsState,
                        stops = visitStops,
                        contentDescription = stringResource(R.string.places_scroll_visits),
                        label = { monthLabel(it, today) },
                    )
                }
            }
        }
    }
}

/**
 * Where in the world this place is — the city holding its pin, the country, and how far its clock
 * sits from the reader's. The one thing on this screen the user did not say and the recorder did not
 * measure: it comes from the bundled atlas, which is also why it arrives a beat late rather than
 * with the rest of the page. Absent rather than apologetic when nothing can be resolved (a coordinate
 * at sea, an atlas that failed to read); the screen reads perfectly well without it.
 *
 * **The shift is said as of [nowMs], and says so**, because there is no offset a place simply *has*:
 * two zones keep summer time on their own schedules, so a Tokyo hotel is nine hours from a European
 * reader for seven months of the year and eight for the rest — and no single number can stand over a
 * list of visits spanning both. What this answers is "where in the day is that place right now",
 * which is a question about the place; what a visit was for the reader is a question about that
 * visit, and [zoneShiftLabel] is asked at the instant either time.
 */
@Composable
private fun PlaceLocality(at: Coordinate, nowMs: Long, viewModel: TrackListViewModel) {
    val locale = LocalConfiguration.current.locales[0]
    val city by produceState<CityAtlas.City?>(null, at) { value = viewModel.cityAt(at) }
    val resolved = city ?: return
    val country = countryNameOf(resolved.country, locale)
    // Widest first, and the flag leading it: mid-line it splits the row in two, where at the
    // head it is the line's own glyph. No globe beside a line that already names a country, and
    // no flag where the country cannot be named either.
    val label = if (country.isEmpty()) {
        resolved.name
    } else {
        "${flagOf(resolved.country)} $country, ${resolved.name}"
    }
    // Off the city this line resolved, not off the summary's zone: one line, one answer about one
    // spot. Nothing trails a place keeping the reader's own clock — see [zoneShiftLabel].
    val shift = zoneShiftLabel(nowMs, zoneOrDevice(resolved.zoneId), timelineZone())
    val shiftColor = zoneShiftColor
    // Worded before the builder, which is not a composable scope. The separator stays in code:
    // it is layout between the place and its clock, not part of what either says.
    val shiftNow = shift?.let { stringResource(R.string.places_shift_now, it) }
    Text(
        buildAnnotatedString {
            append(label)
            if (shiftNow != null) {
                withStyle(SpanStyle(color = shiftColor)) { append(" · $shiftNow") }
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun PlaceStatsHeader(summary: PlaceResolver.PlaceSummary) {
    val noValue = stringResource(R.string.common_no_value)
    StatHeaderRow(
        pluralStringResource(R.plurals.places_stat_visits, summary.visitCount) to
            if (summary.visitCount > 0) "${summary.visitCount}" else noValue,
        stringResource(R.string.places_stat_time_there) to
            if (summary.totalMs > 0) durationText(summary.totalMs) else noValue,
        stringResource(R.string.places_stat_avg_visit) to
            if (summary.visitCount > 0) durationText(summary.totalMs / summary.visitCount) else noValue,
    )
}

/**
 * The place's visit history, newest first, grouped under month headers — emitted *into* the screen's
 * list rather than owning one. The whole page is one scroller, which is what lets the title collapse
 * against it; a list of its own would scroll under a bar that never moved.
 */
private fun LazyListScope.placeVisits(
    groups: Map<YearMonth, List<StayDeriver.Stay>>,
    zone: ZoneId,
    today: LocalDate,
    nowMs: Long,
    onOpenVisit: (StayDeriver.Stay) -> Unit,
) {
    groups.forEach { (month, visits) ->
        item(key = "month:$month") {
            Text(
                monthLabel(month, today),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
            )
        }
        itemsIndexed(visits, key = { _, s -> "visit:${s.afterTrackId}:${s.start}" }) { index, stay ->
            // Tap → the Timeline, scrolled to this stay in its day's context.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = groupedRowShape(index, visits.size),
                onClick = { onOpenVisit(stay) },
            ) {
                VisitRowContent(stay, zone, nowMs)
            }
        }
    }
    // The history ends where it began — a quiet marker instead of a stat-card factoid. Withheld
    // on a single visit, where the header's "Last visit" already names that day and the one row
    // above spells it out: a marker saying the history started where it plainly starts is noise.
    groups.values.takeIf { months -> months.sumOf { it.size } > 1 }
        ?.lastOrNull()?.lastOrNull()?.let { first ->
            item(key = "first-visit") {
                Text(
                    stringResource(R.string.places_first_visit, relativeDay(first.start)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                )
            }
        }
}

@Composable
private fun VisitRowContent(stay: StayDeriver.Stay, zone: ZoneId, nowMs: Long) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                // The row's own heading, so it takes a capital like the timeline's day headers do.
                visitDayFormat.format(Instant.ofEpochMilli(stay.start).atZone(zone)).standaloneCase(),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                visitTimeRange(stay, zone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            durationText((stay.end ?: nowMs) - stay.start),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** "18:18 – 08:30 +1" — the marker counts midnights crossed; the row title carries the start day. */
@Composable
@ReadOnlyComposable
private fun visitTimeRange(stay: StayDeriver.Stay, zone: ZoneId): String {
    val start = timeText(stay.start, zone)
    val end = stay.end ?: return stringResource(R.string.places_visit_since, start)
    val nights = ChronoUnit.DAYS.between(
        stay.start.toLocalDate(zone),
        end.toLocalDate(zone),
    )
    val rollover = if (nights > 0) " +$nights" else ""
    return "$start – ${timeText(end, zone)}$rollover"
}

private val visitDayFormat by PerLocale { localizedDateFormat("EEEEd", it) }
