package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.valeronm.breadcrumb.domain.FixTiming
import io.github.valeronm.breadcrumb.domain.GraphBreaks
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.KeepRule
import io.github.valeronm.breadcrumb.domain.ManualPath
import io.github.valeronm.breadcrumb.domain.RoutePlaces
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.TrackSettle
import io.github.valeronm.breadcrumb.domain.TrackSplit
import io.github.valeronm.breadcrumb.domain.Trip
import io.github.valeronm.breadcrumb.domain.TripChains
import io.github.valeronm.breadcrumb.domain.TripClocks
import io.github.valeronm.breadcrumb.domain.TripFocus
import io.github.valeronm.breadcrumb.ui.theme.LocalDarkTheme
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.avgSpeedKmh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.math.floor
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * A track's page, switching in place between the parts of the trip it is in and the whole trip;
 * [openWhole] opens on the trip [openedId] is part of. [tracks] are newest first, as the timeline
 * holds them, and the page follows a part retyped out of its trip.
 */
@Composable
internal fun TrackDetailScreen(
    tracks: List<TrackSummary>,
    openedId: Long,
    openWhole: Boolean,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    /**
     * Cut a track at the given timestamp — a split, or a trim where one half is not kept; the caller
     * performs it, offers the undo and closes this screen. Null where splitting doesn't apply, and
     * required rather than defaulted so that a new caller has to say which it is instead of
     * inheriting silence.
     */
    onSplit: ((trackId: Long, atTs: Long) -> Unit)?,
    /** Delete these tracks, one part or the whole trip; the caller offers the undo and closes this screen. */
    onDelete: ((trackIds: List<Long>) -> Unit)?,
    /** Open the trip form on a track. Null where the caller has nowhere to open it, and
     *  undefaulted for the reason [onSplit] is. */
    onEditTrip: ((TripDraft) -> Unit)?,
    /** Null for a track that is not on the timeline, and undefaulted for the reason [onSplit] is. */
    onShowOnTimeline: ((Trip) -> Unit)?,
) {
    val context = LocalContext.current
    var shownId by rememberSaveable(openedId, openWhole) { mutableLongStateOf(openedId) }
    var whole by rememberSaveable(openedId, openWhole) { mutableStateOf(openWhole) }
    val resolved = remember(tracks, shownId, whole) { TripChains.focus(tracks, shownId, whole) }
    // A part deleted from this page still draws while the page slides out.
    var lastFocus by remember(openedId, openWhole) { mutableStateOf<TripFocus?>(null) }
    SideEffect { if (resolved != null) lastFocus = resolved }
    val focus = resolved ?: lastFocus
    val trip = focus?.trip
    val summary = (focus as? TripFocus.Part)?.part
    // Nothing observes `track_points` (such a query would re-run on every GPS fix), so the parts'
    // rows are the key that tells this screen a retype re-derived a part's points
    // (`TrackRepository.setActivityType`).
    val loads by produceState<Map<Long, TrackPoints>?>(null, trip?.parts) {
        val parts = trip?.parts ?: return@produceState
        value = withContext(Dispatchers.Default) {
            parts.map { part -> async { part.id to viewModel.getTrackPoints(part.id) } }.awaitAll().toMap()
        }
    }
    val trackPoints = summary?.let { loads?.get(it.id) }
    // Clocks kept from an earlier, shorter trip would miss the new parts.
    val chain by produceState<Pair<Trip, TripClocks>?>(null, trip) {
        value = trip?.let { it to viewModel.clocksOfTrip(it) }
    }
    // The clocks the shown ends ran on, so opening a row does not change the times it showed.
    // Seeded with the reader's until the derivation answers — a track that never left home is
    // already right, and one that did corrects itself in the time the query takes.
    val reader = timelineZone()
    val zones = chain?.let { (shownTrip, clocks) ->
        if (summary == null) clocks.ends else clocks.parts.getOrNull(shownTrip.parts.indexOfFirst { it.id == summary.id })
    } ?: Clocks.both(reader)
    val startZone = zones.start
    val points = trackPoints?.good
    val stayPoints = trackPoints?.edgeStay.orEmpty()
    // Embedded stays: venue-scale dwells detected from the loaded points (see DwellDetector).
    val dwells by produceState(initialValue = emptyList(), points) {
        value = emptyList()
        val pts = points ?: return@produceState
        value = withContext(Dispatchers.Default) {
            DwellDetector.detect(pts, DwellDetector.TRACK_OVERLAY, AndroidDistance)
        }
    }
    // Recording that ran on past the stop at either edge, grayed on the map: the stored fixes
    // grouped back into one run per edge, each hanging off the good fix that ends the track.
    val overruns = remember(points, stayPoints) {
        TrackSettle.overruns(points.orEmpty(), stayPoints)
    }
    val otherParts = remember(trip, loads, summary?.id) {
        trip?.parts.orEmpty().filter { it.id != summary?.id }.mapNotNull { part ->
            loads?.get(part.id)?.let { TripPart(it.good, TrackOrigin.impliesMotion(part.source)) }
        }
    }
    // The places this journey set out from and arrived at. Off the stored rows rather than the
    // derived summaries: a pin and its reach are all the rule and the map read, and neither has
    // anything to learn from a visit count worth waiting for the clustering over.
    //
    // A plain remember, unlike the dwell walk above: this reads two fixes and prunes the places on
    // their coordinates, so a dispatcher hop would cost more than the work.
    val storedPlaces by viewModel.storedPlaces.collectAsStateWithLifecycle()
    val route = remember(focus, loads, storedPlaces) { routeOf(focus, loads, storedPlaces) }
    // The one seam walk this screen's derived series come from, keyed on the points alone — it is
    // the same distances whatever metric is displayed, and the graph and the map's colors are both
    // built from it. Switching the metric then re-runs only the ramp, not an ellipsoidal distance
    // per point.
    val seams = remember(points) { TrackQuality.seams(points.orEmpty()) }
    val activity = remember(trip) { trip?.let { ActivityType.ofName(it.main.activityType) } }
    val source = TrackOrigin.fromCode(summary?.source)
    val colorModes = remember(points, source) { availableColorModes(points.orEmpty(), source) }
    var selectedMode by remember { mutableStateOf(ColorMode.SPEED) }
    // The points arrive after the first composition, so a mode can be selected and then turn out to
    // have nothing behind it — on a track opened from a metric the last one had.
    val colorMode = if (selectedMode in colorModes) selectedMode else colorModes.firstOrNull() ?: ColorMode.SPEED
    // Noisy (ignored) fixes are hidden by default, behind the map's own chip.
    // A track with no drawable line is the exception — its noisy fixes are all there is to see, so
    // the default follows the points once they load, until the user says otherwise.
    var showNoisyOverride by remember(summary?.id) { mutableStateOf<Boolean?>(null) }
    val showNoisy = showNoisyOverride ?: (points?.let { it.size < KeepRule.MIN_LINE_POINTS } == true)
    // Point picked on the metric graph, highlighted on the map. An index into the list above, so it
    // is keyed on that list: one kept across a reload names a different fix than the user tapped.
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    var showTypeDialog by remember(summary?.id) { mutableStateOf(false) }
    var showSplitDialog by remember(summary?.id) { mutableStateOf(false) }
    // The fix a gesture last ended on, and what a cut there does, worked out off the main thread —
    // a newer gesture's key cancels the older's work. Indices into the same list as the selection,
    // so keyed alike.
    var offeredCut by remember(points) { mutableStateOf<Int?>(null) }
    val cutState by produceState<CutState?>(null, offeredCut, trackPoints) {
        value = null
        val index = offeredCut ?: return@produceState
        val part = summary ?: return@produceState
        val all = trackPoints?.all ?: return@produceState
        val cutTs = points?.getOrNull(index)?.timestamp ?: return@produceState
        value = CutState(index, viewModel.previewSplit(part.id, cutTs, all))
    }
    // Whether this track can be cut at all — a caller that handles the split, a track that has
    // finished, since a still-recording one's last edge is still moving, and a cuttable writer.
    val splittable = onSplit != null && summary?.endedAt != null && TrackSplit.cuttable(summary.source)
    val darkTheme = LocalDarkTheme.current
    // No modes means no graph, no chips and a line in the activity's color — a manual track's typed
    // fixes carry no metric at all, and which writers can say that is availableColorModes's call.
    val measures = LocalMeasures.current
    val graph = remember(seams, colorMode, activity, darkTheme, measures, colorModes) {
        if (colorModes.isEmpty()) null else metricGraphData(seams, colorMode, activity, darkTheme, measures)
    }
    val lineArgb = mapActivityArgb(activity, isMapDark())
    val scrubbed = remember(graph, points, source, lineArgb) { scrubberDataOf(graph, points.orEmpty(), source, lineArgb) }
    val solidLine = remember(points, lineArgb, colorModes) {
        if (colorModes.isEmpty()) solidColoring(points.orEmpty().size, lineArgb) else null
    }

    MapSheetPage(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(trip?.let { activityLabel(context, it.main.activityType) } ?: stringResource(R.string.track_title)) },
                subtitle = { if (focus != null) TripSpanRow(focus.shownTrip, zones) },
                navigationIcon = { BackNavIcon(onBack) },
            )
        },
        // A part's peek holds its graph, which needs the fixes.
        subject = focus.takeIf { loads != null },
        lowerKey = remember(whole, shownId) { whole to shownId },
        peek = { shown ->
            PeekCard { TrackStatsHeader(shown.shownTrip) }
            if (shown is TripFocus.Part) {
                if (colorModes.size > 1) ColorModeSelector(colorMode, colorModes) { selectedMode = it }
                if (scrubbed != null) {
                    PeekCard {
                        TrackScrubber(
                            data = scrubbed,
                            zone = startZone,
                            selectedIndex = selectedIndex,
                            onSelect = { selectedIndex = it },
                            // The cut is offered on the scrubber, beside the fix it would cut at; what
                            // it does is worked out here, with the track it is about, and the plot
                            // renders the offer it is handed.
                            cut = cutState,
                            onCutOffered = { index: Int -> offeredCut = index }.takeIf { splittable },
                            onCut = { showSplitDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        raised = { shown ->
            val actions = remember(shown, onEditTrip != null, onDelete != null) {
                pageActions(shown, canOpenForm = onEditTrip != null, canDelete = onDelete != null)
            }
            TrackSheetRows(
                focus = shown,
                chain = chain,
                actions = actions,
                onShowWhole = { whole = true },
                onShowPart = {
                    shownId = it
                    whole = false
                },
                onShowOnTimeline = onShowOnTimeline?.let { show -> { show(shown.trip) } },
            ) { action ->
                val part = (shown as? TripFocus.Part)?.part
                when (action) {
                    TrackAction.CHANGE_TYPE -> showTypeDialog = true
                    TrackAction.EDIT_TRIP -> if (part != null) onEditTrip?.invoke(tripDraftOf(part, points, storedPlaces, startZone))
                    TrackAction.SHARE -> if (part != null) {
                        viewModel.importExport.shareTracks(listOf(part.id)) { intent ->
                            if (intent != null) context.startActivity(intent)
                        }
                    }
                    TrackAction.DELETE, TrackAction.DELETE_PART -> onDelete?.invoke(shown.shownTrip.parts.map { it.id })
                }
            }
        },
        map = { peekKey, lowered, obscured ->
            val all = loads
            when {
                all == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                // A track without a drawable line still gets the map when it has noisy fixes to mark
                // or a trip around it.
                all.values.none { it.good.size >= KeepRule.MIN_LINE_POINTS || it.noisy.isNotEmpty() } -> Text(
                    stringResource(R.string.track_too_few_points),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> MapLibreTrackMap(
                    points = points.orEmpty(),
                    noisyPoints = if (showNoisy) trackPoints?.noisy.orEmpty() else emptyList(),
                    activity = activity,
                    colorMode = colorMode,
                    showLegend = summary != null,
                    noisyLegend = { if (showNoisy) NoisyLegend(trackPoints?.noisy.orEmpty()) },
                    selectedPoint = { selectedIndex?.let { points?.getOrNull(it) } },
                    dwells = dwells,
                    overruns = overruns,
                    endPlaces = route.places,
                    endLinks = route.links,
                    dashed = TrackOrigin.impliesMotion(summary?.source),
                    otherParts = otherParts,
                    wholeTrip = focus is TripFocus.Whole,
                    precomputedColoring = graph?.coloring ?: solidLine,
                    precomputedSeams = seams,
                    // The chip filters marks drawn on this map.
                    topStart = if (trackPoints?.noisy.isNullOrEmpty()) {
                        null
                    } else {
                        {
                            MapFilterChip(selected = showNoisy, label = stringResource(R.string.track_filter_noisy)) {
                                showNoisyOverride = !showNoisy
                            }
                        }
                    },
                    obscured = obscured,
                    frameKey = remember(peekKey, lowered) { peekKey to lowered },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
    )

    if (showTypeDialog && summary != null) {
        ActivityTypeDialog(
            options = ActivityType.entries.filter { it.recording && it != ActivityType.UNKNOWN },
            selected = activity,
            onDismiss = { showTypeDialog = false },
        ) { option ->
            // Selecting applies immediately: the summary flow re-emits and the title, icon, colors
            // and speed scale all follow — and so does the drawn path, on a choice that re-derives
            // the overrun (see the point load above).
            viewModel.setTrackActivity(summary.id, option)
            showTypeDialog = false
        }
    }

    CutConfirmation(
        // Read only while the dialog is up, so a gesture's result recomposes no more than the graph.
        preview = if (showSplitDialog) cutState?.preview else null,
        original = summary,
        zone = startZone,
        onSplit = summary?.let { part -> onSplit?.let { split -> { atTs: Long -> split(part.id, atTs) } } },
        onDelete = summary?.let { part -> onDelete?.let { delete -> { delete(listOf(part.id)) } } },
        onDismiss = { showSplitDialog = false },
    )
}

private fun routeOf(focus: TripFocus?, loads: Map<Long, TrackPoints>?, places: List<Place>): RoutePlaces.Route {
    val parts = focus?.shownTrip?.parts.orEmpty()
    return RoutePlaces.route(
        tripEnds(parts.map { loads?.get(it.id)?.good.orEmpty() }),
        places,
        AndroidDistance,
        startPlaceId = parts.firstOrNull()?.startPlaceId,
        endPlaceId = parts.lastOrNull()?.endPlaceId,
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

/** A part's fixes as [TrackScrubber] runs over them, never empty. */
@Immutable
internal sealed class ScrubberData(val points: List<TrackPoint>) {
    val t0: Long = points.first().timestamp
    val tSpan: Float = (points.last().timestamp - t0).coerceAtLeast(1L).toFloat()

    /** Where a fix falls across the time axis, 0..1; every drawn position derives from it. */
    fun xFraction(index: Int): Float = ((points[index].timestamp - t0) / tSpan).coerceIn(0f, 1f)
}

/** Fixes scrubbed for their times alone, drawn as dots in the map line's [lineArgb]. */
@Immutable
internal class FixTimes(points: List<TrackPoint>, val lineArgb: Int) : ScrubberData(points)

/** The pixel column of each fix's time across [widthPx], fixes sharing a column merged. */
internal fun FixTimes.fixColumns(widthPx: Int): IntArray {
    if (widthPx <= 0) return IntArray(0)
    val columns = IntArray(points.size)
    var n = 0
    for (i in points.indices) {
        val column = (xFraction(i) * widthPx).toInt().coerceAtMost(widthPx - 1)
        if (n == 0 || columns[n - 1] != column) columns[n++] = column
    }
    return columns.copyOf(n)
}

/** Where [FixDots] draws its line, and so where the cursor's dot sits on it. */
private fun fixLineY(height: Float): Float = height / 2

/** Cached per (fixes, size), like [MetricPlot]: the cursor beside them redraws on every touch event. */
@Composable
private fun FixDots(fixes: FixTimes, modifier: Modifier) {
    val color = Color(fixes.lineArgb)
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    val dotPx = with(LocalDensity.current) { 6.dp.toPx() }
    Spacer(
        modifier.drawWithCache {
            val y = fixLineY(size.height)
            val dots = fixes.fixColumns(size.width.toInt()).map { Offset(it + 0.5f, y) }
            onDrawBehind {
                drawLine(color.copy(alpha = FIX_LINE_ALPHA), Offset(0f, y), Offset(size.width, y), strokeWidth = strokePx)
                drawPoints(dots, PointMode.Points, color, strokeWidth = dotPx, cap = StrokeCap.Round)
            }
        },
    )
}

private const val FIX_LINE_ALPHA = 0.4f

/**
 * What the track page scrubs a part over: its [metric] where it has one, else its fixes' times
 * unless they were [FixTiming.SPREAD]; null for neither, and for fewer than two fixes, which span no
 * time.
 */
internal fun scrubberDataOf(metric: MetricGraphData?, points: List<TrackPoint>, source: TrackOrigin?, lineArgb: Int): ScrubberData? =
    metric ?: if (points.size >= 2 && source?.timing != FixTiming.SPREAD) FixTimes(points, lineArgb) else null

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
) : ScrubberData(seams.points) {
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

// The plot sits this far in from the card's edges, where the time axis's end labels start.
private val PLOT_INSET = 16.dp

// Above and below the plotted range, so the line's extremes clear the card's edges.
private val PLOT_PAD = 8.dp

private val RANGE_LABEL_PAD = 2.dp

private fun MetricGraphData.columns(widthPx: Float): List<PlotColumn> = plotColumns(
    values,
    xOf = { xFraction(it) * widthPx },
    breaksBefore = { seams.graphBreaks[it] },
)

/** A [graph] laid out at one width: the fixes its line is drawn through, and the range they span. */
@Immutable
private class Plot(
    val graph: MetricGraphData,
    val drawn: BooleanArray,
    val minV: Float,
    val span: Float,
    val minLabel: String,
    val maxLabel: String,
) {
    fun yOf(value: Float, height: Float, padPx: Float): Float {
        val h = height - 2 * padPx
        return padPx + h - ((value - minV) / span) * h
    }
}

private fun MetricGraphData.plot(widthPx: Int): Plot {
    val drawn = drawnMembers(values, columns(widthPx.toFloat()))
    fun rangeOf(include: (Int) -> Boolean): Pair<Float, Float> {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (i in values.indices) {
            val v = values[i] ?: continue
            if (!include(i)) continue
            lo = minOf(lo, v)
            hi = maxOf(hi, v)
        }
        return lo to hi
    }
    // A value nothing is drawn through would stretch the axis to a reading the line never shows.
    val (minV, maxV) = rangeOf { drawn[it] }.takeIf { it.first <= it.second } ?: rangeOf { true }
    return Plot(this, drawn, minV, (maxV - minV).let { if (it < 1e-3f) 1f else it }, label(minV), label(maxV))
}

/**
 * The metric polyline alone, rasterized once per (graph, size) into a bitmap blitted per frame:
 * the cursor overlay invalidates every frame, and thousands of drawLine ops each lag long-track
 * scrubbing — cached, the plot is one drawImage. Takes only the immutable series and scale, never
 * the selection, so Compose also skips it.
 */
@Composable
private fun MetricPlot(
    plot: Plot,
    modifier: Modifier,
) {
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    val padPx = with(LocalDensity.current) { PLOT_PAD.toPx() }
    val graph = plot.graph
    Spacer(
        modifier.drawWithCache {
            val bitmap = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            CanvasDrawScope().draw(this, layoutDirection, ComposeCanvas(bitmap), size) {
                fun yOf(value: Float) = plot.yOf(value, size.height, padPx)
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
internal class CutState(val index: Int, val preview: TrackRepository.SplitPreview?)

/**
 * The scrubbed fix's reading and, when [cutOffered], the cut on offer there: what a tap does —
 * split, trim, or a cut too short to keep either half, still tappable so its dialog can say why and
 * offer the delete it amounts to. The cut is offered only between gestures (see `offerCut`), and is
 * **disabled rather than absent** where nothing lies before the fix — a rule the user meets by aiming
 * at it is worth stating, and an offer that simply fails to appear reads as a missed gesture.
 */
@Composable
private fun CursorReading(reading: AnnotatedString, cutOffered: Boolean, state: CutState?, onCut: () -> Unit, modifier: Modifier) {
    val preview = state?.preview
    val label = when {
        state == null -> null
        preview == null -> R.string.track_split_too_close
        preview.dropsBoth -> R.string.track_split_too_short
        preview.trims -> R.string.track_trim_here
        else -> R.string.track_split_here
    }
    val actionColor = if (preview != null && !preview.dropsBoth) {
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
            .then(if (cutOffered) Modifier.clickable(enabled = preview != null, onClick = onCut) else Modifier),
    ) {
        Text(reading, style = MaterialTheme.typography.labelMedium)
        if (cutOffered) {
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
}

/**
 * A part's fixes over their time span, with a time axis. Tap/drag picks the nearest fix on offer
 * ([onSelect]), drawn as a cursor with a time readout; the caller highlights the same fix on the
 * map. A [MetricGraphData] is plotted in the map line's colors one pixel column at a time, missing
 * values and [GraphBreaks] breaking the line, and then only the fixes the line is drawn through are
 * on offer and the readout leads with the value.
 */
@Composable
internal fun TrackScrubber(
    data: ScrubberData,
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
    val graph = data as? MetricGraphData
    val points = data.points
    val reader = timelineZone()
    val shiftColor = zoneShiftColor
    val readerClock = LocalReaderClock.current
    // Read back off the layout rather than asked for with BoxWithConstraints, whose
    // subcomposition would run over the whole plot on every measure, and the plot remeasures per
    // touch event. Zero until the first measure.
    var plotWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val plotWidth = with(density) { plotWidthPx.toDp() }
    // Remembered: TrackScrubber recomposes per touch event while scrubbing, and laying the plot out
    // is O(points) — the series is immutable per graph instance.
    val plot = remember(graph, plotWidthPx) { if (graph == null || plotWidthPx == 0) null else graph.plot(plotWidthPx) }
    fun selectable(i: Int) = graph == null || plot?.drawn?.get(i) == true
    val t0 = data.t0
    val tSpan = data.tSpan

    // Runs per drag event while scrubbing, so it binary-searches the (sorted) timestamps instead of
    // scanning them all.
    fun PointerInputScope.indexAt(x: Float): Int? {
        val inset = PLOT_INSET.toPx()
        val width = size.width - 2 * inset
        if (width <= 0f) return null
        // Keep the epoch-millis math in Long: t0 (~1.8e12) + Float promotes to Float, whose
        // precision at that magnitude quantizes the target to ~131 s steps.
        val target = t0 + (((x - inset) / width).coerceIn(0f, 1f) * tSpan).toLong()
        // First index with timestamp >= target.
        var lo = 0
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].timestamp < target) lo = mid + 1 else hi = mid
        }
        // Nearest selectable point on each side of the boundary; distances only grow further out.
        var left = lo - 1
        while (left >= 0 && !selectable(left)) left--
        var right = lo
        while (right <= points.lastIndex && !selectable(right)) right++
        val leftDist = if (left >= 0) target - points[left].timestamp else Long.MAX_VALUE
        val rightDist = if (right <= points.lastIndex) {
            kotlin.math.abs(points[right].timestamp - target)
        } else {
            Long.MAX_VALUE
        }
        return when {
            left < 0 && right > points.lastIndex -> null
            leftDist <= rightDist -> left
            else -> right
        }
    }

    Column(modifier.padding(vertical = 12.dp).height(120.dp)) {
        val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
        val padPx = with(LocalDensity.current) { PLOT_PAD.toPx() }
        val cursorColor = MaterialTheme.colorScheme.onSurface
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        val view = LocalView.current
        // Tick only when the scrubber actually lands on a different point.
        val pointTick = remember(data) { ThrottledTick(view, tickOnFirst = true) }
        fun select(index: Int?) {
            pointTick.onChange(index)
            onSelect(index)
        }
        // The cut offer rides the *end* of a gesture: raised on a tap or when a drag lifts,
        // dropped the moment the next one starts, so it never sits under the finger that is
        // still choosing. Two flips per gesture, not one per touch event — the scrubber holds its
        // frame budget only because scrubbing recomposes as little as possible.
        var offerCut by remember { mutableStateOf(false) }
        // The selection, bounds-checked once for both the cursor and the readout below. On a
        // plot, a fix with no value has nowhere for the cursor to sit.
        val selValue = selectedIndex?.let { graph?.values?.getOrNull(it) }
        val sel = selectedIndex?.takeIf { it in points.indices && (graph == null || selValue != null) }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // Over the inset too, so the fix at either end is taken from the scrubber's edge.
                .pointerInput(data) {
                    detectTapGestures { offset ->
                        select(indexAt(offset.x))
                        offerCut = true
                    }
                }
                .pointerInput(data) {
                    detectHorizontalDragGestures(
                        onDragStart = { offerCut = false },
                        onDragEnd = { offerCut = true },
                        onDragCancel = { offerCut = false },
                    ) { change, _ ->
                        change.consume()
                        select(indexAt(change.position.x))
                    }
                }
                .padding(horizontal = PLOT_INSET)
                .onSizeChanged { plotWidthPx = it.width },
        ) {
            // Static plot in its own skippable composable: scrubbing recomposes TrackScrubber per
            // touch event, and redrawing the full multi-thousand-segment polyline each time
            // makes long tracks feel laggy. Only the cursor overlay below redraws.
            if (plot != null) MetricPlot(plot, Modifier.fillMaxSize())
            if (data is FixTimes) FixDots(data, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                if (sel != null) {
                    val x = data.xFraction(sel) * size.width
                    drawLine(
                        cursorColor.copy(alpha = 0.6f),
                        Offset(x, 0f),
                        Offset(x, size.height),
                        strokeWidth = strokePx / 2,
                    )
                    val y = if (data is FixTimes) fixLineY(size.height) else selValue?.let { plot?.yOf(it, size.height, padPx) }
                    if (y != null) drawCircle(cursorColor, radius = strokePx * 2, center = Offset(x, y))
                }
            }
            if (plot != null) {
                Text(
                    plot.maxLabel,
                    modifier = Modifier.align(Alignment.TopStart).padding(vertical = RANGE_LABEL_PAD),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Text(
                    plot.minLabel,
                    modifier = Modifier.align(Alignment.BottomStart).padding(vertical = RANGE_LABEL_PAD),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
            if (sel != null) {
                val reading = buildAnnotatedString {
                    if (graph != null && selValue != null) {
                        append(graph.label(selValue))
                        append(" · ")
                    }
                    appendTime(
                        points[sel].timestamp, zone, reader, shiftColor, readerClock,
                    )
                }
                val cutOffered = offerCut && onCutOffered != null
                if (cutOffered) LaunchedEffect(sel) { onCutOffered(sel) }
                // Beside the cursor, never over it: the line is what the reading is about, and a
                // card on top of it hides the answer to "where exactly". It takes the roomier
                // side, so the cursor's own half decides — and each side is placed by padding
                // against the opposite edge, which needs no measuring of the card and cannot push
                // it out of the plot.
                val cursor = plotWidth * data.xFraction(sel)
                val gap = 10.dp
                // Below the max label's row, which a bubble left of the cursor would cover just
                // past the middle.
                val top = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp() } + RANGE_LABEL_PAD * 2
                CursorReading(
                    reading = reading,
                    cutOffered = cutOffered,
                    state = cut?.takeIf { it.index == sel },
                    onCut = onCut,
                    modifier = if (cursor < plotWidth / 2) {
                        Modifier.align(Alignment.TopStart).padding(start = cursor + gap, end = gap, top = top)
                    } else {
                        Modifier.align(Alignment.TopEnd).padding(end = plotWidth - cursor + gap, start = gap, top = top)
                    },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PLOT_INSET, vertical = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // No label carries an offset: marking one would assert the whole track ran on the
            // start's clock, which is false past a border crossing. Placing the crossing would
            // take resolving a zone per fix.
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
private fun NoisyLegend(noisyPoints: List<TrackPoint>) {
    val entries = remember(noisyPoints) {
        noisyPoints.map { noisyLegendEntry(IgnoreReason.fromCode(it.ignoreReason)) }.distinct()
    }
    LegendSurface {
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

/** [spanText] for [trip], beside its writer's mark, or its count of parts where it has several. */
@Composable
internal fun TripSpanRow(trip: Trip, zones: Clocks) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            spanText(trip.startedAt, trip.endedAt, zones),
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trip.chained) {
            MarkCaption(pluralStringResource(R.plurals.track_part_count, trip.parts.size, trip.parts.size))
        } else {
            OriginCaption(trip.main.mark)
        }
    }
}

@Composable
private fun PeekCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = groupedRowShape(0, 1), content = content)
}

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

@Composable
internal fun TrackSheetRows(
    focus: TripFocus,
    chain: Pair<Trip, TripClocks>?,
    actions: List<TrackAction>,
    onShowWhole: () -> Unit,
    onShowPart: (Long) -> Unit,
    onShowOnTimeline: (() -> Unit)?,
    onAction: (TrackAction) -> Unit,
) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (chain != null && chain.first.chained) {
            TripSelector(chain.first, chain.second, focus, onShowWhole, onShowPart)
        }
        if (onShowOnTimeline != null) {
            GroupedRows({
                NavRow(stringResource(R.string.common_show_on_timeline), icon = Icons.Filled.Route, onClick = onShowOnTimeline)
            })
        }
        if (actions.isNotEmpty()) {
            GroupedRows(
                *actions.map<TrackAction, @Composable GroupedRowScope.() -> Unit> { action ->
                    { ActionRow(stringResource(action.labelRes()), action.icon()) { onAction(action) } }
                }.toTypedArray(),
            )
        }
    }
}

@StringRes
private fun TrackAction.labelRes(): Int = when (this) {
    TrackAction.CHANGE_TYPE -> R.string.track_change_type
    TrackAction.EDIT_TRIP -> R.string.track_edit_trip
    TrackAction.SHARE -> R.string.track_share_gpx
    TrackAction.DELETE -> R.string.common_delete
    TrackAction.DELETE_PART -> R.string.track_delete_part
}

private fun TrackAction.icon(): ImageVector = when (this) {
    TrackAction.CHANGE_TYPE, TrackAction.EDIT_TRIP -> Icons.Filled.Edit
    TrackAction.SHARE -> Icons.Filled.Share
    TrackAction.DELETE, TrackAction.DELETE_PART -> Icons.Filled.Delete
}

/** [clocks] are [trip]'s, part for part. */
@Composable
internal fun TripSelector(trip: Trip, clocks: TripClocks, focus: TripFocus, onShowWhole: () -> Unit, onShowPart: (Long) -> Unit) {
    val shownPartId = (focus as? TripFocus.Part)?.part?.id
    TripCard(
        trip = trip,
        clocks = clocks,
        shape = groupedRowShape(0, 1),
        selected = focus is TripFocus.Whole,
        onClick = onShowWhole,
        below = { TripPartLines(trip, clocks, onOpen = onShowPart, selected = { it.id == shownPartId }) },
    )
}
