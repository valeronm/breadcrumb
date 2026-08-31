package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.PlaceSearch
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.placeCategory
import java.util.Locale
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

private enum class PlacesSort(@StringRes val labelRes: Int) {
    LAST_VISIT(R.string.places_sort_recent),
    MOST_VISITS(R.string.places_sort_most_visits),
    TIME_SPENT(R.string.places_sort_time_spent),
    ;

    companion object {
        /** Decodes the persisted name; unknown or unset falls back to LAST_VISIT. */
        fun fromSettings(context: Context): PlacesSort =
            entries.find { it.name == AppSettings.placesSort(context) } ?: LAST_VISIT
    }
}

// Clusters below the notable-visit floor are "rare stops": hidden on the map unless its chip is
// on. A label doesn't exempt one — a place named on the strength of a single visit is exactly the
// clutter the chip is asked to clear, and a named cluster with no visits at all (a dropped pin, or
// one whose stays were deleted) is rarer still.
private fun PlaceResolver.PlaceSummary.isRareStop() =
    visitCount < PlaceResolver.NOTABLE_VISIT_MIN

// LIST before MAP, matching the Timeline's switch: the two tabs wear the same control, and the
// same choice should sit under the same thumb on both.
private enum class PlacesPage(@StringRes val labelRes: Int) {
    LIST(R.string.places_view_list),
    MAP(R.string.places_view_map),
}

/** One instance for the switch's sake: a list rebuilt per pass would recompose it per pass too. */
private val placesPageLabels = PlacesPage.entries.map { it.labelRes }

/** The Places tab: an all-places map and a sortable list (tap for detail), two views under a
 *  [ViewSwitchRow]. */
@Composable
internal fun PlacesTab(
    viewModel: TrackListViewModel,
    /** Bumped each time the Places tab is tapped while already open — send the shown view home:
     *  the list to its top, the map to the frame it opened on. Whichever view is composed is the
     *  one that consumes it. */
    homeRequest: Int,
    onOpenPlace: (String) -> Unit,
) {
    val context = LocalContext.current
    val derivedPlaces by viewModel.places.collectAsStateWithLifecycle()
    val places = derivedPlaces.orEmpty()
    // For the map's orange dots: stays the Timeline offers to merge away (TrackMerge's rules —
    // short, finished, same activity on both sides). A place that is only such an artifact is
    // marked rather than re-deciding eligibility here. Named places are exempt at the dot, not
    // here: the merge rule doesn't spare them, but a label still says the place is meant.
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    // Which view is open is a standing preference about how you read your places (InsightsTab has
    // the contrast), so it persists — written on the switch, never for the view the tab opened on.
    var page by remember {
        mutableStateOf(if (AppSettings.placesViewMap(context)) PlacesPage.MAP else PlacesPage.LIST)
    }
    val focusManager = LocalFocusManager.current
    var sort by remember { mutableStateOf(PlacesSort.fromSettings(context)) }
    var showRareStops by remember { mutableStateOf(AppSettings.placesShowRareStops(context)) }

    val sorted = remember(sort, places) {
        val comparator = when (sort) {
            PlacesSort.MOST_VISITS -> compareByDescending<PlaceResolver.PlaceSummary> { it.visitCount }
            PlacesSort.TIME_SPENT -> compareByDescending { it.totalMs }
            PlacesSort.LAST_VISIT -> compareByDescending { it.lastSeenMs ?: Long.MIN_VALUE }
        }
        // Zero-visit pass-through clusters exist for gap-side detail pages, never for this tab.
        places
            .filter { it.isNamed || it.visitCount > 0 }
            // Tiebreak: named before unnamed — a name the user chose outranks one worked out for
            // them — then alphabetically by whatever the row displays, so the derived tail is
            // ordered rather than left in whatever order the clustering produced it. Stable across
            // recompositions either way.
            .sortedWith(
                comparator
                    .thenBy { if (it.isNamed) 0 else 1 }
                    .thenBy { it.name?.lowercase(Locale.getDefault()) ?: "￿" },
            )
    }
    // Search narrows the *list* only, so it sits with the list's chrome and the map keeps whatever
    // it was showing — the same split the sort row and the rare-stops chip already follow. It
    // matches whatever the row displays, which for an unnamed cluster is the city the atlas put
    // it in: a name on screen that a search for it doesn't return reads as a broken search.
    var query by remember { mutableStateOf("") }
    // What the map draws — the list page shows `sorted` whole, since it offers no such filter and
    // demoting rows there would bury places under a rule the screen gives no way to see or turn
    // off. Note the chip's off default also hides the map's orange brief-stop dots: a one-off stop
    // is a rare cluster by definition.
    val mapVisible = remember(sorted, showRareStops) {
        if (showRareStops) sorted else sorted.filterNot { it.isRareStop() }
    }
    // Derived here rather than in the map view, which is disposed on switching away — there, this
    // history-wide walk would re-run on every return to the map. Stay identity (afterTrackId +
    // start) survives the timeline's per-day slicing — a mergeable stay is short, so its first
    // slice is the whole stay.
    val mergeableStays = remember(timeline) {
        timeline.orEmpty().filterIsInstance<TimelineItem.StayItem>()
            .filter { it.merge != null }
            .mapTo(HashSet()) { it.stay.afterTrackId to it.stay.start }
    }
    val mapPlaces = remember(mapVisible, mergeableStays) {
        mapVisible.map { summary ->
            OverviewPlace(
                marker = PlaceMarker(summary.anchor, summary.place),
                key = summary.key,
                // Never a named place: a merge offer says the split may be an artifact, but a
                // label says the user meant this place, and the dot claims the opposite.
                brief = !summary.isNamed &&
                    summary.stays.singleOrNull()
                        ?.let { (it.afterTrackId to it.start) in mergeableStays } == true,
                // Only a named place's reach is drawn: it is a number the user set and can judge
                // against its neighbours here, where an unnamed cluster's is the clusterer's
                // default repeated under every dot.
                radiusM = summary.radiusM.takeIf { summary.isNamed },
            )
        }
    }
    // Folded once per list change rather than once per place per keystroke: accent-stripping is an
    // NFD normalisation and a regex pass, and this list is the whole named history.
    val foldedNames = remember(sorted) {
        sorted.map { it.name?.let(PlaceSearch::fold) }
    }
    val listed = remember(sorted, foldedNames, query) {
        val needle = PlaceSearch.fold(query)
        if (needle.isEmpty()) {
            sorted
        } else {
            sorted.filterIndexed { index, _ -> foldedNames[index]?.contains(needle) == true }
        }
    }

    // Each view keeps its state across switches — above all the list's scroll position, which a
    // bare `when` would discard with the branch.
    val viewStateHolder = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        ViewSwitchRow(
            labelsRes = placesPageLabels,
            selectedIndex = page.ordinal,
            onSelect = { index ->
                val selected = PlacesPage.entries[index]
                page = selected
                AppSettings.setPlacesViewMap(context, selected == PlacesPage.MAP)
                // The search keyboard doesn't survive leaving the list: disposing the focused
                // field is not a reliable dismissal, so the focus is dropped with the switch.
                focusManager.clearFocus()
            },
        )
        // Deriving and an empty history are the tab's states, not a view's — gated here, so the
        // switch never toggles between two identical blanks.
        if (derivedPlaces == null) {
            DerivingState(Modifier.weight(1f).fillMaxWidth())
        } else if (sorted.isEmpty()) {
            EmptyState(
                stringResource(R.string.places_empty),
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            )
        } else {
            when (page) {
                PlacesPage.MAP -> viewStateHolder.SaveableStateProvider(PlacesPage.MAP) {
                    PlacesMapPage(
                        mapPlaces = mapPlaces,
                        showRareStops = showRareStops,
                        onToggleRareStops = {
                            showRareStops = !showRareStops
                            AppSettings.setPlacesShowRareStops(context, showRareStops)
                        },
                        homeRequest = homeRequest,
                        onOpenPlace = onOpenPlace,
                    )
                }

                PlacesPage.LIST -> viewStateHolder.SaveableStateProvider(PlacesPage.LIST) {
                    PlacesListPage(
                        listed = listed,
                        query = query,
                        onQueryChange = { query = it },
                        sort = sort,
                        onSortChange = {
                            sort = it
                            AppSettings.setPlacesSort(context, it.name)
                        },
                        homeRequest = homeRequest,
                        onOpenPlace = onOpenPlace,
                    )
                }
            }
        }
    }
}

/** The all-places map in its card; the rare-stops filter rides on the map it declutters. */
@Composable
private fun PlacesMapPage(
    mapPlaces: List<OverviewPlace>,
    showRareStops: Boolean,
    onToggleRareStops: () -> Unit,
    homeRequest: Int,
    onOpenPlace: (String) -> Unit,
) {
    // Card padding keeps the texture-mode map off the back-gesture edge strips.
    Card(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 16.dp),
    ) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            if (mapPlaces.isEmpty()) {
                // The filter, not the history, emptied this view — a bare basemap would read as
                // "no places". The chip below stays on top of this message.
                EmptyState(
                    stringResource(
                        R.string.places_all_rare,
                        stringResource(R.string.places_rare_stops),
                    ),
                    Modifier.fillMaxSize().padding(24.dp),
                )
            } else {
                MapLibrePlacesMap(
                    places = mapPlaces,
                    frameKey = homeRequest,
                    onOpen = onOpenPlace,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            MapFilterChip(
                selected = showRareStops,
                label = stringResource(R.string.places_rare_stops),
            ) { onToggleRareStops() }
        }
    }
}

/**
 * The sortable, searchable list. The search field keeps focus (and the keyboard) until something
 * takes it away. Two gestures should: a tap that no row or control claimed, and the first scroll
 * of the list — by then the user is reading results rather than typing. Both live here, so the
 * map page's own touch handling is left alone; it has no field to focus anyway.
 */
@Composable
private fun PlacesListPage(
    listed: List<PlaceResolver.PlaceSummary>,
    query: String,
    onQueryChange: (String) -> Unit,
    sort: PlacesSort,
    onSortChange: (PlacesSort) -> Unit,
    homeRequest: Int,
    onOpenPlace: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    // Re-tapping the open tab sends the list home — the same requested jump a re-sort makes, for
    // the same reason: the list arriving, not the reader moving. One immutable snapshot, as the
    // Timeline's consumers keep and for their reason — and a bump consumed by the other view
    // can't replay here on a later switch back.
    val homeRequestAtEntry = remember { homeRequest }
    SideEffect(homeRequest) {
        if (homeRequest != homeRequestAtEntry) listState.requestScrollToItem(0)
    }
    SideEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus()
    }
    // Re-sorting or narrowing makes a different list; a position kept through it is a position
    // measured against rows that are no longer there. The values this page composed with are not a
    // change, though — `rememberLazyListState` restores where the reader was across a rotation or a
    // return to this tab, and resetting on entry would throw exactly that away. Compared against
    // the values last applied, not those at entry: the entry sort is one the chips come back to,
    // and a return to it is as much a re-sort as leaving it was.
    var appliedSort by remember { mutableStateOf(sort) }
    var appliedQuery by remember { mutableStateOf(query) }
    SideEffect(sort, query) {
        if (sort == appliedSort && query == appliedQuery) return@SideEffect
        appliedSort = sort
        appliedQuery = query
        // Requested rather than scrolled: no coroutine and no forced relayout for a jump that has
        // nothing to travel through, this being the list arriving rather than the reader moving.
        listState.requestScrollToItem(0)
    }
    val scrubberStops = placeScrubberStops(listed)
    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            },
    ) {
        // Chrome pinned above the list (not scrolling with it), the search pill first: narrowing
        // the list is the header's main act, and the sort applies to whatever the search leaves.
        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.places_search_placeholder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 8.dp),
        )
        // Chips, not a second [ViewSwitchRow]: the switch at the top of the tab means "switch the
        // view", and a second control wearing the same shape would claim the same kind
        // of choice. Chips are what this app arranges and narrows lists with — the journey day
        // chips, the maps' filter chips — and a sort is that kind of act. A plain row over a lazy
        // one for three fixed chips; the scroll is for a translation that outgrows the width.
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlacesSort.entries.forEach { option ->
                FilterToggleChip(
                    selected = sort == option,
                    label = stringResource(option.labelRes),
                ) {
                    // Re-sorting means reading the list, not typing on — and the chip claims the
                    // tap, so the header's own tap-to-dismiss never sees it.
                    focusManager.clearFocus()
                    onSortChange(option)
                }
            }
        }
        if (listed.isEmpty()) {
            // The search emptied the list, not the history — say which, and leave the field above
            // it holding the query that did it.
            EmptyState(
                stringResource(R.string.places_no_match, query.trim()),
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // No swipe action on these rows — removing a place lives on its editor's
                    // Remove button, behind a screen that shows what the label being removed
                    // covers, rather than one flick on a list row.
                    itemsIndexed(listed, key = { _, s -> s.key }) { index, summary ->
                        PlaceRow(
                            summary = summary,
                            sort = sort,
                            shape = groupedRowShape(index, listed.size),
                            onClick = { onOpenPlace(summary.key) },
                        )
                    }
                }
                FastScroller(
                    state = listState,
                    stops = scrubberStops,
                    contentDescription = stringResource(R.string.places_scroll_list),
                    // The row's own line, off the one function that decides what a place says on
                    // the metric it is sorted by — a bubble wording it a second way would be a
                    // second answer to the question the reader is dragging to ask.
                    label = { placeSubtitle(it, sort) },
                )
            }
        }
    }
}

@Composable
private fun PlaceRow(
    summary: PlaceResolver.PlaceSummary,
    sort: PlacesSort,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    val named = summary.isNamed
    // A tagged place says what it is in the disc, as its stays do on the timeline. Only the glyph,
    // though: a stay row spells the category out because there the category qualifies an event,
    // while here the name is the row's identity. The words still reach a screen reader through
    // the disc's description.
    val category = summary.place?.placeCategory
    val disc = placeDiscStyle(category)
    ListRowCard(
        shape = shape,
        onClick = onClick,
        icon = category.discIcon,
        disc = disc,
        iconDescription = category?.let { stringResource(it.labelRes) },
        // The atlas's city stands in where the user has said nothing, dimmed by `named` so a
        // worked-out name never reads as one they chose — the same rule the timeline's rows follow.
        title = summary.name ?: stringResource(R.string.place_detected_stop),
        titleColor = placeTitleColor(named),
        subtitle = AnnotatedString(placeSubtitle(summary, sort)),
    )
}

/**
 * One metric, the one the list is sorted by — the subtitle then also says why the row sits where
 * it does, and no language has to fit three stats on one line. The other two are on the detail's
 * stat header.
 */
@Composable
@ReadOnlyComposable
private fun placeSubtitle(summary: PlaceResolver.PlaceSummary, sort: PlacesSort): String {
    if (summary.visitCount == 0) return stringResource(R.string.place_no_visits)
    return when (sort) {
        // A visited place can lack a dated visit; its count is the true thing left to say.
        PlacesSort.LAST_VISIT -> summary.lastSeenMs?.let { relativeDay(it, RelativeDayStyle.FULL) }
            ?: visitPhrase(summary)
        PlacesSort.MOST_VISITS -> visitPhrase(summary)
        PlacesSort.TIME_SPENT -> durationText(summary.totalMs)
    }
}

@Composable
@ReadOnlyComposable
private fun visitPhrase(summary: PlaceResolver.PlaceSummary): String =
    pluralStringResource(R.plurals.place_row_visits, summary.visitCount, summary.visitCount)

/**
 * The scrubber's scale for this list: **a stop per row, each its own band**. The list draws no
 * headings, so a row is the only division it has, and the tick keeps a row's rhythm rather than
 * marking a rule that has nothing on screen to correspond to.
 *
 * A place is its own band, rather than the row's position standing in for one: two lists of the
 * same places in a different order then tick alike, and nothing is allocated to say what the row
 * already holds.
 */
@Composable
private fun placeScrubberStops(listed: List<PlaceResolver.PlaceSummary>): List<ScrollStop<PlaceResolver.PlaceSummary>> =
    remember(listed) { listed.mapIndexed { index, summary -> ScrollStop(summary, index) } }
