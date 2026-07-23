package io.github.valeronm.breadcrumb.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.EdgeStaySweepStatus
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackMerge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TracksTab(
    items: List<TimelineItem>,
    viewModel: TrackListViewModel,
    undo: UndoSnackbar,
    visitTarget: StayDeriver.Stay?,
    onVisitTargetShown: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenPlace: (String) -> Unit,
    onReplay: (TrackSummary) -> Unit,
) {
    val context = LocalContext.current

    // Held on the empty/progress screen for the whole restore, not just while the list is empty:
    // the first inserted batch would otherwise replace this screen (and its progress text) with a
    // timeline that keeps re-deriving as tracks pour in. The finished timeline appears at once.
    val restoreProgress by viewModel.restoreProgress.collectAsStateWithLifecycle()
    if (restoreProgress != null || items.none { it is TimelineItem.TrackItem }) {
        EmptyTracksState(viewModel)
        return
    }

    // Rows change under the user while this runs, so the work says so rather than the list
    // simply rearranging itself. Null except during a sweep.
    val sweep by EdgeStaySweepStatus.state.collectAsStateWithLifecycle()

    val groups = remember(items) { groupTimelineByDay(items) }
    val listState = rememberLazyListState()
    // Day label -> its header's lazy-item index: the fast scroller jumps between these anchors.
    val dayAnchors = remember(groups) {
        buildList {
            var index = 0
            groups.forEach { (label, dayItems) ->
                add(label to index)
                index += dayItems.size + 1
            }
        }
    }
    // The just-landed-on stay's row key: its card tints briefly so the eye finds it, then fades.
    var highlightKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightKey) {
        if (highlightKey != null) {
            delay(1800)
            highlightKey = null
        }
    }
    // Land on a visit tapped on a place's detail screen. Multi-day stays are sliced per day here,
    // but the first slice keeps the stay's original start, so it matches by identity; if the stay
    // is gone (re-derivation shifted it), its day header still anchors the jump.
    LaunchedEffect(visitTarget) {
        val target = visitTarget ?: return@LaunchedEffect
        val hit = groups.indices.firstNotNullOfOrNull { g ->
            val i = groups[g].second.indexOfFirst {
                it is TimelineItem.StayItem &&
                    it.stay.afterTrackId == target.afterTrackId &&
                    it.stay.start == target.start
            }
            if (i >= 0) (dayAnchors[g].second + 1 + i) to groups[g].second[i] else null
        }
        if (hit != null) {
            // One row above the stay so it sits below the sticky day header, not under it.
            listState.scrollToItem(hit.first - 1)
            highlightKey = hit.second.rowKey()
        } else {
            val zone = ZoneId.systemDefault()
            val label = dayLabel(target.start.toLocalDate(zone), LocalDate.now(zone))
            dayAnchors.firstOrNull { it.first == label }?.let { listState.scrollToItem(it.second) }
        }
        onVisitTargetShown()
    }
    Box(Modifier.fillMaxSize()) {
        // Above the list, not inside it: dayAnchors counts lazy indices from zero, so a leading
        // item would put the fast scroller and the visit jump one row out for the sweep's
        // duration — and a progress banner that scrolls away is not much of one.
        Column(Modifier.fillMaxSize()) {
            sweep?.let {
                EdgeStaySweepBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                // Rows within a day sit tight so the group reads as one visual block.
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                groups.forEach { (label, dayItems) ->
                    val dayTracks = dayItems.filterIsInstance<TimelineItem.TrackItem>().map { it.summary }
                    stickyHeader(key = "header:$label") {
                        DayHeader(label, dayTracks) {
                            viewModel.shareTracks(dayTracks.map { it.id }) { intent ->
                                if (intent != null) context.startActivity(intent)
                            }
                        }
                    }
                    itemsIndexed(dayItems, key = { _, item -> item.rowKey() }) { index, item ->
                        val shape = groupedRowShape(index, dayItems.size)
                        when (item) {
                            is TimelineItem.TrackItem -> TrackRow(
                                track = item.summary,
                                shape = shape,
                                onOpen = { onOpen(item.summary.id) },
                                onDelete = {
                                    val id = item.summary.id
                                    viewModel.delete(id)
                                    undo.show("Track deleted") { viewModel.restoreTrack(id) }
                                },
                                // DEBUG: long-press replays the track through the Record tab's live view.
                                onReplay = if (BuildConfig.DEBUG) {
                                    { onReplay(item.summary) }
                                } else {
                                    null
                                },
                            )
                            is TimelineItem.StayItem -> StayRow(
                                item = item,
                                shape = shape,
                                highlighted = item.rowKey() == highlightKey,
                                onMerge = { plan ->
                                    viewModel.mergeTracks(plan) { mergedId ->
                                        undo.show("Tracks merged") { viewModel.unmergeTracks(mergedId, plan) }
                                    }
                                },
                                onClick = {
                                    item.place?.let { onOpenPlace(placeDetailKeyOf(it.placeId, it.centroid)) }
                                },
                            )
                            is TimelineItem.GapItem -> GapRow(item, shape, onOpenPlace)
                        }
                    }
                }
            }
        }
        TimelineFastScroller(state = listState, dayAnchors = dayAnchors)
    }
}

/**
 * Haptic CLOCK_TICK when a scrubbed value crosses to a different key, throttled (30 ms) so a fast
 * drag feels like a picker, not a buzz. A plain holder rather than composed state: gesture lambdas
 * capture one composition and go stale. [tickOnFirst] controls whether the first non-null key
 * after construction (or a [reset]) ticks. Shared by the timeline fast scroller and the metric
 * graph scrubber.
 */
internal class ThrottledTick(private val view: View, private val tickOnFirst: Boolean) {
    private var last: Any? = null
    private var lastTickAt = 0L

    fun onChange(key: Any?) {
        if (key != null && key != last && (last != null || tickOnFirst)) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTickAt >= 30) {
                lastTickAt = now
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        last = key
    }

    fun reset() {
        last = null
    }
}

/**
 * Fast scroller for the timeline: a finger-sized handle that fades in while the list scrolls and
 * can be grabbed and dragged through the history. The drag snaps to day headers (never into the
 * middle of a day), a bubble names the day under the thumb, and crossing into a different day
 * ticks like the track scrubber.
 */
@Composable
private fun BoxScope.TimelineFastScroller(state: LazyListState, dayAnchors: List<Pair<String, Int>>) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // Tick when the drag crosses into a different day (never on the day under the initial grab).
    val dayTick = remember { ThrottledTick(view, tickOnFirst = false) }
    // Linger after the scroll stops so there's time to reach for the handle before it fades.
    var shown by remember { mutableStateOf(false) }
    val active = dragging || state.isScrollInProgress
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(1_500)
            shown = false
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (shown) 100 else 500),
        label = "fastScrollerAlpha",
    )
    if (alpha == 0f || dayAnchors.isEmpty()) return

    // Where the thumb sits when the finger isn't driving it: the day currently at the top,
    // on the same day-quantized scale the drag uses (so grabbing the handle doesn't jump).
    val listFraction by remember(state, dayAnchors) {
        derivedStateOf {
            val first = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return@derivedStateOf 0f
            val dayIdx = dayAnchors.indexOfLast { it.second <= first }.coerceAtLeast(0)
            if (dayAnchors.size <= 1) 0f else dayIdx.toFloat() / (dayAnchors.size - 1)
        }
    }
    val fraction = if (dragging) dragFraction else listFraction

    BoxWithConstraints(Modifier.matchParentSize()) {
        val density = LocalDensity.current
        val thumbHeight = 56.dp
        val thumbWidth = 32.dp
        val thumbPx = with(density) { thumbHeight.toPx() }
        val trackPx = (constraints.maxHeight - thumbPx).coerceAtLeast(1f)
        val thumbY = (trackPx * fraction).roundToInt()

        fun dayIndexAt(f: Float): Int =
            (f * (dayAnchors.size - 1)).roundToInt().coerceIn(dayAnchors.indices)

        fun applyFraction(f: Float) {
            dragFraction = f.coerceIn(0f, 1f)
            val (day, headerIndex) = dayAnchors[dayIndexAt(dragFraction)]
            dayTick.onChange(day)
            scope.launch { state.scrollToItem(headerIndex) }
        }

        // The handle: a half-circle hugging the edge inside a larger touch box that captures on
        // first touch-down — no slop wait, so grabs aren't eaten by drag detection (which loses
        // slow or slightly diagonal starts). Only the handle area takes input; the rest of the
        // edge scrolls the list as usual.
        val touchPad = 12.dp
        val touchPadPx = with(density) { touchPad.toPx() }
        val currentFraction = rememberUpdatedState(fraction)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (thumbY - touchPadPx).roundToInt()) }
                .size(width = thumbWidth + touchPad, height = thumbHeight + touchPad * 2)
                .pointerInput(dayAnchors.size, trackPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragging = true
                        dayTick.reset()
                        dragFraction = currentFraction.value
                        // This box moves with the thumb, so map local positions to track space
                        // through the thumb's current offset; anchor the grab point so the
                        // handle doesn't jump under the finger.
                        fun trackY(localY: Float) = localY + trackPx * dragFraction - touchPadPx
                        val grabDelta = (trackPx * dragFraction + thumbPx / 2) - trackY(down.position.y)
                        try {
                            drag(down.id) { change ->
                                change.consume()
                                val centre = trackY(change.position.y) + grabDelta
                                applyFraction((centre - thumbPx / 2) / trackPx)
                            }
                        } finally {
                            dragging = false
                        }
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                modifier = Modifier.size(width = thumbWidth, height = thumbHeight).alpha(alpha),
                shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                color = if (dragging) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.UnfoldMore,
                        contentDescription = "Scroll to a day",
                        tint = if (dragging) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        if (dragging) {
            val label = dayAnchors[dayIndexAt(fraction)].first
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            -with(density) { (thumbWidth + 12.dp).roundToPx() },
                            (thumbY + (thumbPx / 2).roundToInt() - with(density) { 16.dp.roundToPx() }),
                        )
                    },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

internal class DayActivityTotal(val activity: ActivityType?, val meters: Double, val durationMs: Long)

internal fun dayActivityTotals(tracks: List<TrackSummary>): List<DayActivityTotal> =
    tracks.groupBy { ActivityType.ofName(it.activityType) }
        .map { (activity, list) ->
            DayActivityTotal(
                activity = activity,
                meters = list.sumOf { it.distanceMeters },
                durationMs = list.sumOf { (it.endedAt ?: it.startedAt) - it.startedAt },
            )
        }
        .sortedByDescending { it.meters }

@Composable
private fun DayHeader(label: String, dayTracks: List<TrackSummary>, onShare: () -> Unit) {
    val totals = remember(dayTracks) { dayActivityTotals(dayTracks) }
    Column(
        // Opaque background: the header is sticky, rows scroll underneath it.
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // Share exports the day's tracks as GPX — nothing to offer on a day with only stays.
            if (dayTracks.isNotEmpty()) {
                // Compact: a full 48dp/24dp action on every header outweighs the content rows.
                IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Share $label tracks",
                        // Match the top bar's action-icon tint — plain onSurface reads too bright here.
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        // Day totals per recorded activity, in the row style: tinted glyph + distance · duration.
        Row(
            modifier = Modifier.padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val units = LocalUnits.current
            for (total in totals) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        activityIcon(total.activity),
                        contentDescription = total.activity?.label,
                        tint = activityColor(total.activity),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${units.distance(total.meters)} · ${formatDurationMs(total.durationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun groupTimelineByDay(items: List<TimelineItem>): List<Pair<String, List<TimelineItem>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    // groupBy preserves encounter order, and items arrive newest-first, so days stay descending.
    // Midnight-spanning stays were already sliced per day by the deriver.
    return items
        .groupBy { it.startedAt.toLocalDate(zone) }
        .map { (date, list) -> dayLabel(date, today) to list }
}

private fun isLocalMidnight(epochMs: Long): Boolean =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime() == java.time.LocalTime.MIDNIGHT

internal fun TimelineItem.rowKey(): String = when (this) {
    is TimelineItem.TrackItem -> "track:${summary.id}"
    is TimelineItem.StayItem -> "stay:${stay.afterTrackId}:${stay.start}"
    is TimelineItem.GapItem -> "gap:${gap.start}"
}

private val dayHeaderFormat = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.getDefault())

private val dayHeaderFormatThisYear = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())

private fun dayLabel(date: LocalDate, today: LocalDate): String = when {
    date == today -> "Today"
    date == today.minusDays(1) -> "Yesterday"
    // The current year goes without saying.
    date.year == today.year -> date.format(dayHeaderFormatThisYear)
    else -> date.format(dayHeaderFormat)
}

/**
 * The Timeline's empty state — the only place that offers restoring a backup.
 * With tracks present a restore would have to merge with them, so the offer disappears as soon
 * as the first track exists.
 */
@Composable
private fun EmptyTracksState(viewModel: TrackListViewModel) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val progress by viewModel.restoreProgress.collectAsStateWithLifecycle()
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.restoreBackup(uri) { summary ->
            val message = if (summary == null) {
                "Restore failed — not a Breadcrumb backup?"
            } else {
                "Restored ${summary.tracks} tracks and ${summary.places} places"
            }
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }
    EmptyState(
        if (progress == null) {
            "No tracks yet. They'll appear here once recording captures some movement."
        } else {
            "Restoring your backup — the timeline will appear when it finishes."
        },
        Modifier.fillMaxSize().padding(24.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        val restoring = progress
        if (restoring == null) {
            TextButton(onClick = {
                restoreLauncher.launch(
                    arrayOf("application/gzip", "application/x-gzip", "application/octet-stream"),
                )
            }) { Text("Restore from backup") }
        } else {
            val total = restoring.tracksTotal?.let { " of $it" } ?: ""
            Text(
                "Restoring… ${restoring.tracksDone}$total tracks",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: TrackSummary,
    shape: RoundedCornerShape,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onReplay: (() -> Unit)? = null,
) {
    // Swipe right-to-left to delete — a soft delete, undoable from the snackbar and, after that,
    // from Recently deleted.
    SwipeActionRow(
        shape = shape,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        icon = Icons.Filled.Delete,
        iconDescription = "Delete",
        onDismiss = onDelete,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onReplay),
            shape = shape,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val activity = ActivityType.ofName(track.activityType)
                // Activity token: a clear category cue that stays quiet.
                TonalIconDisc(
                    icon = activityIcon(activity),
                    tint = activityColor(activity),
                    contentDescription = ActivityType.labelFor(track.activityType),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    // What happened leads; when it happened is the metadata line.
                    Text(
                        "${ActivityType.labelFor(track.activityType)} · " +
                            LocalUnits.current.distance(track.distanceMeters),
                        style = MaterialTheme.typography.titleMedium,
                        // Explicit: the inherited card colour dims to onSurfaceVariant under
                        // dynamic colour (contentColorFor matches surfaceVariant first).
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val start = timeFormat.format(Date(track.startedAt))
                    val timeLine = track.endedAt?.let { "$start – ${timeFormat.format(Date(it))}" } ?: start
                    Text(
                        "$timeLine · ${formatDuration(track.startedAt, track.endedAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The edge-stay sweep, while it runs: distances and end times shift behind it as the recorder's
 * overrun comes off each track, so it says so instead of the list quietly rearranging itself.
 * Determinate — the total is known up front — and it removes itself when the sweep ends.
 */
@Composable
private fun EdgeStaySweepBanner(progress: EdgeStaySweepStatus.Progress, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(12.dp))
                // Short enough to sit beside the count on one line at phone widths; the weight
                // is the backstop, not the plan. "Updating", not "Trimming": the sweep re-derives
                // each track's overrun, and hands fixes back as readily as it takes them.
                Text(
                    "Updating recording overruns",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${progress.done} / ${progress.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = {
                    if (progress.total <= 0) 0f else progress.done.toFloat() / progress.total
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// Unnamed clusters visited at least this often surface their count as a naming invitation.
private const val VISIT_COUNT_BADGE_MIN = 3

/**
 * A derived stationary period between two tracks. A resolved place shows its label, an unnamed
 * recurring cluster shows its visit count. Tap → name. (The derivation's observed/inferred
 * provenance is deliberately NOT rendered: the customer can't act on it either way.)
 */
@Composable
private fun StayRow(
    item: TimelineItem.StayItem,
    shape: RoundedCornerShape,
    highlighted: Boolean,
    onMerge: (TrackMerge.Plan) -> Unit,
    onClick: () -> Unit,
) {
    val place = item.place
    val named = place?.label != null
    val card = @Composable { StayCard(item, shape, named, highlighted, onClick) }
    // A short same-activity stay can be swiped to merge its two tracks — the merged track replaces
    // the stay and both originals, and Undo unmerges. Ineligible stays (no plan) aren't swipeable.
    val plan = item.merge
    if (plan == null) {
        card()
        return
    }
    SwipeActionRow(
        shape = shape,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        icon = Icons.AutoMirrored.Filled.CallMerge,
        iconDescription = "Merge tracks",
        onDismiss = { onMerge(plan) },
    ) { card() }
}

@Composable
private fun StayCard(
    item: TimelineItem.StayItem,
    shape: RoundedCornerShape,
    named: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val stay = item.stay
    val place = item.place
    // Appears already tinted when a place-visit jump lands on this row, then fades to normal.
    val containerColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            CardDefaults.cardColors().containerColor
        },
        animationSpec = tween(durationMillis = 600),
        label = "stayHighlight",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A merge-eligible short stop is its own species: tertiary accent (matching the
            // swipe-to-merge hint) and a pause glyph instead of the place pin.
            val mergeable = item.merge != null
            val tint = when {
                mergeable -> MaterialTheme.colorScheme.tertiary
                named -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            TonalIconDisc(
                icon = if (mergeable) Icons.Filled.Pause else Icons.Filled.Place,
                tint = tint,
                contentDescription = if (mergeable) "Short stop" else "Stay",
                discAlpha = 0.16f,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                // The place leads; when (with midnight slices phrased humanly) is the metadata line.
                // Merge-eligible stays (always unnamed) name the situation instead.
                Text(
                    place?.label ?: if (mergeable) "Short stop" else "Stayed",
                    style = MaterialTheme.typography.titleMedium,
                    color = placeTitleColor(named),
                )
                val start = timeFormat.format(Date(stay.start))
                val end = stay.end
                val startsAtMidnight = isLocalMidnight(stay.start)
                val endsAtMidnight = end != null && isLocalMidnight(end)
                val timePhrase = when {
                    // Ongoing from midnight = all of today so far; completed midnight-to-midnight
                    // slices of a multi-day stay read the same.
                    startsAtMidnight && (end == null || endsAtMidnight) -> "All day"
                    end == null -> "$start – now"
                    startsAtMidnight -> "Until ${timeFormat.format(Date(end))}"
                    endsAtMidnight -> "From $start"
                    // A stop the recorder only caught the tail end of lands on one clock minute at
                    // both bounds; "09:11 – 09:11" reads as a rendering fault rather than a moment.
                    else -> timeFormat.format(Date(end)).let { if (it == start) start else "$start – $it" }
                }
                val visits = place?.visitCount?.takeIf { !named && it >= VISIT_COUNT_BADGE_MIN }
                Text(
                    buildAnnotatedString {
                        append(timePhrase)
                        // A midnight-sliced bound makes the duration both redundant (it restates
                        // the clock time) and misleading (the real stay continues across the
                        // slice) — only whole stays show one. A stay whose bounds span less than
                        // StayDeriver.REPORTABLE_DURATION_MS shows none either: the stop was
                        // longer than its bounds say, so "0m" would be worse than silence.
                        val reportable = stay.reportableDurationMs(System.currentTimeMillis())
                        if (!startsAtMidnight && !endsAtMidnight && reportable != null) {
                            append(" · ")
                            append(formatDurationMs(reportable))
                        }
                        if (visits != null) {
                            append(" · " + visitCountLabel(visits))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Movement the recorder missed: neighbouring track endpoints disagree. Deliberately subdued.
 * Most such gaps are really one place misclustered as two, so the card names both sides as
 * full-width tappable lines — the app's row-tap language, not inline links — each opening its
 * place, where re-pinning or widening the radius fixes the split. Two pin glyphs joined by a
 * dashed connector in the icon column draw the unrecorded leg the way a map would. Newest-first
 * timeline: the destination sits above (adjacent to the later track), the source below.
 */
@Composable
private fun GapRow(item: TimelineItem.GapItem, shape: RoundedCornerShape, onOpenPlace: (String) -> Unit) {
    val gap = item.gap
    Card(modifier = Modifier.fillMaxWidth(), shape = shape) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            GapPlaceLine(item.toPlace, onOpenPlace)
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val strokeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                // Built once, not per draw pass — the ripple invalidates the row on every press.
                val density = LocalDensity.current
                val dashEffect = remember(density) {
                    with(density) { PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx())) }
                }
                Canvas(modifier = Modifier.width(36.dp).height(24.dp)) {
                    drawLine(
                        color = strokeColor,
                        start = Offset(size.width / 2, 0f),
                        end = Offset(size.width / 2, size.height),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = dashEffect,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    "missing recording · ${formatDurationMs(gap.end - gap.start)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            GapPlaceLine(item.fromPlace, onOpenPlace)
        }
    }
}

/**
 * One side of a gap: a full-width tappable line (pin glyph + place name, ripple across the row,
 * like every other tappable row in the app) opening the place's detail screen — stay-less
 * clusters have zero-visit rows (summarize emits every cluster), so every known side opens.
 * A side that's unknown renders nothing; its position tells the story.
 */
@Composable
private fun GapPlaceLine(place: PlaceResolver.ResolvedStay?, onOpenPlace: (String) -> Unit) {
    if (place == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPlace(placeDetailKeyOf(place.placeId, place.centroid)) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = place.label ?: "unnamed place",
            style = MaterialTheme.typography.titleMedium,
            color = placeTitleColor(named = place.label != null),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
