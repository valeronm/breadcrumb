package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.util.PerLocale
import io.github.valeronm.breadcrumb.util.SliderStops
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

/** How far around a place the detail map shows neighboring clusters for radius context. */
internal const val NEIGHBOR_CONTEXT_M = 1_200.0

/**
 * Unnamed clusters with fewer visits than this are "rare stops": hidden on the map unless its
 * "Rare stops" chip is on, and sorted to the tail of the list view.
 */
private const val RARE_UNNAMED_MIN_VISITS = 3

private fun PlaceResolver.PlaceSummary.isRareStop() =
    !isNamed && visitCount < RARE_UNNAMED_MIN_VISITS

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
    // marked rather than re-deciding eligibility here.
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    var showMap by remember { mutableStateOf(AppSettings.placesViewMap(context)) }
    var sort by remember { mutableStateOf(PlacesSort.fromSettings(context)) }
    var showRareUnnamed by remember { mutableStateOf(AppSettings.placesShowRareUnnamed(context)) }

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
    // The rare-stops chip declutters the map only. Note the off default also hides the map's
    // orange brief-stop dots — one-off stops are rare unnamed clusters by definition. The list
    // never filters: rare stops sort to its tail instead (sortedBy is stable, so the chosen
    // sort order is preserved within each half).
    val visible = remember(sorted, showMap, showRareUnnamed) {
        when {
            !showMap -> sorted.sortedBy { it.isRareStop() }
            showRareUnnamed -> sorted
            else -> sorted.filterNot { it.isRareStop() }
        }
    }

    // Chips occupy an invisible touch target (48dp minimum) around their 32dp visual height;
    // insets that should read from a chip's *visible* edge subtract this overshoot.
    val chipHalo = ((LocalMinimumInteractiveComponentSize.current - FilterChipDefaults.Height) / 2)
        .coerceAtLeast(0.dp)

    Column(Modifier.fillMaxSize()) {
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
        }
        if (visible.isEmpty()) {
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
                    val mapPlaces = remember(visible, mergeableStays) {
                        visible.map { summary ->
                            OverviewPlace(
                                location = summary.anchor,
                                label = summary.place?.label,
                                key = summary.rowKey(),
                                brief = summary.stays.singleOrNull()
                                    ?.let { (it.afterTrackId to it.start) in mergeableStays }
                                    ?: false,
                            )
                        }
                    }
                    MapLibrePlacesMap(
                        places = mapPlaces,
                        onOpen = onOpenPlace,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // The filter rides on the map it declutters, top-left like the chips row in
                    // the list view; the halo subtraction keeps the visible gap at 12dp.
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 12.dp, top = 12.dp - chipHalo),
                    ) {
                        PlacesChip(
                            selected = showRareUnnamed,
                            label = "Rare stops",
                            onClick = {
                                showRareUnnamed = !showRareUnnamed
                                AppSettings.setPlacesShowRareUnnamed(context, showRareUnnamed)
                            },
                            onMap = true,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(visible, key = { _, s -> s.rowKey() }) { index, summary ->
                    PlaceRow(
                        summary = summary,
                        shape = groupedRowShape(index, visible.size),
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
 * Full-screen detail for one place: stats header, the cluster on a map (capture circle +
 * endpoints), and — for named places — a live radius slider (the circle previews while dragging;
 * the value persists on release and the derivation re-clusters reactively).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceDetailScreen(
    summary: PlaceResolver.PlaceSummary,
    neighbors: List<NeighborPlace>,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onOpenVisit: (StayDeriver.Stay) -> Unit,
) {
    val place = summary.place
    var showNameDialog by remember { mutableStateOf(false) }
    var showRecenterDialog by remember { mutableStateOf(false) }
    // Edit mode reveals the cluster internals (capture circle, endpoints, neighbors) plus the
    // radius slider and re-center action; view mode leads with stats, a clean map and visits.
    var editing by remember(place?.id) { mutableStateOf(false) }
    // Local while dragging; summary.radiusM catches up after the persisted value re-derives.
    var radiusM by remember(place?.id) { mutableFloatStateOf(summary.radiusM.toFloat()) }
    // Where the pin would move: the mean of the endpoints the cluster currently captures.
    val endpointCentroid = remember(summary.endpoints) {
        summary.endpoints.takeIf { it.isNotEmpty() }?.let { pts ->
            StayDeriver.Endpoint(pts.sumOf { it.lat } / pts.size, pts.sumOf { it.lon } / pts.size)
        }
    }
    // Back steps out of edit mode before it closes the screen.
    BackHandler(enabled = editing) { editing = false }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    Column {
                        Text(place?.label ?: "Unnamed place")
                        if (editing) {
                            Text(
                                "${summary.endpoints.size} recorded track endpoints",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackNavIcon { if (editing) editing = false else onBack() } },
                actions = {
                    if (editing) {
                        if (place != null && endpointCentroid != null) {
                            IconButton(onClick = { showRecenterDialog = true }) {
                                Icon(
                                    Icons.Filled.FilterCenterFocus,
                                    contentDescription = "Re-center pin",
                                )
                            }
                        }
                        IconButton(onClick = { editing = false }) {
                            Icon(Icons.Filled.Check, contentDescription = "Done")
                        }
                    } else if (place != null) {
                        // Unnamed places get no top-bar actions: naming has the header CTA.
                        IconButton(onClick = { showNameDialog = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Rename place")
                        }
                        IconButton(onClick = { editing = true }) {
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
            if (!editing) {
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
            } else if (place != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        val scale = rememberDistanceScale(SliderStops(50, 500, 25), SliderStops(150, 1650, 75))
                        SliderSetting(
                            "Place radius", radiusM.roundToInt(), scale,
                            onDragEnd = { viewModel.setPlaceRadius(place.id, radiusM.toDouble()) },
                        ) { radiusM = it.toFloat() }
                    }
                }
            }
            // One card at one call site in both modes, so the MapView survives the mode switch
            // and only restyles (internals on/off) instead of reloading.
            Card(
                if (editing) {
                    Modifier.weight(1f).fillMaxWidth()
                } else {
                    Modifier.height(220.dp).fillMaxWidth()
                },
            ) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    MapLibrePlaceMap(
                        center = summary.anchor,
                        radiusM = if (place != null) radiusM.toDouble() else summary.radiusM,
                        endpoints = summary.endpoints,
                        neighbors = neighbors,
                        showInternals = editing,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (!editing) {
                if (summary.stays.isEmpty()) {
                    EmptyState("No visits yet", Modifier.weight(1f).fillMaxWidth())
                } else {
                    PlaceVisitsList(summary.stays, onOpenVisit, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }

    if (showRecenterDialog && place != null && endpointCentroid != null) {
        ConfirmDialog(
            icon = Icons.Filled.FilterCenterFocus,
            title = "Re-center pin?",
            text = "Moves \"${place.label}\" to the middle of where your visits actually landed. " +
                "Visits and stats recalculate around the new spot.",
            confirmLabel = "Move",
            onConfirm = {
                viewModel.setPlacePin(place.id, endpointCentroid.lat, endpointCentroid.lon)
                showRecenterDialog = false
            },
            onDismiss = { showRecenterDialog = false },
        )
    }

    if (showNameDialog) {
        var text by remember(place?.id) { mutableStateOf(place?.label ?: "") }
        val removing = text.isBlank() && place != null
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            icon = { Icon(Icons.Filled.Place, contentDescription = null) },
            title = { Text(if (place == null) "Name this place" else "Rename place") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("Place name") },
                        // Place names are proper nouns — capitalize each word.
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
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
                            place != null -> viewModel.renamePlace(place.id, trimmed)
                            trimmed.isNotEmpty() -> viewModel.createPlace(
                                summary.centroid.lat, summary.centroid.lon, trimmed,
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
    }
}

@Composable
private fun PlaceStatsHeader(summary: PlaceResolver.PlaceSummary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        HeaderStat(
            "Visits",
            if (summary.visitCount > 0) "${summary.visitCount}" else "—",
            Modifier.weight(1f),
        )
        HeaderStat(
            "Time there",
            if (summary.totalMs > 0) formatDurationMs(summary.totalMs) else "—",
            Modifier.weight(1f),
        )
        HeaderStat(
            "Last visit",
            summary.lastSeenMs?.let { relativeDayCompact(it) } ?: "—",
            Modifier.weight(1f),
        )
    }
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
    Card(modifier = Modifier.fillMaxWidth(), shape = shape, onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (named) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            TonalIconDisc(Icons.Filled.Place, tint, contentDescription = null, discAlpha = 0.16f)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    summary.place?.label ?: "Unnamed place",
                    style = MaterialTheme.typography.titleMedium,
                    color = placeTitleColor(named),
                )
                Text(
                    placeSubtitle(summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
