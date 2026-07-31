package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategoryGroup
import io.github.valeronm.breadcrumb.domain.PlaceCategorySuggester
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.PlaceSearch
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.placeCategory
import io.github.valeronm.breadcrumb.util.PerLocale
import io.github.valeronm.breadcrumb.util.SliderStops
import io.github.valeronm.breadcrumb.util.openInMaps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

private enum class PlacesSort(val label: String) {
    LAST_VISIT("Recent"),
    MOST_VISITS("Most visits"),
    TIME_SPENT("Time spent"),
    ;

    companion object {
        /** Decodes the persisted name; unknown or unset falls back to LAST_VISIT. */
        fun fromSettings(context: Context): PlacesSort =
            entries.find { it.name == AppSettings.placesSort(context) } ?: LAST_VISIT
    }
}

/** Label of the map's filter chip — the empty state below names it, so both read from here. */
private const val RARE_STOPS_LABEL = "Rare stops"

/** A line break with whatever indentation surrounds it — one space's worth of separation. */
private val LINE_BREAK_RUN = Regex("[ \\t]*[\\r\\n]+[ \\t]*")

// Clusters below the notable-visit floor are "rare stops": hidden on the map unless its chip is
// on. A label doesn't exempt one — a place named on the strength of a single visit is exactly the
// clutter the chip is asked to clear, and a named cluster with no visits at all (a dropped pin, or
// one whose stays were deleted) is rarer still.
private fun PlaceResolver.PlaceSummary.isRareStop() =
    visitCount < PlaceResolver.NOTABLE_VISIT_MIN

/** The Places tab: sortable list (tap for detail, swipe to delete) or an all-places map. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlacesTab(
    viewModel: TrackListViewModel,
    onOpenPlace: (String) -> Unit,
    /** Removes a place and offers the Undo — hoisted, because the editor's Remove is the same act. */
    onRemovePlace: (Place) -> Unit,
) {
    val context = LocalContext.current
    val derivedPlaces by viewModel.places.collectAsStateWithLifecycle()
    val places = derivedPlaces.orEmpty()
    // For the map's orange dots: stays the Timeline offers to merge away (TrackMerge's rules —
    // short, finished, same activity on both sides). A place that is only such an artifact is
    // marked rather than re-deciding eligibility here. Named places are exempt at the dot, not
    // here: the merge rule doesn't spare them, but a label still says the place is meant.
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    var showMap by remember { mutableStateOf(AppSettings.placesViewMap(context)) }
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
            // Tiebreak: named before unnamed, then by label — stable across recompositions.
            .sortedWith(comparator.thenBy { it.place?.label?.lowercase(Locale.getDefault()) ?: "￿" })
    }
    // Search narrows the *list* only, so it sits with the list's chrome and the map keeps whatever
    // it was showing — the same split the sort chips and the rare-stops chip already follow. An
    // unnamed cluster drops out of any non-empty query: it has no name to match.
    var query by remember { mutableStateOf("") }
    // What the map draws — the list below shows `sorted` whole, since it has no chip and demoting
    // rows there would bury places under a rule the screen gives no way to see or turn off. Note
    // the chip's off default also hides the map's orange brief-stop dots: a one-off stop is a rare
    // cluster by definition.
    val mapVisible = remember(sorted, showRareStops) {
        if (showRareStops) sorted else sorted.filterNot { it.isRareStop() }
    }
    // Folded once per list change rather than once per place per keystroke: accent-stripping is an
    // NFD normalisation and a regex pass, and this list is the whole named history.
    val foldedLabels = remember(sorted) {
        sorted.map { it.place?.label?.let(PlaceSearch::fold) }
    }
    val listed = remember(sorted, foldedLabels, query) {
        val needle = PlaceSearch.fold(query)
        if (needle.isEmpty()) {
            sorted
        } else {
            sorted.filterIndexed { index, _ -> foldedLabels[index]?.contains(needle) == true }
        }
    }

    // The search field keeps focus (and the keyboard) until something takes it away. Two gestures
    // should: a tap that no row or control claimed, and the first scroll of the list — by then the
    // user is reading results rather than typing. Only in list mode, so the map's own touch handling
    // is left alone; it has no field to focus anyway.
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus()
    }
    Column(
        Modifier
            .fillMaxSize()
            .then(
                if (showMap) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { focusManager.clearFocus() })
                    }
                },
            ),
    ) {
        // Chrome beyond the view switch belongs to the view it controls: sort chips pin above
        // the list, the rare-stops filter rides on the map.
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = if (showMap) 12.dp else 12.dp - chipHalo),
        ) {
            listOf(true to "Map", false to "List").forEachIndexed { index, (isMap, label) ->
                SegmentedButton(
                    selected = showMap == isMap,
                    onClick = {
                        showMap = isMap
                        AppSettings.setPlacesViewMap(context, isMap)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(label) }
            }
        }
        if (!showMap) {
            // The switch and the sort chips are both controls *of* the view and stay together under
            // it; the search pill sits last, against the rows it narrows.
            // Pinned above the list (not scrolling with it): sort stays visible and reachable
            // mid-scroll. Default touch-target spacing between wrapped lines is left in place.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                PlacesSort.entries.forEach { option ->
                    FilterToggleChip(
                        selected = sort == option,
                        label = option.label,
                        onClick = {
                            sort = option
                            AppSettings.setPlacesSort(context, option.name)
                        },
                    )
                }
            }
            PlacesSearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    // Top gap measured from the chips' *visible* edge — their touch halo overhangs
                    // downward, so it comes off the spacing here rather than showing as a wide gap.
                    .padding(top = (10.dp - chipHalo).coerceAtLeast(0.dp), bottom = 8.dp),
            )
        }
        if (derivedPlaces == null) {
            DerivingState(Modifier.weight(1f).fillMaxWidth())
        } else if (sorted.isEmpty()) {
            EmptyState(
                "No places yet. Stays and places you name show up here.",
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            )
        } else if (showMap) {
            // Card padding keeps the texture-mode map off the back-gesture edge strips.
            Card(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    // Stay identity (afterTrackId + start) survives the timeline's per-day
                    // slicing — a mergeable stay is short, so its first slice is the whole stay.
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
                                // Never a named place: a merge offer says the split may be an
                                // artifact, but a label says the user meant this place, and the
                                // dot claims the opposite.
                                brief = !summary.isNamed &&
                                    summary.stays.singleOrNull()
                                        ?.let { (it.afterTrackId to it.start) in mergeableStays } == true,
                            )
                        }
                    }
                    if (mapPlaces.isEmpty()) {
                        // The filter, not the history, emptied this view — a bare basemap would
                        // read as "no places". The chip below stays on top of this message.
                        EmptyState(
                            "Every place here is a rare stop. Turn on $RARE_STOPS_LABEL to see them.",
                            Modifier.fillMaxSize().padding(24.dp),
                        )
                    } else {
                        MapLibrePlacesMap(
                            places = mapPlaces,
                            onOpen = onOpenPlace,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // The filter rides on the map it declutters.
                    MapFilterChip(selected = showRareStops, label = RARE_STOPS_LABEL) {
                        showRareStops = !showRareStops
                        AppSettings.setPlacesShowRareStops(context, showRareStops)
                    }
                }
            }
        } else if (listed.isEmpty()) {
            // The search emptied the list, not the history — say which, and leave the field above
            // it holding the query that did it.
            EmptyState(
                "No place matches \"${query.trim()}\".",
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(listed, key = { _, s -> s.key }) { index, summary ->
                    PlaceRow(
                        summary = summary,
                        shape = groupedRowShape(index, listed.size),
                        onOpen = { onOpenPlace(summary.key) },
                        onDelete = onRemovePlace,
                    )
                }
            }
        }
    }
}

/**
 * The list's search box, shaped like the chips it sits above, not like a form field: a Material text
 * field's 56dp minimum and heavy outline read as borrowed from another screen in a header of 32dp
 * chips — hence [BasicTextField] in a pill of the app's own making, height, shape and placeholder all
 * this composable's to set. Filtering is live; with nothing to submit, the keyboard's Done dismisses itself.
 */
@Composable
private fun PlacesSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                        .copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isEmpty()) {
                    Text(
                        "Search places",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Present only with something to clear — an always-there X on an empty field invites a
            // tap that does nothing. Sized down like the day header's share action, for the same
            // reason: a full 48dp target would outweigh the 40dp control holding it.
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(
    summary: PlaceResolver.PlaceSummary,
    shape: RoundedCornerShape,
    onOpen: () -> Unit,
    onDelete: (Place) -> Unit,
) {
    // Only named places can be deleted (there's a label to remove) — unnamed clusters render as a
    // plain card with no swipe.
    val place = summary.place
    if (place == null) {
        PlaceRowCard(summary, shape, onOpen)
        return
    }
    SwipeActionRow(
        shape = shape,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        icon = Icons.Filled.Delete,
        iconDescription = "Delete",
        onDismiss = { onDelete(place) },
    ) {
        PlaceRowCard(summary, shape, onOpen)
    }
}

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
 * rather than a second way in. Removing a place is that screen's own button, or the Places list's
 * swipe — both with an Undo.
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
@OptIn(ExperimentalMaterial3Api::class)
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
    val zone = ZoneId.systemDefault()
    val nowMs = remember { System.currentTimeMillis() }
    // Stays arrive newest first, so groupBy preserves month order and in-month order.
    val visitGroups = remember(summary.stays) {
        summary.stays.groupBy { YearMonth.from(it.start.toLocalDate(zone)) }
    }
    val title = place?.label ?: "Detected stop"
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
                        contentDescription = "Open in maps app",
                    )
                }
                // Everything the user gets to say about a place — its name, its area, its pin — is
                // edited behind this one action, which is why it wears a pencil rather than the
                // target it did while it only tuned a radius. Nothing here for a detected stop:
                // there is no place to edit yet, and "edit" is the wrong offer for a thing that
                // doesn't exist — creating it is the button below, at the emphasis a first step wants.
                if (place != null) {
                    IconButton(onClick = onAdjustArea) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit place")
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
                ) { Text("Create place") }
            }
            Card(Modifier.fillMaxWidth()) { PlaceStatsHeader(summary) }
            if (summary.stays.isEmpty()) {
                EmptyState("No visits yet", Modifier.weight(1f).fillMaxWidth())
            } else {
                // The scroller the collapsing title reads. Lazy because a long-lived place
                // accumulates visits by the hundred.
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    placeVisits(visitGroups, zone, nowMs, onOpenVisit)
                }
            }
        }
    }
}

/**
 * Everything the user gets to say about one place — its name, its capture radius and its center —
 * over a full-height map of what the circle would take: this place's endpoints and the loose ones
 * around it, the named neighbors it competes with, and their areas muted underneath. Every
 * adjustment previews and none writes: the pin moves exactly as the slider moves the circle, and the
 * scan re-measures from there, so the screen always shows what saving would produce. A slider can be
 * dragged back; a jumped pin has nowhere obvious to return to, so both ways of moving it offer an
 * Undo instead.
 *
 * **The center is placed by long-pressing the map**, and the re-center action is the shortcut beside
 * it — snap to the middle of what the circle already holds. Both are needed, and in that order: the
 * center is what decides which endpoints are held at all, so an answer derived from the held ones
 * can't be the only one available. A long press rather than a tap because a tap is how a map is
 * panned; and a placement doesn't re-fit the camera, or the point aimed at would slide away under
 * the finger that aimed at it.
 *
 * **This is also where an unnamed cluster becomes a place**, which is why the name is here and not
 * only in the detail screen's dialog: a cluster has no name to identify it by, so naming it is the
 * one moment the map matters most — and the same screen then lets the radius be judged before the
 * name is committed, rather than in a second trip. Done writes name, radius and pin as one row.
 * Removing the place is its own button under the map, and a blank name is therefore never a delete —
 * Done simply disables. That separation is what the explicit button buys: the field says what a place
 * is called, and nothing about whether it exists.
 * A layer of its own above the place detail, and the map is the reason: the two screens want it at
 * different heights, and a `MapView` is a `TextureView` — handed a new size it scales its
 * last-rendered frame into the new box until it has one of its own, the pin visibly stretching to
 * an oval. Nothing fixes that inside one shared map (hiding is too late, the scaled frame was
 * rendered before the hide; animating the height makes it every frame instead of one); a map per
 * screen is never resized, so there is nothing to hide, sequence or animate around. Its own camera
 * is the point rather than the price: this screen opens framed on the circle, in a state the radius
 * can be judged in, wherever the detail map below was panned. Backing out is the layer's own
 * predictive-back gesture, discarding by construction — radius and pin are local to a screen that
 * gets thrown away, so nothing needs restoring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceEditScreen(
    summary: PlaceResolver.PlaceSummary,
    neighbors: List<PlaceMarker>,
    candidates: List<StayDeriver.Endpoint>,
    rivals: List<PlaceClusterer.Seed>,
    viewModel: TrackListViewModel,
    onClose: () -> Unit,
    /** The row a create landed on — the screen underneath follows the place there. */
    onCreated: (Long) -> Unit,
    /** Removes the place and leaves this screen — the caller owns both, since the Undo it offers has
     *  to outlive a layer that is going away. */
    onRemove: (Place) -> Unit,
) {
    // Null while this is still a cluster being named — every write below then becomes the one insert.
    val place = summary.place
    // Everything this screen adjusts is local until Done: the name, the circle and its center
    // preview together, and nothing is written on the way. A write here re-derives the whole
    // timeline, so committing per drag step would re-derive it several times for one adjustment.
    // Leaving without Done discards them all by simply never having written them.
    var radiusM by remember(place?.id) { mutableFloatStateOf(summary.radiusM.toFloat()) }
    // Where the place sits, which for an unnamed cluster is where naming would drop the pin rather
    // than the anchor its clustering grew from — so the circle previews what saving produces.
    var pin by remember(place?.id) { mutableStateOf(summary.pin) }
    // The state, not its value: read here and every keystroke would invalidate this whole screen —
    // including the map, which cannot skip and would re-run its input diff per character. The field
    // reads it a level down ([PlaceNameField]), the way ZoomReadout takes the camera's zoom.
    val name = remember(place?.id) { mutableStateOf(place?.label ?: "") }
    // Read where the bar's actions are built, and that scope also measures the re-center target over
    // every captured endpoint — so it must not turn on the name itself, or each keystroke would walk
    // the scan again. Derived, so it changes only as the field crosses between blank and not.
    val nameGiven by remember { derivedStateOf { name.value.isNotBlank() } }
    // Its own host, not the app's: that one hangs off MainScreen's Scaffold, under every overlay
    // layer, so an Undo offered from this screen would be covered by the screen offering it.
    val snackbarHostState = remember { SnackbarHostState() }
    val undo = rememberUndoSnackbar(snackbarHostState)
    // Both ways of moving the pin are one step back: a jumped pin has nowhere obvious to return to,
    // where a slider can simply be dragged again.
    val movePin: (StayDeriver.Endpoint, String) -> Unit = { target, message ->
        val was = pin
        pin = target
        undo.show(message) { pin = was }
    }
    // Down to 25 m (75 ft, the step-aligned stop nearest it): a doorway-scale place needs a circle
    // tighter than GPS scatter, and narrowing one is also how it stops claiming a neighbour's stops.
    val radiusScale = rememberDistanceScale(SliderStops(25, 500, 25), SliderStops(75, 1650, 75))
    val maxRadiusM = radiusScale.metersOf(radiusScale.range.endInclusive).toDouble()
    // Prepared once per pin, not per drag step — whether a neighbor keeps an endpoint has nothing to
    // do with our radius, and a per-step scan made a place with a few thousand endpoints around it
    // lag under the finger (see CaptureScan). Off the main thread, because the frame it would land
    // on is this layer's opening one: a 300 ms animation and a fresh MapView loading its style, plus
    // a distance call per endpoint. Null until the first scan (the map draws plain endpoints
    // meanwhile), and the previous scan stays up while a moved pin re-measures, so re-centering
    // colors the dots from where the pin was for a frame rather than blanking them. Keyed on what
    // the scan reads, not the whole summary — its visit stats move on every derivation and would
    // rebuild this for nothing.
    val scan by produceState<PlaceClusterer.CaptureScan?>(null, pin, candidates, rivals, maxRadiusM) {
        value = withContext(Dispatchers.Default) {
            PlaceClusterer.scanCapture(
                candidates = candidates,
                anchor = pin,
                maxRadiusM = maxRadiusM,
                rivals = rivals,
                distance = AndroidDistance,
            )
        }
    }
    val captureDots = remember(scan) {
        // Conceded dots carry no distance — a nearer pin holds them at any radius, so the map
        // must draw them settled rather than compare them.
        scan?.let {
            it.winnable.map { reach -> CaptureDot(reach.location, reach.distanceM) } +
                it.conceded.map { endpoint -> CaptureDot(endpoint, null) }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    Text(
                        // Titled with the action that opened it, not with what it does to a name —
                        // "Create place" is the offer the button made. Bounded here rather than moved
                        // into the content as on the detail screen: this screen's content is a
                        // full-height map with nowhere to put a heading, and you arrive already
                        // knowing which place you opened, or what you came to do to a stop.
                        place?.label ?: "Create place",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackNavIcon(onClose) },
                actions = {
                    // Measured against the circle on screen, not the one last saved: dragging
                    // changes what it takes, which moves the middle of it, which is where
                    // re-centering would put the pin. Taking the offer moves the pin there and
                    // re-measures from it, so a second tap settles rather than repeating.
                    val recenterTarget = scan?.let {
                        PlaceResolver.recenterTarget(pin, it, radiusM.toDouble(), AndroidDistance)
                    }
                    if (recenterTarget != null) {
                        IconButton(onClick = { movePin(recenterTarget, "Pin re-centered") }) {
                            Icon(Icons.Filled.FilterCenterFocus, contentDescription = "Re-center pin")
                        }
                    }
                    IconButton(
                        onClick = {
                            viewModel.savePlace(place, name.value, pin, radiusM.toDouble(), onCreated)
                            onClose()
                        },
                        // A place must be called something. Clearing the field is not how one is
                        // deleted — that offer belongs to the Remove button below.
                        enabled = nameGiven,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Done")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) { PlaceNameField(name) }
            Card(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    MapLibrePlaceMap(
                        center = PlaceMarker(pin, summary.place),
                        radiusM = radiusM.toDouble(),
                        endpoints = summary.endpoints,
                        neighbors = neighbors,
                        capture = captureDots,
                        rivalAreas = rivals,
                        // Placing the center by hand, where the re-center action only snaps it to
                        // what the circle already holds — and the center is what decides what is
                        // held, so it needs an answer that isn't derived from the dots. A long press
                        // rather than a tap: a tap is how a map is panned, and this is one Undo away
                        // either way.
                        onLongPress = { movePin(it, "Pin moved") },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Over the map's corner, not under the slider: this number is read *while*
                    // dragging, and a hand reaching down to the slider covers everything below it.
                    // Here it sits beside the dots it counts, in the one part of the screen a thumb
                    // never crosses. What the circle holds *now*, not what the stored radius holds,
                    // so it and the dots can't disagree as the slider moves.
                    val captured = remember(scan, radiusM, summary.endpoints) {
                        scan?.countWithin(radiusM.toDouble()) ?: summary.endpoints.size
                    }
                    LegendSurface(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                        Text(
                            "$captured ${if (captured == 1) "endpoint" else "endpoints"}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            // Below the map, not above it: this is the one control dragged while watching the result,
            // and a hand on a slider above the map covers the circle it is sizing.
            Card(Modifier.fillMaxWidth()) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    SliderSetting("Capture radius", radiusM.roundToInt(), radiusScale) {
                        radiusM = it.toFloat()
                    }
                }
            }
            // Under the map rather than up with the fields: removing is not one more thing to adjust,
            // and it must not sit next to the name it would once have been performed by clearing.
            // Low emphasis in the error color, and it takes effect at once with an Undo, as the
            // Places list's swipe does — the app answers a destructive tap with a way back rather
            // than with a question first. Nothing to remove until there is a row.
            if (place != null) {
                TextButton(
                    onClick = { onRemove(place) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Remove place")
                }
            }
        }
    }
}

/**
 * What a place is called. Takes the state rather than its value so that typing invalidates this and
 * nothing above it: the editor's column holds a map that cannot skip a recomposition, and would
 * re-run its whole input diff per character.
 */
@Composable
private fun PlaceNameField(name: MutableState<String>) {
    OutlinedTextField(
        value = name.value,
        // `singleLine` lays the field out on one line but doesn't police what arrives: paste a block
        // of text and everything past the first break is stored, and saved, where it can't be seen.
        // Breaks (and the indentation around them) fold into single spaces instead.
        onValueChange = { name.value = it.replace(LINE_BREAK_RUN, " ") },
        singleLine = true,
        label = { Text("Place name") },
        // Place names are proper nouns — capitalize each word.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * One category as a chip, denser than the stock [SuggestionChip]. **Both states of the category line
 * are built from this** — the tagged place's current category and the untagged place's suggestions —
 * so the line keeps one height and one visual language whichever it is showing, and there is no
 * selector for the suggestions to look bolted onto. The glyph carries its group's colour: the same
 * set the timeline and Places rows use, already learned, and scanned faster than the label.
 * [showsMore] marks a chip that opens the full picker rather than setting a category.
 *
 * [icon] is optional because one chip has no category to stand for: "More" would only be able to
 * show a glyph *about* opening a picker, which the caret already says and the word says twice.
 *
 * [selected] switches it to a filled `InputChip`. The filled/outlined split is what carries the
 * meaning — an answer already given should not look like one more thing on offer, and a caret alone
 * said only *this opens something*, which is an affordance rather than a statement of what the chip
 * holds. The component pair follows Material's rule that a chip is chosen by who authored its
 * content, the product's guess against the person's own answer; the two are never on screen
 * together, so the differing chip metrics cost nothing.
 */
@Composable
private fun CategoryChip(
    label: String,
    icon: ImageVector? = null,
    tint: Color = Color.Unspecified,
    showsMore: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (showsMore) {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val leading: @Composable (() -> Unit)? = icon?.let {
        { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint) }
    }
    val height = Modifier.height(CATEGORY_CHIP_HEIGHT)
    if (selected) {
        InputChip(
            selected = true,
            onClick = onClick,
            label = content,
            leadingIcon = leading,
            shape = CircleShape,
            modifier = height,
        )
    } else {
        SuggestionChip(
            onClick = onClick,
            label = content,
            icon = leading,
            shape = CircleShape,
            modifier = height,
        )
    }
}

/** Shorter than the stock 32dp chip; the touch target stays 48dp, which the chip's halo supplies. */
private val CATEGORY_CHIP_HEIGHT = 30.dp

/**
 * A category the user has just tapped, held until the stored row catches up. A wrapper rather than a
 * bare [PlaceCategory]? because untagging is itself a choice: "picked Not set" and "picked nothing
 * yet" are different states and null cannot carry both.
 */
private data class PickedCategory(val value: PlaceCategory?)

/**
 * What a place is for, as **one chip-high line**: tagged, the category it carries;
 * untagged, the categories its *name* suggests ([PlaceCategorySuggester]) plus a chip onto the full
 * picker. Deliberately not a card and not a full-width row — a place spends most of its life with a
 * one-word answer here, and a card's surface and padding cost three times the line they wrap, on a
 * screen whose subject is the visits below it.
 *
 * A suggestion is a shortcut past the picker, never a replacement for it: the picker chip is always
 * present, the suggester offers at most three and often none, and nothing is written until something
 * is tapped — which is what lets a wrong guess cost a glance. Suggestions show only while untagged,
 * because a tagged place has an answer already and re-suggesting against it would invite tapping the
 * model's opinion over the user's own.
 */
@Composable
private fun PlaceCategorySection(
    place: Place,
    suggester: PlaceCategorySuggester.Model,
    onPick: (PlaceCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosingCategory by remember(place.id) { mutableStateOf(false) }
    // Keyed on the stored category as well as the place: the hold releases itself the moment the row
    // comes back carrying anything, which is what an effect comparing the two would do a frame later.
    var picked by remember(place.id, place.placeCategory) { mutableStateOf<PickedCategory?>(null) }
    // What was tapped outranks what the row currently reads, until the two agree. The write is
    // asynchronous and the row comes back through the whole stay derivation, so for the length of
    // that walk the screen would otherwise hold a place that is still untagged — and the *model*,
    // which reloads off the places table directly, is already retrained on the tag just written.
    // Untagged place plus a model that has memorized this exact name is a chip for the category the
    // user has just chosen, offered beside a row still reading "Not set".
    val pending = picked
    val category = if (pending != null) pending.value else place.placeCategory
    // Keyed on the label as well as the model: a rename is new evidence about what this place is,
    // and it retrains the model that reads it.
    val suggestions = remember(suggester, place.label, category) {
        if (category == null) suggester.suggest(place.label) else emptyList()
    }
    // Scrolls rather than wraps, so the line's height never depends on how long three category
    // labels happen to be. The picker chip is last and always present — it wears the caret, which is
    // what marks a chip that opens something rather than setting a category outright — and it is the
    // tagged place's chip too, since a tagged place has no suggestions to precede it.
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        suggestions.forEach { suggestion ->
            CategoryChip(suggestion.label, suggestion.icon, placeDiscTint(suggestion)) {
                picked = PickedCategory(suggestion)
                onPick(suggestion)
            }
        }
        CategoryChip(
            label = category?.label ?: if (suggestions.isEmpty()) "Set a category" else "More",
            // Standing alone it wears the place glyph — untagged included, which `discIcon` is the
            // one place that decides. Beside suggestions it wears none: "More" has no category to
            // stand for, and a glyph about opening a picker says what the caret already says.
            icon = if (suggestions.isEmpty()) category.discIcon else null,
            tint = placeDiscTint(category),
            showsMore = true,
            // Filled once a category is set, outlined until then. Material picks a chip by who
            // authored it: a suggestion is the model's guess, the category is the user's answer, and
            // an answer already given should not look like one more thing being offered.
            selected = category != null,
        ) { choosingCategory = true }
    }
    if (choosingCategory) {
        CategorySheet(
            current = category,
            onPick = {
                picked = PickedCategory(it)
                onPick(it)
                choosingCategory = false
            },
            onDismiss = { choosingCategory = false },
        )
    }
}

/**
 * The full category list: one full-width row each, so a long label sits on its own line instead of
 * wrapping mid-list. "Not set" leads — untagging is a choice worth seeing, not a gesture (re-tapping
 * the chosen one) left to discover. Grouped by colour under headings, where the coding is learned —
 * scattering one group's colour across four runs would teach nothing — and unsorted: [PlaceCategory]
 * is declared grouped, then by how often a category is chosen, so this walks the entries once and
 * heads each run as it starts.
 *
 * A **sheet** rather than a dialog: seventeen rows carrying glyphs and group headings are what a
 * modal bottom sheet is for, where a dialog would have to scroll inside its own bounded text slot.
 * It also leaves the visit list showing behind it — deciding what a place *is* is a question the
 * screen underneath helps answer. No Cancel button: dismissing a sheet is the drag or
 * the scrim, and a picker writes on the row that is tapped rather than on a confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(
    current: PlaceCategory?,
    onPick: (PlaceCategory?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Text(
                "What is it for?",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            // Untagged leads, above the groups: it belongs to none of them.
            CategoryRow(null, selected = current == null) { onPick(null) }
            // One pass, heading each time the group changes — the categories are *declared* in
            // this order, so reading it off them can't disagree with a second list of groups.
            var heading: PlaceCategoryGroup? = null
            PlaceCategory.entries.forEach { option ->
                if (option.group != heading) {
                    heading = option.group
                    Text(
                        option.group.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 2.dp),
                    )
                }
                CategoryRow(option, selected = option == current) { onPick(option) }
            }
        }
    }
}

/**
 * One category as a row — glyph, label, and the chosen tick. Untagged (a
 * null [category]) wears the plain pin, which is what its stays show on the timeline.
 */
@Composable
private fun CategoryRow(
    category: PlaceCategory?,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    OptionRow(
        icon = category.discIcon,
        label = category?.label ?: "Not set",
        // The picker is where the color coding is learned, so a row wears its group's color.
        tint = placeDiscTint(category),
        labelColor = placeTitleColor(named = category != null),
        selected = selected,
        selectedDescription = "Current category",
        onClick = onClick,
    )
}

@Composable
private fun PlaceStatsHeader(summary: PlaceResolver.PlaceSummary) {
    StatHeaderRow(
        "Visits" to if (summary.visitCount > 0) "${summary.visitCount}" else "—",
        "Time there" to if (summary.totalMs > 0) formatDurationMs(summary.totalMs) else "—",
        "Last visit" to (summary.lastSeenMs?.let { relativeDayCompact(it) } ?: "—"),
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
    nowMs: Long,
    onOpenVisit: (StayDeriver.Stay) -> Unit,
) {
    val today = LocalDate.now(zone)
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
                    "First visit ${relativeDay(first.start)}",
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
                visitDayFormat.format(Instant.ofEpochMilli(stay.start).atZone(zone)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                visitTimeRange(stay, zone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatDurationMs((stay.end ?: nowMs) - stay.start),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** "18:18 – 08:30 +1" — the marker counts midnights crossed; the row title carries the start day. */
private fun visitTimeRange(stay: StayDeriver.Stay, zone: ZoneId): String {
    val start = timeFormat.format(Date(stay.start))
    val end = stay.end ?: return "since $start"
    val nights = ChronoUnit.DAYS.between(
        stay.start.toLocalDate(zone),
        end.toLocalDate(zone),
    )
    val rollover = if (nights > 0) " +$nights" else ""
    return "$start – ${timeFormat.format(Date(end))}$rollover"
}

private val visitDayFormat by PerLocale { DateTimeFormatter.ofPattern("EEE d", it) }

private val monthFormat by PerLocale { DateTimeFormatter.ofPattern("MMMM", it) }

private val monthYearFormat by PerLocale { DateTimeFormatter.ofPattern("MMMM yyyy", it) }

internal fun monthLabel(month: YearMonth, today: LocalDate): String =
    if (month.year == today.year) month.format(monthFormat) else month.format(monthYearFormat)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceRowCard(
    summary: PlaceResolver.PlaceSummary,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    val named = summary.isNamed
    // A tagged place says what it is in the disc, as its stays do on the timeline. Only the glyph,
    // though: a stay row spells the category out because there the category qualifies an event,
    // while here the name is the row's identity and the subtitle is already three stats long.
    // The words still reach a screen reader through the disc's description.
    val category = summary.place?.placeCategory
    ListRowCard(
        shape = shape,
        onClick = onClick,
        icon = category.discIcon,
        tint = placeDiscTint(category),
        iconDescription = category?.label,
        discAlpha = placeDiscAlpha(category),
        title = summary.place?.label ?: "Detected stop",
        titleColor = placeTitleColor(named),
        subtitle = AnnotatedString(placeSubtitle(summary)),
    )
}

private fun placeSubtitle(summary: PlaceResolver.PlaceSummary): String {
    if (summary.visitCount == 0) return "No visits yet"
    val total = formatDurationMs(summary.totalMs)
    val lastVisit = summary.lastSeenMs?.let { "last visit ${relativeDayCompact(it)}" }
    return listOfNotNull(visitCountLabel(summary.visitCount), lastVisit, total).joinToString(" · ")
}

internal fun visitCountLabel(n: Int): String = if (n == 1) "1 visit" else "$n visits"
