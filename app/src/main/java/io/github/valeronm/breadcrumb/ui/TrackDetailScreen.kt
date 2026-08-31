package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.TrackPoints
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Clocks
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayIgnore
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.KeepRule
import io.github.valeronm.breadcrumb.domain.RoutePlaces
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.TrackSplit
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.avgSpeedKmh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

@Composable
internal fun TrackMapScreen(
    trackId: Long,
    summary: TrackSummary?,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    /**
     * Cut this track in two at the given timestamp; the caller performs the split, offers the undo
     * and closes this screen. Null where splitting doesn't apply, and required rather than
     * defaulted so that a new caller has to say which it is instead of inheriting silence.
     */
    onSplit: ((Long) -> Unit)?,
    /**
     * Open the trip form on this track — offered only for a manual one, whose whole content is the
     * two ends the form asks for. Null where the caller has nowhere to open it, and undefaulted for
     * the reason [onSplit] is.
     */
    onEditTrip: ((TripDraft) -> Unit)?,
) {
    val context = LocalContext.current
    // One load of everything the screen draws (null = still reading), keyed on the row as well as
    // the id. Nothing observes `track_points` — such a query would re-run on every GPS fix (see
    // CLAUDE.md) — so the row standing in lets the screen notice a re-derivation it didn't ask for:
    // a retype re-runs the overrun rule and hands back what the new jump ceiling accepts
    // (`TrackRepository.setActivityType`); keyed on the id alone, the header would update from the
    // summary flow while the line and grayed edges kept the pre-retype shape until reopen. A retype
    // that moves nothing re-reads for nothing — a rare tap, invisible because produceState keeps
    // what it has while the new query runs.
    val trackPoints by produceState<TrackPoints?>(initialValue = null, trackId, summary) {
        value = viewModel.getTrackPoints(trackId)
    }
    // The clocks this track's two ends ran on, so opening a row does not change the times it showed.
    // Seeded with the reader's until the derivation answers — a track that never left home is
    // already right, and one that did corrects itself in the time the query takes.
    val reader = timelineZone()
    val zones by produceState(Clocks.both(reader), trackId) {
        value = viewModel.zonesOfTrack(trackId)
    }
    val startZone = zones.start
    val endZone = zones.end
    val points = trackPoints?.good
    val noisyPoints = trackPoints?.noisy
    val stayPoints = trackPoints?.edgeStay.orEmpty()
    // Embedded stays: venue-scale dwells detected from the loaded points (see DwellDetector).
    val dwells by produceState(initialValue = emptyList(), points) {
        value = points?.let { pts ->
            withContext(Dispatchers.Default) {
                DwellDetector.detect(pts, DwellDetector.TRACK_OVERLAY, AndroidDistance)
            }
        } ?: emptyList()
    }
    // Recording that ran on past the stop at either edge, grayed on the map: the stored fixes
    // grouped back into one run per edge, each hanging off the good fix that ends the track.
    val overruns = remember(points, stayPoints) {
        EdgeStayIgnore.overruns(points.orEmpty(), stayPoints)
    }
    // The named places this journey set out from and arrived at. Off the stored rows rather than the
    // derived summaries: a pin and its reach are all the rule and the map read, and neither has
    // anything to learn from a visit count worth waiting for the clustering over.
    //
    // A plain remember, unlike the dwell walk above: this reads two fixes and prunes the places on
    // their coordinates, so a dispatcher hop would cost more than the work — and arriving in the
    // first composition means the pins are there when the style loads, instead of a source rebuilt a
    // frame later.
    val storedPlaces by viewModel.storedPlaces.collectAsStateWithLifecycle()
    val endPlaces = remember(points, storedPlaces) {
        RoutePlaces.ends(points.orEmpty(), storedPlaces, AndroidDistance)
    }
    // The one seam walk this screen's derived series come from, keyed on the points alone — it is
    // the same distances whatever metric is displayed, and the graph, the map's colors and the
    // split preview are all built from it. Switching the metric then re-runs only the ramp, not an
    // ellipsoidal distance per point. Hoisted this high because the split dialog is a sibling of
    // the Scaffold, not part of the column that draws the track.
    val seams = remember(points) { TrackQuality.seams(points.orEmpty()) }
    val activity = remember(summary) {
        summary?.let { ActivityType.ofName(it.activityType) }
    }
    val source = TrackOrigin.fromCode(summary?.source)
    val colorModes = remember(points, source) { availableColorModes(points.orEmpty(), source) }
    var selectedMode by remember { mutableStateOf(ColorMode.SPEED) }
    // The points arrive after the first composition, so a mode can be selected and then turn out to
    // have nothing behind it — on a track opened from a metric the last one had.
    val colorMode = if (selectedMode in colorModes) selectedMode else ColorMode.SPEED
    // Noisy (ignored) fixes are hidden by default, behind the map's own chip.
    // A track with no drawable line is the exception — its noisy fixes are all there is to see, so
    // the default follows the points once they load, until the user says otherwise.
    var showNoisyOverride by remember(trackId) { mutableStateOf<Boolean?>(null) }
    val showNoisy = showNoisyOverride ?: (points?.let { it.size < KeepRule.MIN_LINE_POINTS } == true)
    // Point picked on the metric graph, highlighted on the map. An index into the list above, so it
    // is keyed on that list: one kept across a reload names a different fix than the user tapped.
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    var showTypeDialog by remember(trackId) { mutableStateOf(false) }
    var showSplitDialog by remember(trackId) { mutableStateOf(false) }
    // Whether this track can be cut at all — a caller that handles the split, and a track that has
    // finished, since a still-recording one's last edge is still moving.
    val splittable = onSplit != null && summary?.endedAt != null
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                // The bar carries what the track *is*; when it happened is a line of its own below,
                // where it reads at body size instead of as a caption under a title.
                title = {
                    Text(
                        summary?.let { activityLabel(LocalContext.current, it.activityType) }
                            ?: stringResource(R.string.track_title),
                    )
                },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (summary != null) {
                        // One pencil, meaning "change what this track says it was". On a recorded or
                        // imported track that is its type and nothing else — the fixes are a
                        // measurement or a file's. A manual track is typed in whole, so the pencil
                        // opens the form that typed it, type chips included; a second action beside
                        // this one would offer the type from two places.
                        val editTrip = onEditTrip?.takeIf { source == TrackOrigin.MANUAL }
                        IconButton(
                            onClick = {
                                if (editTrip == null) {
                                    showTypeDialog = true
                                } else {
                                    editTrip(tripDraftOf(summary, points, storedPlaces, startZone))
                                }
                            },
                        ) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(
                                    if (editTrip != null) R.string.track_edit_trip else R.string.track_change_type,
                                ),
                            )
                        }
                    }
                    IconButton(onClick = {
                        viewModel.importExport.shareTracks(listOf(trackId)) { intent ->
                            if (intent != null) context.startActivity(intent)
                        }
                    }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.track_share_gpx),
                        )
                    }
                },
            )
        },
    ) { inner ->
        Box(modifier = Modifier.padding(inner).fillMaxSize().clipToBounds()) {
            val load = trackPoints
            when {
                load == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                // A track without a drawable line still gets the map when it has noisy fixes to
                // mark — a bad-points-only track is exactly what the noisy overlay is for.
                load.good.size < KeepRule.MIN_LINE_POINTS && load.noisy.isEmpty() -> Text(
                    stringResource(R.string.track_too_few_points),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (summary != null) {
                        // Each end on its own clock, marked where it differs from the reader's — a
                        // track can cross a border, and the row this screen opened from says so.
                        val shiftColor = zoneShiftColor
                        val readerClock = LocalReaderClock.current
                        Text(
                            buildAnnotatedString {
                                appendDateTime(
                                    summary.startedAt, startZone, reader, shiftColor, readerClock,
                                )
                                summary.endedAt?.let { endedAt ->
                                    append(" – ")
                                    appendTime(endedAt, endZone, reader, shiftColor, readerClock)
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Card(Modifier.fillMaxWidth()) { TrackStatsHeader(summary) }
                    }
                    val darkTheme = isSystemInDarkTheme()
                    // No modes means no graph and no chips — a manual track's typed fixes carry no
                    // metric at all, and which writers can say that is availableColorModes's call,
                    // not this screen's.
                    val measures = LocalMeasures.current
                    val graph = remember(seams, colorMode, activity, darkTheme, measures, colorModes) {
                        if (colorModes.isEmpty()) {
                            null
                        } else {
                            metricGraphData(seams, colorMode, activity, darkTheme, measures)
                        }
                    }
                    // The chips carry no surface of their own: they are the control for the map
                    // below, not a block of content beside it, and a card around a row of chips
                    // reads as a third thing to look at.
                    if (colorModes.isNotEmpty()) {
                        ColorModeSelector(
                            colorMode,
                            colorModes,
                            // Only the import is named: a recording is what a track is, and a
                            // word on every other one would say nothing.
                            caption = stringResource(R.string.track_caption_imported)
                                .takeIf { source == TrackOrigin.IMPORTED },
                        ) { selectedMode = it }
                    }
                    // Map and scrubber read as one group: small gaps, small corners between them.
                    Column(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val blocks = if (graph != null) 2 else 1
                        // The map card takes the stretch.
                        Card(Modifier.weight(1f).fillMaxWidth(), shape = groupedRowShape(0, blocks)) {
                            Box(Modifier.fillMaxSize().clipToBounds()) {
                                MapLibreTrackMap(
                                    points = load.good,
                                    noisyPoints = if (showNoisy) load.noisy else emptyList(),
                                    activity = activity,
                                    colorMode = colorMode,
                                    showLegend = true,
                                    selectedPoint = selectedIndex?.let { load.good.getOrNull(it) },
                                    dwells = dwells,
                                    overruns = overruns,
                                    endPlaces = endPlaces,
                                    precomputedColoring = graph?.coloring,
                                    precomputedSeams = seams,
                                    // A manual track's legs are typed, not travelled — drawn along
                                    // the great circle rather than as projected chords.
                                    greatCircleLegs = source == TrackOrigin.MANUAL,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (showNoisy) {
                                    // Top-right, clear of the color-metric legend (bottom-right).
                                    NoisyLegend(load.noisy, Modifier.align(Alignment.TopEnd).padding(12.dp))
                                }
                                if (!noisyPoints.isNullOrEmpty()) {
                                    // On the map rather than in the bar: it shows and hides marks
                                    // drawn here.
                                    MapFilterChip(
                                        selected = showNoisy,
                                        label = stringResource(R.string.track_filter_noisy),
                                    ) {
                                        showNoisyOverride = !showNoisy
                                    }
                                }
                            }
                        }
                        if (graph != null) {
                            Card(Modifier.fillMaxWidth(), shape = groupedRowShape(1, blocks)) {
                                MetricGraph(
                                    graph = graph,
                                    zone = startZone,
                                    selectedIndex = selectedIndex,
                                    onSelect = { selectedIndex = it },
                                    // The cut is offered on the scrubber, beside the fix it would
                                    // cut at. Null where splitting doesn't apply at all.
                                    onSplitRequested = { showSplitDialog = true }.takeIf { splittable },
                                    // Both halves of "can this be cut" stay here, with the track
                                    // they are about: the plot renders the offer it is handed.
                                    canCutAt = { index ->
                                        TrackSplit.isLegalCut(index, (points?.size ?: 0) - index)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(130.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTypeDialog && summary != null) {
        AlertDialog(
            onDismissRequest = { showTypeDialog = false },
            icon = { Icon(activityIcon(activity), contentDescription = null) },
            title = { Text(stringResource(R.string.track_type_dialog_title)) },
            text = {
                Column {
                    // Selecting applies immediately: the summary flow re-emits and the title,
                    // icon, colors and speed scale all follow — and so does the drawn path, on a
                    // choice that re-derives the overrun (see the point load above).
                    for (option in ActivityType.entries.filter { it.recording && it != ActivityType.UNKNOWN }) {
                        OptionRow(
                            icon = activityIcon(option),
                            label = stringResource(option.labelRes),
                            // Each option in its own hue, as the category picker wears its groups'.
                            tint = activityColor(option),
                            selected = option == activity,
                        ) {
                            viewModel.setTrackActivity(trackId, option)
                            showTypeDialog = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTypeDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    SplitConfirmation(
        load = trackPoints,
        seams = seams,
        summary = summary,
        // Read only while the dialog is up: the scrubber writes the selection per drag event, so a
        // read in this scope would recompose the whole Scaffold on every touch move.
        cutIndex = if (showSplitDialog) selectedIndex else null,
        zone = startZone,
        onSplit = onSplit,
        onDismiss = { showSplitDialog = false },
    )
}

/**
 * Confirm a split, showing both halves as they will read on the timeline. Reversible from the undo
 * snackbar, yet the one action here turning one track into two, so it asks first. Draws nothing
 * until every piece of a legal cut is present — hence the preconditions here, not at the call site:
 * a non-null [cutIndex] (dialog up on a selected point), a finished track, a willing caller, and a
 * plan [TrackSplit] accepts.
 */
@Composable
private fun SplitConfirmation(
    load: TrackPoints?,
    seams: TrackQuality.Seams,
    summary: TrackSummary?,
    cutIndex: Int?,
    /** The clock the cut and both halves are stated on — the track's start, as the graph's axis
     *  is: a cut is a point on that axis, not an arrival. */
    zone: ZoneId,
    onSplit: ((Long) -> Unit)?,
    onDismiss: () -> Unit,
) {
    if (load == null || cutIndex == null || onSplit == null) return
    if (summary == null) return
    val trackEnd = summary.endedAt ?: return
    // The plan runs once per dialog open rather than once per scrub tick. The load holds the points
    // in three lists and the plan reads only counts and extremes, so concatenating needs no sort.
    val plan = remember(load, cutIndex) {
        val cutTs = load.good.getOrNull(cutIndex)?.timestamp ?: return@remember null
        TrackSplit.plan(load.good + load.noisy + load.edgeStay, cutTs)
    } ?: return
    // The legs either side of the cut, off the walk the screen already did. The leg *across* the cut
    // is in neither, because after the split it stops being a leg at all — it is the gap between two
    // tracks. A half whose new inner edge turns out to hold an overrun ends up marginally shorter
    // than this, the fixes still on it but off its path.
    val firstMeters = remember(seams, cutIndex) { (1 until cutIndex).sumOf { seams.meters[it] } }
    val secondMeters = remember(seams, cutIndex) {
        (cutIndex + 1 until seams.points.size).sumOf { seams.meters[it] }
    }
    val reader = timelineZone()
    val shiftColor = zoneShiftColor
    // All of these resolve before the builders below, none of which is a composable scope.
    val readerClock = LocalReaderClock.current
    val splitAt = annotatedStringResource(
        R.string.track_split_at,
        markedTime(plan.cutTs, zone, reader, shiftColor, readerClock),
    )
    val firstPoints = pluralStringResource(R.plurals.track_points, plan.firstGoodPoints, plan.firstGoodPoints)
    val secondPoints =
        pluralStringResource(R.plurals.track_points, plan.secondGoodPoints, plan.secondGoodPoints)
    val firstDistance = distanceText(firstMeters)
    val secondDistance = distanceText(secondMeters)
    fun half(from: Long, to: Long, points: String, distance: String) = buildAnnotatedString {
        appendTime(from, zone, reader, shiftColor, readerClock)
        append(" – ")
        appendTime(to, zone, reader, shiftColor, readerClock)
        append(" · $distance · $points")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
        title = {
            Text(splitAt)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(half(summary.startedAt, plan.firstEndTs, firstPoints, firstDistance))
                Text(half(plan.secondStartTs, trackEnd, secondPoints, secondDistance))
                Text(
                    stringResource(R.string.track_split_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onSplit(plan.cutTs)
            }) { Text(stringResource(R.string.track_split_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Per-point series for the metric graph. Everything but the points comes off one [TrackColoring],
 * which is also the map's — the series a stroke's height is read from is the series its hue was
 * read from, because there is only one of them.
 */
@Immutable
internal class MetricGraphData(
    val points: List<TrackPoint>,
    /** Shared with the map (via `precomputedColoring`) so the O(points) pass runs once. */
    val coloring: TrackColoring,
) {
    val values: List<Float?> get() = coloring.values
    val colors: IntArray get() = coloring.colors
    val unit: String get() = coloring.unit
}

/** Null when no point carries the metric. */
internal fun metricGraphData(
    seams: TrackQuality.Seams,
    mode: ColorMode,
    activity: ActivityType?,
    dark: Boolean,
    measures: Measures,
): MetricGraphData? {
    val coloring = trackColoring(
        seams.points, TrackQuality.pointSpeedsKmh(seams), mode, activity, dark, measures,
    )
    // The ramp already reached this verdict when it found nothing to scale.
    if (coloring.legend is Legend.None) return null
    return MetricGraphData(seams.points, coloring)
}

/**
 * The metric polyline alone, rasterized once per (graph, size) into a bitmap blitted per frame:
 * the cursor overlay invalidates every frame, and thousands of drawLine ops each lag long-track
 * scrubbing — cached, the plot is one drawImage. Takes only the immutable series and scale, never
 * the selection, so Compose also skips it.
 */
@Composable
private fun MetricPlot(
    graph: MetricGraphData,
    minV: Float,
    span: Float,
    t0: Long,
    tSpan: Float,
    modifier: Modifier,
) {
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    val padPx = with(LocalDensity.current) { 8.dp.toPx() }
    Spacer(
        modifier.drawWithCache {
            val bitmap = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            CanvasDrawScope().draw(this, layoutDirection, ComposeCanvas(bitmap), size) {
                val h = size.height - 2 * padPx
                var prev: Offset? = null
                for (i in graph.points.indices) {
                    val v = graph.values[i]
                    if (v == null) {
                        prev = null
                        continue
                    }
                    if (graph.points[i].segmentStart) prev = null
                    val x = (graph.points[i].timestamp - t0) / tSpan * size.width
                    val y = padPx + h - ((v - minV) / span) * h
                    val current = Offset(x, y)
                    prev?.let { drawLine(Color(graph.colors[i]), it, current, strokeWidth = strokePx) }
                    prev = current
                }
            }
            onDrawBehind { drawImage(bitmap) }
        },
    )
}

/**
 * The cut on offer at the scrubbed fix: what is there, and the action on it. Shown only between
 * gestures (see `offerCut`), and **disabled rather than absent** where the cut is illegal — a rule
 * the user meets by aiming at it is worth stating, and an offer that simply fails to appear reads
 * as a missed gesture.
 */
@Composable
private fun CutOffer(reading: AnnotatedString, canCut: Boolean, onCut: () -> Unit, modifier: Modifier) {
    val actionColor =
        if (canCut) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    // The surface every other thing that floats over this screen's map already wears — the legends
    // and the ramp — so what it reads against is a property of the app rather than of this one card.
    // The clip is what keeps the ripple inside those corners; it takes the surface's own shape.
    LegendSurface(
        modifier
            .clip(legendShape)
            .clickable(enabled = canCut, onClick = onCut),
    ) {
        Text(
            reading,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ContentCut,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = actionColor,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (canCut) R.string.track_split_here else R.string.track_split_too_close),
                style = MaterialTheme.typography.labelMedium,
                color = actionColor,
            )
        }
    }
}

/**
 * The selected color metric over the track's time span, stroked point-to-point in the map line's
 * colors, with a time axis; missing values and segment starts break the line. Tap/drag picks the
 * nearest point ([onSelect]), drawn as a cursor with a value/time readout; the caller highlights
 * the same point on the map.
 */
@Composable
private fun MetricGraph(
    graph: MetricGraphData,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    /** Open the split confirmation for the scrubbed fix; null where the track can't be cut. */
    onSplitRequested: (() -> Unit)?,
    /** Whether a cut at this index is one the repository would accept — the caller's rule, since
     *  the track it applies to is the caller's, not the plot's. */
    canCutAt: (Int) -> Boolean,
    /** The clock the time axis is read on — **the track's start**, even where it ended on another.
     *  The axis is one continuum, and labelling its far end on the arrival's clock would make it
     *  read as non-monotonic; the header above states both ends, which is where the crossing shows. */
    zone: ZoneId,
    modifier: Modifier,
) {
    val reader = timelineZone()
    val shiftColor = zoneShiftColor
    val readerClock = LocalReaderClock.current
    // Remembered: MetricGraph recomposes per touch event while scrubbing, and the min/max scan
    // is O(points) — the series is immutable per graph instance.
    val (minV, maxV) = remember(graph) {
        val present = graph.values.filterNotNull()
        present.min() to present.max()
    }
    val span = (maxV - minV).let { if (it < 1e-3f) 1f else it }
    val t0 = graph.points.first().timestamp
    val tSpan = (graph.points.last().timestamp - t0).coerceAtLeast(1L).toFloat()

    // x (0..width) -> index of the nearest point that actually has a value. Runs per drag event
    // while scrubbing, so it binary-searches the (sorted) timestamps instead of scanning them all.
    fun indexAt(x: Float, width: Float): Int? {
        if (width <= 0f) return null
        // Keep the epoch-millis math in Long: t0 (~1.8e12) + Float promotes to Float, whose
        // precision at that magnitude quantizes the target to ~131 s steps.
        val target = t0 + ((x / width).coerceIn(0f, 1f) * tSpan).toLong()
        // First index with timestamp >= target.
        var lo = 0
        var hi = graph.points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (graph.points[mid].timestamp < target) lo = mid + 1 else hi = mid
        }
        // Nearest valued point on each side of the boundary; distances only grow further out.
        var left = lo - 1
        while (left >= 0 && graph.values[left] == null) left--
        var right = lo
        while (right <= graph.points.lastIndex && graph.values[right] == null) right++
        val leftDist = if (left >= 0) target - graph.points[left].timestamp else Long.MAX_VALUE
        val rightDist = if (right <= graph.points.lastIndex) {
            kotlin.math.abs(graph.points[right].timestamp - target)
        } else {
            Long.MAX_VALUE
        }
        return when {
            left < 0 && right > graph.points.lastIndex -> null
            leftDist <= rightDist -> left
            else -> right
        }
    }

    Surface(modifier = modifier, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxSize()) {
            val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
            val padPx = with(LocalDensity.current) { 8.dp.toPx() }
            val cursorColor = MaterialTheme.colorScheme.onSurface
            val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            val view = LocalView.current
            // Tick only when the scrubber actually lands on a different point.
            val pointTick = remember(graph) { ThrottledTick(view, tickOnFirst = true) }
            fun select(index: Int?) {
                pointTick.onChange(index)
                onSelect(index)
            }
            // The cut offer rides the *end* of a gesture: raised on a tap or when a drag lifts,
            // dropped the moment the next one starts, so it never sits under the finger that is
            // still choosing. Two flips per gesture, not one per touch event — this card holds its
            // frame budget only because scrubbing recomposes as little as possible.
            var offerCut by remember { mutableStateOf(false) }
            // The selection, bounds-checked once for both the cursor and the readout below.
            val sel = selectedIndex?.takeIf { it in graph.points.indices }
            val selValue = sel?.let { graph.values[it] }

            // Where a fix falls across the plot, 0..1 — the one reading of the time axis, so the
            // cursor line and the offer beside it cannot land in different places.
            fun xFraction(index: Int) =
                ((graph.points[index].timestamp - t0) / tSpan).coerceIn(0f, 1f)
            // The plot's own width, for placing the cut offer beside the cursor. Read back off the
            // layout rather than asked for with BoxWithConstraints, whose subcomposition would run
            // over the whole plot on every measure — and the plot remeasures per touch event.
            var plotWidth by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { with(density) { plotWidth = it.width.toDp() } },
            ) {
                // Static plot in its own skippable composable: scrubbing recomposes MetricGraph per
                // touch event, and redrawing the full multi-thousand-segment polyline each time
                // makes long tracks feel laggy. Only the cursor overlay below redraws.
                MetricPlot(graph, minV, span, t0, tSpan, Modifier.fillMaxSize())
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(graph) {
                            detectTapGestures { offset ->
                                select(indexAt(offset.x, size.width.toFloat()))
                                offerCut = true
                            }
                        }
                        .pointerInput(graph) {
                            detectHorizontalDragGestures(
                                onDragStart = { offerCut = false },
                                onDragEnd = { offerCut = true },
                                onDragCancel = { offerCut = false },
                            ) { change, _ ->
                                change.consume()
                                select(indexAt(change.position.x, size.width.toFloat()))
                            }
                        },
                ) {
                    val h = size.height - 2 * padPx
                    if (sel != null && selValue != null) {
                        val x = xFraction(sel) * size.width
                        val y = padPx + h - ((selValue - minV) / span) * h
                        drawLine(
                            cursorColor.copy(alpha = 0.6f),
                            Offset(x, 0f),
                            Offset(x, size.height),
                            strokeWidth = strokePx / 2,
                        )
                        drawCircle(cursorColor, radius = strokePx * 2, center = Offset(x, y))
                    }
                }
                Text(
                    "%.0f %s".format(maxV, graph.unit),
                    modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Text(
                    "%.0f %s".format(minV, graph.unit),
                    modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                if (sel != null && selValue != null) {
                    val reading = buildAnnotatedString {
                        append("%.0f %s · ".format(selValue, graph.unit))
                        appendTime(
                            graph.points[sel].timestamp, zone, reader, shiftColor, readerClock,
                        )
                    }
                    if (offerCut && onSplitRequested != null) {
                        // Beside the cut line, never over it: the line is what the offer is about,
                        // and a card on top of it hides the answer to "where exactly". It takes the
                        // roomier side, so the cursor's own half decides — and each side is placed
                        // by padding against the opposite edge, which needs no measuring of the
                        // card and cannot push it out of the plot.
                        val cursor = plotWidth * xFraction(sel)
                        val gap = 10.dp
                        CutOffer(
                            reading = reading,
                            canCut = canCutAt(sel),
                            onCut = onSplitRequested,
                            modifier = if (cursor < plotWidth / 2) {
                                Modifier.align(Alignment.CenterStart).padding(start = cursor + gap, end = gap)
                            } else {
                                Modifier.align(Alignment.CenterEnd).padding(end = plotWidth - cursor + gap, start = gap)
                            },
                        )
                    } else {
                        // While the finger is still choosing, the reading alone, out of its way.
                        Text(
                            reading,
                            modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // The axis alone carries no offsets, and not merely to stay quiet: **marking a label
                // would assert the whole track ran on the start's clock**, which for one that
                // crossed a border is false after the crossing — and where that happened is not
                // known here. It would take resolving a zone per fix to find the instant the clock
                // flipped, and the axis deliberately reads nothing but the two bounds. So the axis
                // stays what it is, a continuum on one declared clock, and the header above states
                // that clock and the arrival's, both marked.
                Text(
                    timeText(t0, zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Text(
                    timeText(t0 + (tSpan / 2).toLong(), zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Text(
                    timeText(t0 + tSpan.toLong(), zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
        }
    }
}

// Chip colors match the marker drawables (ic_marker_noisy / _jump / _gnss) and so stay literal —
// they answer to those files, not to the theme. Only the wording is a resource.
private fun noisyLegendEntry(reason: IgnoreReason?): Pair<Int, Color> = when (reason) {
    IgnoreReason.JUMP -> R.string.ignore_reason_jump to Color(0xFFE53935)
    IgnoreReason.NO_GNSS -> R.string.ignore_reason_no_gnss to Color(0xFFAB47BC)
    // EDGE_STAY never reaches here (it is loaded separately and drawn as the grayed overrun);
    // it shares the default marker rather than adding a legend row for an impossible case.
    IgnoreReason.ACCURACY, IgnoreReason.EDGE_STAY, null ->
        R.string.ignore_reason_accuracy to Color(0xFFFF8F00)
}

/** Legend for the noisy-fix markers: one row per rejection reason present in [noisyPoints]. */
@Composable
private fun NoisyLegend(noisyPoints: List<TrackPoint>, modifier: Modifier) {
    val entries = remember(noisyPoints) {
        noisyPoints.map { noisyLegendEntry(IgnoreReason.fromCode(it.ignoreReason)) }.distinct()
    }
    LegendSurface(modifier) {
        for ((labelRes, color) in entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * This manual track as the trip form's input: its two fixes and the bounds they were stamped at,
 * under whatever the user calls those spots.
 *
 * The times come from the *row*, not from the points, though a manual track has them equal by
 * construction — the row's bounds are the declaration, the points a restatement of it. A track still
 * recording has no arrival to state and hands over none; the form asks for one.
 */
private fun tripDraftOf(
    summary: TrackSummary,
    points: List<TrackPoint>?,
    places: List<Place>,
    zone: ZoneId,
): TripDraft {
    fun endAt(point: TrackPoint?, atMs: Long) = TripDraftEnd(
        at = point?.let { Coordinate(it.latitude, it.longitude) },
        // The user's own name for the spot where a place holds this end — so the card reads as the
        // timeline does, and committing creates nothing: the place that would claim the pin is the
        // one the name came from.
        placeName = point?.let { RoutePlaces.holding(it, places, AndroidDistance)?.label },
        timeMs = atMs,
    )
    return TripDraft(
        day = summary.startedAt.toLocalDate(zone),
        origin = endAt(points?.firstOrNull(), summary.startedAt),
        destination = summary.endedAt?.let { endAt(points?.lastOrNull(), it) },
        editing = EditedTrip(summary.id, ActivityType.ofName(summary.activityType)),
    )
}

@Composable
private fun TrackStatsHeader(summary: TrackSummary) {
    val durationS = summary.endedAt?.let { (it - summary.startedAt) / 1000.0 } ?: 0.0
    val avgKmh = avgSpeedKmh(summary.distanceMeters, durationS)
    StatHeaderRow(
        stringResource(R.string.common_stat_distance) to distanceText(summary.distanceMeters),
        stringResource(R.string.common_stat_duration) to
            durationText(summary.startedAt, summary.endedAt),
        stringResource(R.string.track_stat_avg_speed) to
            if (avgKmh > 0) speedText(avgKmh) else stringResource(R.string.common_no_value),
    )
}
