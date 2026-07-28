package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategoryGroup
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.PlaceSearch
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.placeCategory
import io.github.valeronm.breadcrumb.util.PerLocale
import io.github.valeronm.breadcrumb.util.SliderStops
import io.github.valeronm.breadcrumb.util.openInMaps
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
    undo: UndoSnackbar,
    onOpenPlace: (String) -> Unit,
) {
    val context = LocalContext.current
    val places by viewModel.places.collectAsStateWithLifecycle()
    // For the map's orange dots: stays the Timeline offers to merge away (TrackMerge's rules —
    // short, finished, same activity on both sides). A place that is only such an artifact is
    // marked rather than re-deciding eligibility here. Named places are exempt at the dot, not
    // here: the merge rule stopped sparing them, but a label still says the place is meant.
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

    // Chips occupy an invisible touch target (48dp minimum) around their 32dp visual height;
    // insets that should read from a chip's *visible* edge subtract this overshoot.
    val chipHalo = ((LocalMinimumInteractiveComponentSize.current - FilterChipDefaults.Height) / 2)
        .coerceAtLeast(0.dp)

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
                    PlacesChip(
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
        if (sorted.isEmpty()) {
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
                        timeline.filterIsInstance<TimelineItem.StayItem>()
                            .filter { it.merge != null }
                            .mapTo(HashSet()) { it.stay.afterTrackId to it.stay.start }
                    }
                    val mapPlaces = remember(mapVisible, mergeableStays) {
                        mapVisible.map { summary ->
                            OverviewPlace(
                                location = summary.anchor,
                                label = summary.place?.label,
                                key = summary.rowKey(),
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
                    // The filter rides on the map it declutters, top-left like the chips row in
                    // the list view; the halo subtraction keeps the visible gap at 12dp.
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 12.dp, top = 12.dp - chipHalo),
                    ) {
                        PlacesChip(
                            selected = showRareStops,
                            label = RARE_STOPS_LABEL,
                            onClick = {
                                showRareStops = !showRareStops
                                AppSettings.setPlacesShowRareStops(context, showRareStops)
                            },
                            onMap = true,
                        )
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
                itemsIndexed(listed, key = { _, s -> s.rowKey() }) { index, summary ->
                    PlaceRow(
                        summary = summary,
                        shape = groupedRowShape(index, listed.size),
                        onOpen = { onOpenPlace(summary.rowKey()) },
                        // Deleting removes the label, not the stays — they go back to being an
                        // unnamed cluster, and Undo re-pins the place exactly as it was.
                        onDelete = { place ->
                            viewModel.deletePlace(place.id)
                            undo.show("\"${place.label}\" deleted") { viewModel.restorePlace(place) }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The list's search box, shaped like the chips it sits above rather than like a form field: a Material
 * text field brings a 56dp minimum and a heavy outline, which in a header of 32dp chips reads as
 * something borrowed from another screen. Hence [BasicTextField] inside a pill of the app's own
 * making — the height, the shape and the placeholder are all this composable's to set.
 *
 * Filtering is live, so there is nothing to submit: the keyboard's Done just dismisses itself.
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

/**
 * Single-choice/filter chip in the Places header idiom: checkmark when selected. [onMap] switches
 * to an elevated chip on an opaque surface with a shadow (the track-map legend's recipe) — the
 * default tones all but vanish against the basemap.
 */
@Composable
private fun PlacesChip(selected: Boolean, label: String, onClick: () -> Unit, onMap: Boolean = false) {
    val leadingIcon: (@Composable () -> Unit)? = if (selected) {
        { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
    } else {
        null
    }
    if (onMap) {
        ElevatedFilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label) },
            leadingIcon = leadingIcon,
            colors = FilterChipDefaults.elevatedFilterChipColors(
                containerColor = MaterialTheme.colorScheme.surface,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            elevation = FilterChipDefaults.elevatedFilterChipElevation(elevation = 3.dp),
        )
    } else {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label) },
            leadingIcon = leadingIcon,
        )
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
 * Full-screen detail for one place: stats header, the place on a map, and its visits. The map
 * leads with the pin alone — the capture circle, the endpoint scatter and the neighbors belong to
 * [PlaceEditScreen], which [onAdjustArea] opens as a layer above this one.
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
    var showNameDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    Column {
                        Text(place?.label ?: "Unnamed place")
                        // What the place is for belongs with its name, not among the stats.
                        place?.placeCategory?.let { category ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    category.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = categoryColor(category),
                                )
                                Text(
                                    category.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
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
                    // The rest are a named place's: naming itself has the header CTA.
                    if (place != null) {
                        IconButton(onClick = { showNameDialog = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit place")
                        }
                        IconButton(onClick = onAdjustArea) {
                            Icon(Icons.Filled.Tune, contentDescription = "Adjust area")
                        }
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
            Card(Modifier.fillMaxWidth()) {
                PlaceStatsHeader(summary)
                if (place == null) {
                    FilledTonalButton(
                        onClick = { showNameDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) { Text("Name this place") }
                }
            }
            Card(Modifier.height(220.dp).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    MapLibrePlaceMap(
                        center = summary.anchor,
                        radiusM = summary.radiusM,
                        // The map here leads with the place alone: no circle, no endpoint dots,
                        // no neighbors. Everything they are for belongs to PlaceEditScreen — and
                        // the scatter is withheld rather than merely hidden, because the map
                        // compares it to decide whether to rebuild its marker source, and a fresh
                        // list each derivation would re-upload it all to redraw one pin.
                        endpoints = emptyList(),
                        showInternals = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (summary.stays.isEmpty()) {
                EmptyState("No visits yet", Modifier.weight(1f).fillMaxWidth())
            } else {
                PlaceVisitsList(summary.stays, onOpenVisit, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    if (showNameDialog) {
        var text by remember(place?.id) { mutableStateOf(place?.label ?: "") }
        // Naming a place is when the user knows what it is, so the category is decided in the same
        // breath — and the same dialog edits it later, so there is one screen for what a place *is*.
        var category by remember(place?.id) { mutableStateOf(place?.placeCategory) }
        // Both live inside this block, so Cancel discards an edited category with the typed name.
        var choosingCategory by remember(place?.id) { mutableStateOf(false) }
        val removing = text.isBlank() && place != null
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            icon = { Icon(category.discIcon, contentDescription = null) },
            title = { Text(if (place == null) "Name this place" else "Edit place") },
            text = {
                // The dialog bounds its text slot but doesn't scroll it (AlertDialogContent gives it
                // `weight(1f, fill = false)`), so a tall picker would clip the buttons off instead.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("Place name") },
                        // Place names are proper nouns — capitalize each word.
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                    Spacer(Modifier.height(12.dp))
                    CategorySummaryRow(category) { choosingCategory = true }
                    if (place != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Clear the name to remove this place.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = text.trim()
                        when {
                            trimmed.isEmpty() && place != null -> viewModel.deletePlace(place.id)
                            // Both writes are guarded: either one invalidates the places table, and
                            // the derivation every screen reads runs again off it — so a dialog
                            // dismissed with nothing changed must cost nothing.
                            place != null -> {
                                if (trimmed != place.label) viewModel.renamePlace(place.id, trimmed)
                                if (category != place.placeCategory) {
                                    viewModel.setPlaceCategory(place.id, category)
                                }
                            }
                            trimmed.isNotEmpty() -> viewModel.createPlace(
                                summary.centroid.lat, summary.centroid.lon, trimmed, category,
                            )
                        }
                        showNameDialog = false
                    },
                    enabled = text.isNotBlank() || place != null,
                ) { Text(if (removing) "Remove" else "Save") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("Cancel") }
            },
        )

        // Stacked over the naming dialog rather than replacing it, so the typed name is still there
        // (and still on screen underneath) when the picker closes.
        if (choosingCategory) {
            CategoryDialog(
                current = category,
                onPick = {
                    category = it
                    choosingCategory = false
                },
                onDismiss = { choosingCategory = false },
            )
        }
    }
}

/**
 * Tuning one named place's capture area: the radius slider over a full-height map showing what the
 * circle would take — this place's endpoints and the loose ones around it, the named neighbors it
 * competes with, and their areas muted underneath.
 *
 * **A layer of its own, above the place detail, and the map is the reason.** The two screens want
 * the map at different heights, and a `MapView` is a `TextureView`: handed a new size it scales its
 * last-rendered frame into the new box until it has one of its own, which is the pin visibly
 * stretching to an oval. Nothing fixes that from inside one shared map — hiding what is drawn is
 * too late (the frame being scaled was rendered before the hide), and animating the height makes it
 * every frame instead of one. A map per screen is never resized, so there is nothing to hide,
 * sequence or animate around.
 *
 * Its camera is its own too, and that is the point rather than the price: this screen opens framed
 * on the circle, in a state the radius can be judged in, wherever the detail map below happened to
 * be panned to.
 *
 * Backing out is the layer's own predictive-back gesture, and it discards by construction —
 * [radiusM] is local and nothing is written until Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceEditScreen(
    summary: PlaceResolver.PlaceSummary,
    neighbors: List<NeighborPlace>,
    candidates: List<StayDeriver.Endpoint>,
    rivals: List<PlaceClusterer.Seed>,
    viewModel: TrackListViewModel,
    onClose: () -> Unit,
) {
    val place = summary.place ?: return
    var showRecenterDialog by remember { mutableStateOf(false) }
    // The circle previews live, but nothing is written until Done. A radius write re-derives the
    // whole timeline, so committing per drag re-derived it several times for one adjustment.
    var radiusM by remember(place.id) { mutableFloatStateOf(summary.radiusM.toFloat()) }
    val radiusScale = rememberDistanceScale(SliderStops(50, 500, 25), SliderStops(150, 1650, 75))
    // Prepared once when the screen opens, not per drag step: whether a neighbor keeps an endpoint
    // has nothing to do with our radius, and paying for that scan on every step is what made a
    // place with a few thousand endpoints around it lag under the finger. See CaptureScan.
    //
    // Keyed on what the scan reads, not on the whole summary — that carries visit stats which move
    // on every derivation and would rebuild this for nothing.
    val scan = remember(summary.anchor, candidates, rivals, radiusScale) {
        PlaceClusterer.scanCapture(
            candidates = candidates,
            anchor = summary.anchor,
            maxRadiusM = radiusScale.metersOf(radiusScale.range.endInclusive).toDouble(),
            rivals = rivals,
            distance = AndroidDistance,
        )
    }
    val captureDots = remember(scan) {
        // Conceded dots carry no distance — a nearer pin holds them at any radius, so the map
        // must draw them settled rather than compare them.
        scan.winnable.map { CaptureDot(it.location, it.distanceM) } +
            scan.conceded.map { CaptureDot(it, null) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    Column {
                        Text(place.label)
                        // What the circle holds *now*, not what the stored radius holds: the count
                        // is the same answer the dots below are showing, so the two can't disagree
                        // as the slider moves.
                        val captured = scan.countWithin(radiusM.toDouble())
                        Text(
                            "$captured recorded track ${if (captured == 1) "endpoint" else "endpoints"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { BackNavIcon(onClose) },
                actions = {
                    if (summary.endpointCentroid != null) {
                        IconButton(onClick = { showRecenterDialog = true }) {
                            Icon(Icons.Filled.FilterCenterFocus, contentDescription = "Re-center pin")
                        }
                    }
                    IconButton(
                        onClick = {
                            if (radiusM.toDouble() != summary.radiusM) {
                                viewModel.setPlaceRadius(place.id, radiusM.toDouble())
                            }
                            onClose()
                        },
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
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    SliderSetting("Place radius", radiusM.roundToInt(), radiusScale) {
                        radiusM = it.toFloat()
                    }
                }
            }
            Card(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    MapLibrePlaceMap(
                        center = summary.anchor,
                        radiusM = radiusM.toDouble(),
                        endpoints = summary.endpoints,
                        neighbors = neighbors,
                        capture = captureDots,
                        rivalAreas = rivals,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (showRecenterDialog && summary.endpointCentroid != null) {
        ConfirmDialog(
            icon = Icons.Filled.FilterCenterFocus,
            title = "Re-center pin?",
            text = "Moves \"${place.label}\" to the middle of where your visits actually landed. " +
                "Visits and stats recalculate around the new spot.",
            confirmLabel = "Move",
            onConfirm = {
                viewModel.setPlacePin(place.id, summary.endpointCentroid.lat, summary.endpointCentroid.lon)
                showRecenterDialog = false
            },
            onDismiss = { showRecenterDialog = false },
        )
    }
}

/**
 * The current category as one row in the naming dialog, opening the picker. A row rather than the
 * twelve chips this started as: in a dialog's width the chips wrapped into a ragged block, where
 * "Home" and "Friends & family" are half a screen apart in size.
 */
@Composable
private fun CategorySummaryRow(category: PlaceCategory?, onClick: () -> Unit) {
    Column {
        Text(
            "What is it for?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        CategoryRow(category, trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }, onClick = onClick)
    }
}

/**
 * The category list, built like the track-type dialog: one full-width row each, so a long label sits
 * on its own line instead of wrapping mid-list. "Not set" leads it — untagging is a choice worth
 * seeing, not a gesture (tapping the chosen one again) left for the user to discover.
 *
 * Grouped by color, under a heading each — this is where the coding is learned, and a list that
 * scattered one group's color across four separate runs would teach nothing. No sorting happens
 * here: [PlaceCategory] is declared in exactly this order (grouped, then by how often a category is
 * chosen), so the picker walks the entries once and heads each run as it starts.
 */
@Composable
private fun CategoryDialog(
    current: PlaceCategory?,
    onPick: (PlaceCategory?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(current.discIcon, contentDescription = null) },
        title = { Text("What is it for?") },
        text = {
            // This many rows outgrow a dialog, whose text slot is bounded but doesn't scroll itself.
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One category as a row — glyph, label, and either a trailing slot or the chosen tick. Untagged (a
 * null [category]) wears the plain pin, which is what its stays show on the timeline.
 */
@Composable
private fun CategoryRow(
    category: PlaceCategory?,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
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
        trailing = trailing,
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

/** The place's visit history, newest first, grouped under month headers. */
@Composable
private fun PlaceVisitsList(
    stays: List<StayDeriver.Stay>,
    onOpenVisit: (StayDeriver.Stay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val nowMs = remember { System.currentTimeMillis() }
    // Stays arrive newest first, so groupBy preserves month order and in-month order.
    val groups = remember(stays) {
        stays.groupBy { YearMonth.from(it.start.toLocalDate(zone)) }
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        // The history ends where it began — a quiet marker instead of a stat-card factoid.
        stays.lastOrNull()?.let { first ->
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
        title = summary.place?.label ?: "Unnamed place",
        titleColor = placeTitleColor(named),
        subtitle = AnnotatedString(placeSubtitle(summary)),
    )
}

/**
 * Stable place identity: the place id for named places, the centroid for ephemeral unnamed
 * clusters. Shared by the Places list keys, the detail overlay, and stay-row tap-through —
 * a stay's [PlaceResolver.ResolvedStay] carries the same placeId/centroid pair.
 */
internal fun placeDetailKeyOf(placeId: Long?, centroid: StayDeriver.Endpoint): String =
    placeId?.let { "place:$it" } ?: "cluster:%.5f,%.5f".format(centroid.lat, centroid.lon)

internal fun PlaceResolver.PlaceSummary.rowKey(): String = placeDetailKeyOf(place?.id, centroid)

private fun placeSubtitle(summary: PlaceResolver.PlaceSummary): String {
    if (summary.visitCount == 0) return "No visits yet"
    val total = formatDurationMs(summary.totalMs)
    val lastVisit = summary.lastSeenMs?.let { "last visit ${relativeDayCompact(it)}" }
    return listOfNotNull(visitCountLabel(summary.visitCount), lastVisit, total).joinToString(" · ")
}

internal fun visitCountLabel(n: Int): String = if (n == 1) "1 visit" else "$n visits"
