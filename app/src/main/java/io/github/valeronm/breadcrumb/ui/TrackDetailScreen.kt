package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.TrackPoints
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayIgnore
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.KeepRule
import io.github.valeronm.breadcrumb.domain.TrackSplit
import io.github.valeronm.breadcrumb.util.UnitSystem
import io.github.valeronm.breadcrumb.util.avgSpeedKmh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/** The map legend's line for a grayed edge: which side ran on, and for how long. */
private fun overrunLabel(overrun: EdgeStayIgnore.Overrun): String {
    val side =
        if (overrun.side == EdgeStayDetector.Side.START) "Before the start" else "After the arrival"
    return "$side · ${formatShortDurationMs(overrun.stayMs)}"
}

@OptIn(ExperimentalMaterial3Api::class)
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
    // The one seam walk this screen's derived series come from, keyed on the points alone — it is
    // the same distances whatever metric is displayed, and the graph, the map's gradient and the
    // split preview are all built from it. Switching the metric then re-runs only the ramp, not an
    // ellipsoidal distance per point. Hoisted this high because the split dialog is a sibling of
    // the Scaffold, not part of the column that draws the track.
    val seams = remember(points) { TrackQuality.seams(points.orEmpty()) }
    val activity = remember(summary) {
        summary?.let { ActivityType.ofName(it.activityType) }
    }
    var colorMode by remember { mutableStateOf(ColorMode.SPEED) }
    // Noisy (ignored) fixes are hidden by default; the warning toggle shows them with a legend.
    // A track with no drawable line is the exception — its noisy fixes are all there is to see, so
    // the default follows the points once they load, until the user says otherwise.
    var showNoisyOverride by remember(trackId) { mutableStateOf<Boolean?>(null) }
    val showNoisy = showNoisyOverride ?: (points?.let { it.size < KeepRule.MIN_LINE_POINTS } == true)
    // Point picked on the metric graph, highlighted on the map. An index into the list above, so it
    // is keyed on that list: one kept across a reload names a different fix than the user tapped.
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    var showTypeDialog by remember(trackId) { mutableStateOf(false) }
    var showSplitDialog by remember(trackId) { mutableStateOf(false) }
    // Whether the selected point is a cut the repository would accept — false with no selection or
    // one too close to an end to leave two drawable tracks — which grays the scissors rather than
    // refusing the tap silently. A derived *Boolean*, deliberately unread here: the graph writes
    // selectedIndex per drag event, so a read in this scope would recompose the whole Scaffold per
    // touch move (the scrubber holds ~8 ms frames only because reads stay inside the two cards —
    // see MetricPlot); read inside the actions lambda it keeps topBar memoized, changing only when
    // the scissors actually flips.
    val canSplit by remember(points) {
        derivedStateOf {
            val index = selectedIndex ?: return@derivedStateOf false
            val count = points?.size ?: return@derivedStateOf false
            TrackSplit.isLegalCut(index, count - index)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    Column {
                        Text(summary?.let { ActivityType.labelFor(it.activityType) } ?: "Track")
                        if (summary != null) {
                            Text(
                                dateFormat.format(Date(summary.startedAt)) +
                                    (summary.endedAt?.let { " – ${timeFormat.format(Date(it))}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (!noisyPoints.isNullOrEmpty()) {
                        IconButton(onClick = { showNoisyOverride = !showNoisy }) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription =
                                if (showNoisy) "Hide noisy fixes" else "Show noisy fixes",
                                tint = if (showNoisy) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (summary != null) {
                        IconButton(onClick = { showTypeDialog = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Change track type")
                        }
                    }
                    // Splitting needs a point to cut at, so the scissors stays visible but
                    // disabled until the graph has one — an action that appears and disappears
                    // under the thumb while scrubbing is worse than one that grays.
                    if (onSplit != null && summary?.endedAt != null) {
                        IconButton(
                            onClick = { showSplitDialog = true },
                            enabled = canSplit,
                        ) {
                            Icon(Icons.Filled.ContentCut, contentDescription = "Split track here")
                        }
                    }
                    IconButton(onClick = {
                        viewModel.importExport.shareTracks(listOf(trackId)) { intent ->
                            if (intent != null) context.startActivity(intent)
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share GPX")
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
                    "Not enough points to draw this track on a map.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (summary != null) {
                        Card(Modifier.fillMaxWidth()) { TrackStatsHeader(summary) }
                    }
                    val darkTheme = isSystemInDarkTheme()
                    val units = LocalUnits.current
                    val graph = remember(seams, colorMode, activity, darkTheme, units) {
                        metricGraphData(seams, colorMode, activity, darkTheme, units)
                    }
                    // Metric chips, map, and scrubber read as one group: small gaps, small
                    // corners between neighbors.
                    Column(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val blocks = if (graph != null) 3 else 2
                        Card(Modifier.fillMaxWidth(), shape = groupedRowShape(0, blocks)) {
                            ColorModeSelector(colorMode) { colorMode = it }
                        }
                        // The map card takes the stretch.
                        Card(Modifier.weight(1f).fillMaxWidth(), shape = groupedRowShape(1, blocks)) {
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
                                    precomputedColoring = graph?.coloring,
                                    precomputedSeams = seams,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (showNoisy) {
                                    // Top-right, clear of the color-metric legend (bottom-right).
                                    NoisyLegend(load.noisy, Modifier.align(Alignment.TopEnd).padding(12.dp))
                                }
                                if (dwells.isNotEmpty() || overruns.isNotEmpty()) {
                                    // Top-left: the noisy legend owns the top-right corner.
                                    DwellLegend(
                                        dwells, overruns,
                                        Modifier.align(Alignment.TopStart).padding(12.dp),
                                    )
                                }
                            }
                        }
                        if (graph != null) {
                            Card(Modifier.fillMaxWidth(), shape = groupedRowShape(2, 3)) {
                                MetricGraph(
                                    graph = graph,
                                    selectedIndex = selectedIndex,
                                    onSelect = { selectedIndex = it },
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
            title = { Text("Track type") },
            text = {
                Column {
                    // Selecting applies immediately: the summary flow re-emits and the title,
                    // icon, colors and speed scale all follow — and so does the drawn path, on a
                    // choice that re-derives the overrun (see the point load above).
                    for (option in ActivityType.entries.filter { it.recording && it != ActivityType.UNKNOWN }) {
                        OptionRow(
                            icon = activityIcon(option),
                            label = option.label,
                            tint = travelColor(),
                            selected = option == activity,
                            selectedDescription = "Current type",
                        ) {
                            viewModel.setTrackActivity(trackId, option)
                            showTypeDialog = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTypeDialog = false }) { Text("Cancel") }
            },
        )
    }

    SplitConfirmation(
        load = trackPoints,
        seams = seams,
        summary = summary,
        // Read only while the dialog is up: a selection read in this scope on every drag event would
        // put the scrubber's writes back in front of the whole Scaffold, which is what `canSplit`
        // exists to avoid.
        cutIndex = if (showSplitDialog) selectedIndex else null,
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
    val units = LocalUnits.current
    fun half(from: Long, to: Long, points: Int, meters: Double) =
        "${timeFormat.format(Date(from))} – ${timeFormat.format(Date(to))} · " +
            "${units.distance(meters)} · $points points"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
        title = { Text("Split at ${timeFormat.format(Date(plan.cutTs))}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(half(summary.startedAt, plan.firstEndTs, plan.firstGoodPoints, firstMeters))
                Text(half(plan.secondStartTs, trackEnd, plan.secondGoodPoints, secondMeters))
                Text(
                    "Both tracks keep every fix, and the stop between them appears on the " +
                        "timeline. Undo puts the track back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onSplit(plan.cutTs)
            }) { Text("Split") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Per-point series for the metric graph: values (null = gap), the map's coloring, and a unit. */
@Immutable
internal class MetricGraphData(
    val points: List<TrackPoint>,
    /**
     * What the graph draws and reports — time-averaged where the metric smooths (see
     * [plottedSeries]), so a stroke's *height* and the *hue* it is drawn in answer different
     * questions: the shape of the trip, and what the fix under it recorded.
     */
    val values: List<Float?>,
    /** Shared with the map (via `precomputedColoring`) so the O(points) pass runs once. */
    val coloring: TrackColoring,
    val unit: String,
) {
    val colors: IntArray get() = coloring.colors
}

/** Null when no point carries the metric. */
internal fun metricGraphData(
    seams: TrackQuality.Seams,
    mode: ColorMode,
    activity: ActivityType?,
    dark: Boolean,
    units: UnitSystem,
): MetricGraphData? {
    val points = seams.points
    // Computed unconditionally: trackColoring below needs it whatever the mode.
    val speeds = TrackQuality.pointSpeedsKmh(seams)
    val (values, unit) = metricSeries(points, mode, speeds, units)
    if (values.all { it == null }) return null
    val coloring = trackColoring(points, speeds, mode, activity, dark, units)
    return MetricGraphData(points, plottedSeries(points, mode, values), coloring, unit)
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
 * The selected color metric over the track's time span, stroked point-to-point in the map line's
 * colors — which are the map's, off the raw series, where the height is [MetricGraphData.values] —
 * with a time axis; missing values and segment starts break the line. Tap/drag picks the
 * nearest point ([onSelect]), drawn as a cursor with a value/time readout; the caller highlights
 * the same point on the map.
 */
@Composable
private fun MetricGraph(
    graph: MetricGraphData,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier,
) {
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
            // The selection, bounds-checked once for both the cursor and the readout below.
            val sel = selectedIndex?.takeIf { it in graph.points.indices }
            val selValue = sel?.let { graph.values[it] }
            Box(Modifier.weight(1f).fillMaxWidth()) {
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
                            }
                        }
                        .pointerInput(graph) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                select(indexAt(change.position.x, size.width.toFloat()))
                            }
                        },
                ) {
                    val h = size.height - 2 * padPx
                    if (sel != null && selValue != null) {
                        val x = (graph.points[sel].timestamp - t0) / tSpan * size.width
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
                    Text(
                        "%.0f %s · %s".format(
                            selValue, graph.unit,
                            timeFormat.format(Date(graph.points[sel].timestamp)),
                        ),
                        modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(timeFormat.format(Date(t0)), style = MaterialTheme.typography.labelSmall, color = labelColor)
                Text(
                    timeFormat.format(Date(t0 + (tSpan / 2).toLong())),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Text(
                    timeFormat.format(Date(t0 + tSpan.toLong())),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
        }
    }
}

// Chip colors match the marker drawables (ic_marker_noisy / _jump / _gnss).
private fun noisyLegendEntry(reason: IgnoreReason?): Pair<String, Color> = when (reason) {
    IgnoreReason.JUMP -> "Speed jump" to Color(0xFFE53935)
    IgnoreReason.NO_GNSS -> "No satellite fix" to Color(0xFFAB47BC)
    // EDGE_STAY never reaches here (it is loaded separately and drawn as the grayed overrun);
    // it shares the default marker rather than adding a legend row for an impossible case.
    IgnoreReason.ACCURACY, IgnoreReason.EDGE_STAY, null -> "Low accuracy" to Color(0xFFFF8F00)
}

/**
 * Detected stops: one row per in-track dwell — "14:36 – 16:10 · 1h 34m" — then the grayed edges,
 * named for what they are (recording that outlasted the journey) rather than dated like a visit.
 */
@Composable
private fun DwellLegend(
    dwells: List<DwellDetector.Dwell>,
    overruns: List<EdgeStayIgnore.Overrun>,
    modifier: Modifier,
) {
    LegendSurface(modifier) {
        Text(
            "Stops",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (d in dwells) {
            Text(
                "${timeFormat.format(Date(d.entryTs))} – ${timeFormat.format(Date(d.exitTs))}" +
                    " · ${formatDuration(d.entryTs, d.exitTs)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        for (o in overruns) {
            Text(
                overrunLabel(o),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Legend for the noisy-fix markers: one row per rejection reason present in [noisyPoints]. */
@Composable
private fun NoisyLegend(noisyPoints: List<TrackPoint>, modifier: Modifier) {
    val entries = remember(noisyPoints) {
        noisyPoints.map { noisyLegendEntry(IgnoreReason.fromCode(it.ignoreReason)) }.distinct()
    }
    LegendSurface(modifier) {
        for ((label, color) in entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TrackStatsHeader(summary: TrackSummary) {
    val durationS = summary.endedAt?.let { (it - summary.startedAt) / 1000.0 } ?: 0.0
    val avgKmh = avgSpeedKmh(summary.distanceMeters, durationS)
    val units = LocalUnits.current
    StatHeaderRow(
        "Distance" to units.distance(summary.distanceMeters),
        "Duration" to formatDuration(summary.startedAt, summary.endedAt),
        "Avg speed" to if (avgKmh > 0) units.speedFromKmh(avgKmh) else "—",
    )
}
