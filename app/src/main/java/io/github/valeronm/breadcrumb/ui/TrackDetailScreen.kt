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
import androidx.compose.material.icons.filled.Check
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
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayIgnore
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.KeepRule
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
) {
    // null = still loading the track's points from the database.
    val context = LocalContext.current
    val points by produceState<List<TrackPoint>?>(initialValue = null, trackId) {
        value = viewModel.getPoints(trackId)
    }
    // Also null while loading: the show-a-map decision needs both lists, or a track whose only
    // points are noisy would flash the "not enough points" placeholder before its markers arrive.
    val noisyPoints by produceState<List<TrackPoint>?>(initialValue = null, trackId) {
        value = viewModel.getIgnoredPoints(trackId)
    }
    // The fixes already taken off the path at the track's edges — read back, not re-detected.
    val stayPoints by produceState(initialValue = emptyList(), trackId) {
        value = viewModel.getEdgeStayPoints(trackId)
    }
    // Embedded stays: venue-scale dwells detected from the loaded points (see DwellDetector).
    val dwells by produceState(initialValue = emptyList(), points) {
        value = points?.let { pts ->
            withContext(Dispatchers.Default) { DwellDetector.detect(pts, distance = AndroidDistance) }
        } ?: emptyList()
    }
    // Recording that ran on past the stop at either edge, grayed on the map: the stored fixes
    // grouped back into one run per edge, each hanging off the good fix that ends the track.
    val overruns = remember(points, stayPoints) {
        EdgeStayIgnore.overruns(points.orEmpty(), stayPoints)
    }
    val activity = remember(summary) {
        summary?.let { ActivityType.ofName(it.activityType) }
    }
    var colorMode by remember { mutableStateOf(ColorMode.SPEED) }
    // Noisy (ignored) fixes are hidden by default; the warning toggle shows them with a legend.
    // A track with no drawable line is the exception — its noisy fixes are all there is to see, so
    // the default follows the points once they load, until the user says otherwise.
    var showNoisyOverride by remember(trackId) { mutableStateOf<Boolean?>(null) }
    val showNoisy = showNoisyOverride ?: (points?.let { it.size < KeepRule.MIN_LINE_POINTS } == true)
    // Point picked on the metric graph, highlighted on the map. Index into the good-points list.
    var selectedIndex by remember(trackId) { mutableStateOf<Int?>(null) }
    var showTypeDialog by remember(trackId) { mutableStateOf(false) }
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
            val loaded = points
            val noisy = noisyPoints
            when {
                loaded == null || noisy == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                // A track without a drawable line still gets the map when it has noisy fixes to
                // mark — a bad-points-only track is exactly what the noisy overlay is for.
                loaded.size < KeepRule.MIN_LINE_POINTS && noisy.isEmpty() -> Text(
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
                    val graph = remember(loaded, colorMode, activity, darkTheme, units) {
                        metricGraphData(loaded, colorMode, activity, darkTheme, units)
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
                                    points = loaded,
                                    noisyPoints = if (showNoisy) noisy else emptyList(),
                                    activity = activity,
                                    colorMode = colorMode,
                                    showLegend = true,
                                    selectedPoint = selectedIndex?.let { loaded.getOrNull(it) },
                                    dwells = dwells,
                                    overruns = overruns,
                                    precomputedColoring = graph?.coloring,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (showNoisy) {
                                    // Top-right, clear of the color-metric legend (bottom-right).
                                    NoisyLegend(noisy, Modifier.align(Alignment.TopEnd).padding(12.dp))
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
                    // icon, colors and speed scale all follow.
                    for (option in ActivityType.entries.filter { it.recording && it != ActivityType.UNKNOWN }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    viewModel.setTrackActivity(trackId, option)
                                    showTypeDialog = false
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                activityIcon(option),
                                contentDescription = null,
                                tint = activityColor(option),
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            if (option == activity) {
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Current type",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTypeDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/** Per-point series for the metric graph: values (null = gap), the map's coloring, and a unit. */
@Immutable
internal class MetricGraphData(
    val points: List<TrackPoint>,
    val values: List<Float?>,
    /** Shared with the map (via `precomputedColoring`) so the O(points) pass runs once. */
    val coloring: TrackColoring,
    val unit: String,
) {
    val colors: IntArray get() = coloring.colors
}

/** Null when no point carries the metric. */
internal fun metricGraphData(
    points: List<TrackPoint>,
    mode: ColorMode,
    activity: ActivityType?,
    dark: Boolean,
    units: UnitSystem,
): MetricGraphData? {
    // Computed unconditionally: trackColoring below needs it whatever the mode.
    val speeds = TrackQuality.pointSpeedsKmh(points)
    val (values, unit) = metricSeries(points, mode, speeds, units)
    if (values.all { it == null }) return null
    val coloring = trackColoring(points, speeds, mode, activity, dark, units)
    return MetricGraphData(points, values, coloring, unit)
}

/**
 * The metric polyline alone, rasterized once per (graph, size) into a bitmap and blitted per frame.
 * Recording/issuing thousands of drawLine ops every frame is what made scrubbing long tracks lag
 * (the cursor overlay invalidates each frame); a cached bitmap makes the plot a single drawImage.
 * Takes only the (immutable) series and scale, never the selection, so Compose also skips it.
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
 * The selected color metric over the track's time span, stroked point-to-point in the same colors
 * as the map's track line, with a time axis. Missing values and segment starts break the line.
 * Tapping or dragging picks the nearest point ([onSelect]); the selection is drawn as a cursor with
 * a value/time readout, and the caller highlights the same point on the map.
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
                // touch event, and redrawing the full multi-thousand-segment polyline each time is
                // what made long tracks feel laggy. Only the cursor overlay below redraws.
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
