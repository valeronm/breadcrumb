package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.TrackPoints
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Clocks
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.GraphBreaks
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.KeepRule
import io.github.valeronm.breadcrumb.domain.ManualPath
import io.github.valeronm.breadcrumb.domain.RoutePlaces
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.TrackSettle
import io.github.valeronm.breadcrumb.domain.Trip
import io.github.valeronm.breadcrumb.ui.theme.LocalDarkTheme
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.avgSpeedKmh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.math.floor
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

@Composable
internal fun TrackDetailScreen(
    trackId: Long,
    summary: TrackSummary?,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    /**
     * Cut this track at the given timestamp — a split, or a trim where one half is not kept; the
     * caller performs it, offers the undo and closes this screen. Null where splitting doesn't apply, and required rather than
     * defaulted so that a new caller has to say which it is instead of inheriting silence.
     */
    onSplit: ((Long) -> Unit)?,
    /** Delete this track — this one, not the trip it is part of; the caller offers the undo and
     *  closes this screen. */
    onDelete: (() -> Unit)?,
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
        TrackSettle.overruns(points.orEmpty(), stayPoints)
    }
    // The places this journey set out from and arrived at. Off the stored rows rather than the
    // derived summaries: a pin and its reach are all the rule and the map read, and neither has
    // anything to learn from a visit count worth waiting for the clustering over.
    //
    // A plain remember, unlike the dwell walk above: this reads two fixes and prunes the places on
    // their coordinates, so a dispatcher hop would cost more than the work — and arriving in the
    // first composition means the pins are there when the style loads, instead of a source rebuilt a
    // frame later.
    val storedPlaces by viewModel.storedPlaces.collectAsStateWithLifecycle()
    val route = remember(points, storedPlaces, summary?.startPlaceId, summary?.endPlaceId) {
        RoutePlaces.route(
            points.orEmpty(),
            storedPlaces,
            AndroidDistance,
            startPlaceId = summary?.startPlaceId,
            endPlaceId = summary?.endPlaceId,
        )
    }
    // The one seam walk this screen's derived series come from, keyed on the points alone — it is
    // the same distances whatever metric is displayed, and the graph and the map's colors are both
    // built from it. Switching the metric then re-runs only the ramp, not an ellipsoidal distance
    // per point.
    val seams = remember(points) { TrackQuality.seams(points.orEmpty()) }
    val activity = remember(summary) {
        summary?.let { ActivityType.ofName(it.activityType) }
    }
    val source = TrackOrigin.fromCode(summary?.source)
    val colorModes = remember(points, source) { availableColorModes(points.orEmpty(), source) }
    var selectedMode by remember { mutableStateOf(ColorMode.SPEED) }
    // The points arrive after the first composition, so a mode can be selected and then turn out to
    // have nothing behind it — on a track opened from a metric the last one had.
    val colorMode = if (selectedMode in colorModes) selectedMode else colorModes.firstOrNull() ?: ColorMode.SPEED
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
    // The fix a gesture last ended on, and what a cut there does, worked out off the main thread —
    // a newer gesture's key cancels the older's work. Indices into the same list as the selection,
    // so keyed alike.
    var offeredCut by remember(points) { mutableStateOf<Int?>(null) }
    val cutState by produceState<CutState?>(null, offeredCut, trackPoints) {
        val index = offeredCut ?: return@produceState
        val all = trackPoints?.all ?: return@produceState
        val cutTs = points?.getOrNull(index)?.timestamp ?: return@produceState
        value = CutState(index, viewModel.previewSplit(trackId, cutTs, all))
    }
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
                    // A track still being recorded into has no settled row to delete.
                    if (onDelete != null && summary?.endedAt != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete))
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
                        val trip = remember(summary) { Trip(summary) }
                        TripSpanRow(trip, zones)
                        Card(Modifier.fillMaxWidth()) { TrackStatsHeader(trip) }
                    }
                    val darkTheme = LocalDarkTheme.current
                    // No modes means no graph, no chips and a line in the activity's color — a manual
                    // track's typed fixes carry no metric at all, and which writers can say that is
                    // availableColorModes's call, not this screen's.
                    val measures = LocalMeasures.current
                    val graph = remember(seams, colorMode, activity, darkTheme, measures, colorModes) {
                        if (colorModes.isEmpty()) {
                            null
                        } else {
                            metricGraphData(seams, colorMode, activity, darkTheme, measures)
                        }
                    }
                    val lineArgb = mapActivityArgb(activity, isMapDark())
                    val solidLine = remember(load.good, lineArgb, colorModes) {
                        if (colorModes.isEmpty()) solidColoring(load.good.size, lineArgb) else null
                    }
                    // The chips carry no surface of their own: they are the control for the map
                    // below, not a block of content beside it, and a card around a row of chips
                    // reads as a third thing to look at. A single mode leaves nothing to choose.
                    if (colorModes.size > 1) {
                        ColorModeSelector(colorMode, colorModes) { selectedMode = it }
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
                                    selectedPoint = { selectedIndex?.let { load.good.getOrNull(it) } },
                                    dwells = dwells,
                                    overruns = overruns,
                                    endPlaces = route.places,
                                    endLinks = route.links,
                                    dashed = TrackOrigin.impliesMotion(summary?.source),
                                    precomputedColoring = graph?.coloring ?: solidLine,
                                    precomputedSeams = seams,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (showNoisy) {
                                    // Top-right, clear of the color-metric legend (bottom-right).
                                    NoisyLegend(load.noisy, Modifier.align(Alignment.TopEnd).padding(MapInset))
                                }
                                if (!noisyPoints.isNullOrEmpty()) {
                                    // On the map rather than in the bar: it shows and hides marks
                                    // drawn here.
                                    MapFilterChips {
                                        MapFilterChip(
                                            selected = showNoisy,
                                            label = stringResource(R.string.track_filter_noisy),
                                        ) {
                                            showNoisyOverride = !showNoisy
                                        }
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
                                    // cut at; what it does is worked out here, with the track it
                                    // is about, and the plot renders the offer it is handed.
                                    cut = cutState,
                                    onCutOffered = { index: Int -> offeredCut = index }.takeIf { splittable },
                                    onCut = { showSplitDialog = true },
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
        ActivityTypeDialog(
            options = ActivityType.entries.filter { it.recording && it != ActivityType.UNKNOWN },
            selected = activity,
            onDismiss = { showTypeDialog = false },
        ) { option ->
            // Selecting applies immediately: the summary flow re-emits and the title, icon, colors
            // and speed scale all follow — and so does the drawn path, on a choice that re-derives
            // the overrun (see the point load above).
            viewModel.setTrackActivity(trackId, option)
            showTypeDialog = false
        }
    }

    CutConfirmation(
        // Read only while the dialog is up, so a gesture's result recomposes no more than the graph.
        preview = if (showSplitDialog) cutState?.preview else null,
        original = summary,
        zone = startZone,
        onSplit = onSplit,
        onDelete = onDelete,
        onDismiss = { showSplitDialog = false },
    )
}

/**
 * Confirm a cut as what it does, off the [preview] the scrubber's offer already worked out — the
 * function the split writes from, so what this shows is what lands. A cut keeping both halves is a
 * split; one keeping a single half is a trim of the [original] trip, stated as how much shorter it
 * gets at that end, the discarded half being only how a trim is stored; one keeping neither does
 * nothing deleting the trip doesn't, so that is what it offers.
 */
@Composable
private fun CutConfirmation(
    preview: TrackRepository.SplitPreview?,
    original: TrackSummary?,
    /** The clock the cut and the halves are stated on — the track's start, as the graph's axis
     *  is: a cut is a point on that axis, not an arrival. */
    zone: ZoneId,
    onSplit: ((Long) -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    if (preview == null || original == null || onSplit == null) return
    val originalEnd = original.endedAt ?: return
    val reader = timelineZone()
    val shiftColor = zoneShiftColor
    // All of these resolve before the builders below, none of which is a composable scope.
    val readerClock = LocalReaderClock.current

    @Composable
    fun Span(half: TrackRepository.SplitHalf) {
        val distance = fineDistanceText(half.distanceMeters)
        Text(
            buildAnnotatedString {
                appendTime(half.startedAt, zone, reader, shiftColor, readerClock)
                append(" – ")
                appendTime(half.endedAt, zone, reader, shiftColor, readerClock)
                append(" · $distance")
            },
        )
    }

    @Composable
    fun Note(text: String) = Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val trimmedStart = preview.trims && !preview.first.kept
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
        title = {
            Text(
                when {
                    preview.dropsBoth -> AnnotatedString(stringResource(R.string.track_split_drops_both_title))
                    preview.trims -> AnnotatedString(
                        stringResource(if (trimmedStart) R.string.track_trim_start_title else R.string.track_trim_end_title),
                    )
                    else -> annotatedStringResource(
                        R.string.track_split_at,
                        markedTime(preview.plan.cutTs, zone, reader, shiftColor, readerClock),
                    )
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    preview.dropsBoth -> {
                        Span(preview.first)
                        Span(preview.second)
                        Note(stringResource(R.string.track_split_drops_both_explainer))
                    }
                    preview.trims -> {
                        val trip = if (trimmedStart) preview.second else preview.first
                        Span(trip)
                        val shorter = fineDistanceText((original.distanceMeters - trip.distanceMeters).coerceAtLeast(0.0))
                        val moved = if (trimmedStart) trip.startedAt - original.startedAt else originalEnd - trip.endedAt
                        // Under a minute the duration would print as "0m", so only the distance is said.
                        val change = StayDeriver.reportable(moved)?.let { movedMs ->
                            stringResource(
                                if (trimmedStart) R.string.track_trim_starts_later else R.string.track_trim_ends_earlier,
                                durationText(movedMs),
                                shorter,
                            )
                        } ?: stringResource(
                            if (trimmedStart) R.string.track_trim_shorter_start else R.string.track_trim_shorter_end,
                            shorter,
                        )
                        Text(change)
                        Note(stringResource(R.string.track_trim_explainer))
                    }
                    else -> {
                        Span(preview.first)
                        // The time the two trips leave between them, stated only where the
                        // timeline's stay row would print a duration.
                        StayDeriver.reportable(preview.second.startedAt - preview.first.endedAt)?.let {
                            Note(stringResource(R.string.track_split_gap, durationText(it)))
                        }
                        Span(preview.second)
                        Note(stringResource(R.string.track_split_explainer))
                    }
                }
            }
        },
        confirmButton = {
            when {
                !preview.dropsBoth -> TextButton(onClick = {
                    onDismiss()
                    onSplit(preview.plan.cutTs)
                }) {
                    Text(stringResource(if (preview.trims) R.string.track_trim_confirm else R.string.track_split_confirm))
                }
                onDelete != null -> TextButton(onClick = {
                    onDismiss()
                    onDelete()
                }) { Text(stringResource(R.string.common_delete)) }
            }
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
    val seams: TrackQuality.Seams,
    /** Shared with the map (via `precomputedColoring`) so the O(points) pass runs once. */
    val coloring: TrackColoring,
) {
    val points: List<TrackPoint> get() = seams.points
    val t0: Long = points.first().timestamp
    val tSpan: Float = (points.last().timestamp - t0).coerceAtLeast(1L).toFloat()
    val values: List<Float?> get() = coloring.values
    val colors: IntArray get() = coloring.colors

    /** The value at plot precision with this series' own unit, so a label can't borrow another's. */
    fun label(value: Float): String = plotLabel(value, coloring.unit)
}

/** Null when no point carries the metric. */
internal fun metricGraphData(
    seams: TrackQuality.Seams,
    mode: ColorMode,
    activity: ActivityType?,
    dark: Boolean,
    measures: Measures,
): MetricGraphData? {
    val coloring = trackColoring(seams, mode, activity, dark, measures)
    // The ramp already reached this verdict when it found nothing to scale.
    if (coloring.legend is Legend.None) return null
    return MetricGraphData(seams, coloring)
}

private const val PLOT_RANGE_ALPHA = 0.35f

private fun MetricGraphData.columns(widthPx: Float): List<PlotColumn> = plotColumns(
    values,
    xOf = { (points[it].timestamp - t0) / tSpan * widthPx },
    breaksBefore = { seams.graphBreaks[it] },
)

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
    modifier: Modifier,
) {
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    val padPx = with(LocalDensity.current) { 8.dp.toPx() }
    Spacer(
        modifier.drawWithCache {
            val bitmap = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            CanvasDrawScope().draw(this, layoutDirection, ComposeCanvas(bitmap), size) {
                val h = size.height - 2 * padPx
                fun yOf(value: Float) = padPx + h - ((value - minV) / span) * h
                val columns = graph.columns(size.width)
                // The axis labels and the scrub readout state raw values, which a line through the
                // means alone never reaches.
                for (column in columns) {
                    if (column.max == column.min) continue
                    val x = floor(column.x) + 0.5f
                    drawLine(
                        Color(graph.colors[column.nearestMean]).copy(alpha = PLOT_RANGE_ALPHA),
                        Offset(x, yOf(column.max)),
                        Offset(x, yOf(column.min)),
                        strokeWidth = 1f,
                    )
                }
                fun at(column: PlotColumn) = Offset(column.x, yOf(column.mean))
                for (k in 1 until columns.size) {
                    val column = columns[k]
                    if (!column.startsSegment) {
                        val arrival = Color(graph.colors[column.nearestMean])
                        drawLine(arrival, at(columns[k - 1]), at(column), strokeWidth = strokePx)
                    }
                }
            }
            onDrawBehind { drawImage(bitmap) }
        },
    )
}

/** What a cut at [index] does; [preview] is null where no fix lies before it, leaving nothing to
 *  cut off. */
private class CutState(val index: Int, val preview: TrackRepository.SplitPreview?)

/**
 * The cut on offer at the scrubbed fix: what is there, and what a tap does — split, trim, or a cut
 * too short to keep either half, still tappable so its dialog can say why and offer the delete it
 * amounts to. Shown only between gestures (see `offerCut`), and **disabled rather than absent**
 * where nothing lies before the fix — a rule the user meets by aiming at it is worth stating, and an
 * offer that simply fails to appear reads as a missed gesture.
 */
@Composable
private fun CutOffer(reading: AnnotatedString, state: CutState?, onCut: () -> Unit, modifier: Modifier) {
    val preview = state?.preview
    val label = when {
        state == null -> null
        preview == null -> R.string.track_split_too_close
        preview.dropsBoth -> R.string.track_split_too_short
        preview.trims -> R.string.track_trim_here
        else -> R.string.track_split_here
    }
    val tappable = preview != null
    val actionColor = if (tappable && !preview.dropsBoth) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // The surface every other thing that floats over this screen's map already wears — the legends
    // and the ramp — so what it reads against is a property of the app rather than of this one card.
    // The clip is what keeps the ripple inside those corners; it takes the surface's own shape.
    LegendSurface(
        modifier
            .clip(legendShape)
            .clickable(enabled = tappable, onClick = onCut),
    ) {
        Text(
            reading,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (label == null) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Filled.ContentCut,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = actionColor,
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = actionColor)
            }
        }
    }
}

/**
 * The selected color metric over the track's time span, stroked in the map line's colors one pixel
 * column at a time, with a time axis; missing values and [GraphBreaks] break the line. Tap/drag
 * picks the nearest point the line is drawn through ([onSelect]), drawn as a cursor with a
 * value/time readout; the caller highlights the same point on the map.
 */
@Composable
private fun MetricGraph(
    graph: MetricGraphData,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    /** What a cut at the selection does, as far as the caller has worked it out. */
    cut: CutState?,
    /** Raised when a gesture ends on a fix, for the caller to work out [cut] there; null where the
     *  track can't be cut. */
    onCutOffered: ((Int) -> Unit)?,
    /** Open the cut's confirmation. */
    onCut: () -> Unit,
    /** The clock the time axis is read on — **the track's start**, even where it ended on another.
     *  The axis is one continuum, and labelling its far end on the arrival's clock would make it
     *  read as non-monotonic; the header above states both ends, which is where the crossing shows. */
    zone: ZoneId,
    modifier: Modifier,
) {
    val reader = timelineZone()
    val shiftColor = zoneShiftColor
    val readerClock = LocalReaderClock.current
    // Read back off the layout rather than asked for with BoxWithConstraints, whose
    // subcomposition would run over the whole plot on every measure, and the plot remeasures per
    // touch event. Zero until the first measure.
    var plotWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val plotWidth = with(density) { plotWidthPx.toDp() }
    val drawn = remember(graph, plotWidthPx) {
        if (plotWidthPx == 0) null else drawnMembers(graph.values, graph.columns(plotWidthPx.toFloat()))
    }
    fun selectable(i: Int) = drawn?.get(i) ?: (graph.values[i] != null)

    // Remembered: MetricGraph recomposes per touch event while scrubbing, and the min/max scan
    // is O(points) — the series is immutable per graph instance. A value nothing is drawn through
    // would stretch the axis to a reading the line never shows.
    val (minV, maxV) = remember(graph, drawn) {
        fun rangeOf(include: (Int) -> Boolean): Pair<Float, Float> {
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (i in graph.values.indices) {
                val v = graph.values[i] ?: continue
                if (!include(i)) continue
                lo = minOf(lo, v)
                hi = maxOf(hi, v)
            }
            return lo to hi
        }
        rangeOf(::selectable).takeIf { it.first <= it.second } ?: rangeOf { true }
    }
    val maxLabel = remember(graph, maxV) { graph.label(maxV) }
    val minLabel = remember(graph, minV) { graph.label(minV) }
    val span = (maxV - minV).let { if (it < 1e-3f) 1f else it }
    val t0 = graph.t0
    val tSpan = graph.tSpan

    // Runs per drag event while scrubbing, so it binary-searches the (sorted) timestamps instead of
    // scanning them all.
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
        // Nearest selectable point on each side of the boundary; distances only grow further out.
        var left = lo - 1
        while (left >= 0 && !selectable(left)) left--
        var right = lo
        while (right <= graph.points.lastIndex && !selectable(right)) right++
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
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { plotWidthPx = it.width },
            ) {
                // Static plot in its own skippable composable: scrubbing recomposes MetricGraph per
                // touch event, and redrawing the full multi-thousand-segment polyline each time
                // makes long tracks feel laggy. Only the cursor overlay below redraws.
                // Before the first measure, the axis range still counts fixes the line will not draw.
                if (drawn != null) MetricPlot(graph, minV, span, Modifier.fillMaxSize())
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
                if (drawn != null) {
                    Text(
                        maxLabel,
                        modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                    )
                    Text(
                        minLabel,
                        modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                    )
                }
                if (sel != null && selValue != null) {
                    val reading = buildAnnotatedString {
                        append(graph.label(selValue))
                        append(" · ")
                        appendTime(
                            graph.points[sel].timestamp, zone, reader, shiftColor, readerClock,
                        )
                    }
                    if (offerCut && onCutOffered != null) {
                        LaunchedEffect(sel) { onCutOffered(sel) }
                        // Beside the cut line, never over it: the line is what the offer is about,
                        // and a card on top of it hides the answer to "where exactly". It takes the
                        // roomier side, so the cursor's own half decides — and each side is placed
                        // by padding against the opposite edge, which needs no measuring of the
                        // card and cannot push it out of the plot.
                        val cursor = plotWidth * xFraction(sel)
                        val gap = 10.dp
                        CutOffer(
                            reading = reading,
                            state = cut?.takeIf { it.index == sel },
                            onCut = onCut,
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
    // A reason that isn't a rejection never reaches here (`TrackRepository.trackPointsFor`); it shares
    // the default marker rather than adding a legend row for an impossible case.
    IgnoreReason.ACCURACY, IgnoreReason.EDGE_STAY, IgnoreReason.STATIONARY, null ->
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
 * This manual track as the trip form's input: its first and last fixes and the bounds they were
 * stamped at, under whatever the user calls those spots, the road shape between them, and its type.
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
    fun endAt(point: TrackPoint?, atMs: Long, statedPlaceId: Long?) = TripDraftEnd(
        at = point?.let { Coordinate(it.latitude, it.longitude) },
        // The user's own name for the spot where a place holds this end — so the card reads as the
        // timeline does, and committing creates nothing: the place that would claim the pin is the
        // one the name came from.
        placeName = point?.let { RoutePlaces.holding(it, places, AndroidDistance, statedPlaceId)?.label },
        timeMs = atMs,
    )
    return TripDraft(
        day = summary.startedAt.toLocalDate(zone),
        origin = endAt(points?.firstOrNull(), summary.startedAt, summary.startPlaceId),
        destination = summary.endedAt?.let { endAt(points?.lastOrNull(), it, summary.endPlaceId) },
        editing = EditedTrip(
            summary.id,
            path = ManualPath.shapeOf(points.orEmpty().map { Coordinate(it.latitude, it.longitude) }),
        ),
        activity = ActivityType.ofName(summary.activityType),
    )
}

/**
 * When [trip] ran, each end on its own clock and marked where it differs from the reader's — a trip
 * can cross a border, and the row its page opened from says so — beside its writers' marks.
 */
@Composable
internal fun TripSpanRow(trip: Trip, zones: Clocks) {
    val reader = timelineZone()
    val shiftColor = zoneShiftColor
    val readerClock = LocalReaderClock.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                appendDateTime(trip.startedAt, zones.start, reader, shiftColor, readerClock)
                trip.endedAt?.let { endedAt ->
                    append(" – ")
                    appendTime(endedAt, zones.end, reader, shiftColor, readerClock)
                }
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        for (mark in remember(trip) { tripMarks(trip) }) OriginCaption(mark)
    }
}

/** Each writer's mark among [trip]'s parts once, the main part's first. */
private fun tripMarks(trip: Trip): List<OriginMark> =
    (listOf(trip.main) + trip.parts).mapNotNull { it.mark }.distinct()

@Composable
internal fun TrackStatsHeader(trip: Trip) {
    val durationS = trip.endedAt?.let { (it - trip.startedAt) / 1000.0 } ?: 0.0
    val avgKmh = avgSpeedKmh(trip.distanceMeters, durationS)
    StatHeaderRow(
        stringResource(R.string.common_stat_distance) to distanceText(trip.distanceMeters),
        stringResource(R.string.common_stat_duration) to durationText(trip.startedAt, trip.endedAt),
        stringResource(R.string.track_stat_avg_speed) to
            if (avgKmh > 0) speedText(avgKmh) else stringResource(R.string.common_no_value),
    )
}
