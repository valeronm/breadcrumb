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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Luggage
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.data.SweepStatus
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.TravelDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.dayCategoryTotals
import io.github.valeronm.breadcrumb.util.PerLocale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TracksTab(
    /** Null while the derivation is still running — see [TrackListViewModel.timeline]. */
    items: List<TimelineItem>?,
    viewModel: TrackListViewModel,
    undo: UndoSnackbar,
    visitTarget: StayDeriver.Stay?,
    onVisitTargetShown: () -> Unit,
    /** A day to land on, sent by a journey tapped on the Insights tab. */
    dayTarget: LocalDate?,
    onDayTargetShown: () -> Unit,
    /** Bumped each time the Timeline tab is tapped while already open — send the list to the top. */
    homeRequest: Int,
    onOpen: (Long) -> Unit,
    onOpenPlace: (String) -> Unit,
    onReplay: (TrackSummary) -> Unit,
) {
    val context = LocalContext.current

    // Held on the empty/progress screen for the whole restore, not just while the list is empty:
    // the first inserted batch would otherwise replace this screen (and its progress text) with a
    // timeline that keeps re-deriving as tracks pour in. The finished timeline appears at once.
    val restoreProgress by viewModel.importExport.restoreProgress.collectAsStateWithLifecycle()
    // In priority order: a restore outranks everything, since that screen reports its progress for
    // the whole run; then "not derived yet"; then a history that really is empty.
    when {
        restoreProgress != null -> {
            EmptyTracksState(viewModel)
            return
        }
        items == null -> {
            DerivingState(Modifier.fillMaxSize())
            return
        }
        items.none { it is TimelineItem.TrackItem } -> {
            EmptyTracksState(viewModel)
            return
        }
    }

    // Rows change under the user while this runs, so the work says so rather than the list
    // simply rearranging itself. Null except during a sweep.
    val sweep by SweepStatus.state.collectAsStateWithLifecycle()

    val groups = remember(items) { groupTimelineByDay(items) }
    val travels by viewModel.travels.collectAsStateWithLifecycle()
    val awayDays = remember(travels) { awayDaysOf(travels.orEmpty(), timelineZone()) }
    val listState = rememberLazyListState()
    // Day label -> its header's lazy-item index: the fast scroller jumps between these anchors.
    val dayAnchors = remember(groups) {
        buildList {
            var index = 0
            groups.forEach { group ->
                add(group.label to index)
                index += group.items.size + 1
            }
        }
    }
    // Back to today. The value this tab composed with is not a request — only a later bump is, and
    // the list starts at the top anyway (a tab switch re-composes this from scratch). One immutable
    // snapshot is enough: the counter only grows, so "differs from the value at entry" and "differs
    // from the previous value" are the same test.
    val homeRequestAtEntry = remember { homeRequest }
    LaunchedEffect(homeRequest) {
        if (homeRequest == homeRequestAtEntry) return@LaunchedEffect
        // animateScrollToItem jumps most of the way and animates the last stretch, so this reads as
        // a fast return from anywhere in a multi-thousand-row history rather than a long fling.
        listState.animateScrollToItem(0)
    }
    // The just-landed-on stay's row key: its card tints briefly so the eye finds it, then fades.
    var highlightKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightKey) {
        if (highlightKey != null) {
            delay(1800.milliseconds)
            highlightKey = null
        }
    }
    // Land on a visit tapped on a place's detail screen. Multi-day stays are sliced per day here,
    // but the first slice keeps the stay's original start, so it matches by identity; if the stay
    // is gone (re-derivation shifted it), its day header still anchors the jump.
    LaunchedEffect(visitTarget) {
        val target = visitTarget ?: return@LaunchedEffect
        val hit = groups.indices.firstNotNullOfOrNull { g ->
            val i = groups[g].items.indexOfFirst {
                it is TimelineItem.StayItem &&
                    it.stay.afterTrackId == target.afterTrackId &&
                    it.stay.start == target.start
            }
            if (i >= 0) (dayAnchors[g].second + 1 + i) to groups[g].items[i] else null
        }
        if (hit != null) {
            // One row above the stay so it sits below the sticky day header, not under it.
            listState.scrollToItem(hit.first - 1)
            highlightKey = hit.second.rowKey()
        } else {
            // By instant, not by date: the target arrives from a place screen carrying no zone, and
            // dating it on the device's clock would miss the day a foreign stay was filed under.
            // Groups descend, so the first whose oldest row is at or before the target holds it.
            groups.indexOfFirst { it.items.last().startedAt <= target.start }
                .takeIf { it >= 0 }
                ?.let { listState.scrollToItem(dayAnchors[it].second) }
        }
        onVisitTargetShown()
    }
    // Land on a journey tapped on the Insights tab. The target is its *latest* day, where the block
    // starts as the eye meets it, the rows running newest first.
    //
    // The exact day is looked for before the nearest one because the dates are no longer monotonic:
    // a westward crossing can put an earlier date on a later row (leave Tokyo on the 18th, land in
    // Honolulu on the 17th), so "the first group at or before" can walk past the day that is
    // actually there. It stays as the fallback for a journey whose last day holds no rows at all.
    LaunchedEffect(dayTarget, groups) {
        val date = dayTarget ?: return@LaunchedEffect
        val group = groups.indexOfFirst { it.date == date }.takeIf { it >= 0 }
            ?: groups.indexOfFirst { it.date <= date }.takeIf { it >= 0 }
            ?: groups.lastIndex
        if (group >= 0) listState.scrollToItem(dayAnchors[group].second)
        onDayTargetShown()
    }
    // Both interval rows offer the same merge, so they share one handler rather than two copies
    // of the undo wiring.
    val onMerge = { plan: TrackMerge.Plan ->
        viewModel.mergeTracks(plan) { mergedId ->
            undo.show("Tracks merged") { viewModel.unmergeTracks(mergedId, plan) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Above the list, not inside it: dayAnchors counts lazy indices from zero, so a leading
        // item would put the fast scroller and the visit jump one row out for the sweep's
        // duration — and a progress banner that scrolls away is not much of one.
        Column(Modifier.fillMaxSize()) {
            sweep?.let {
                SweepBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // No padding at the top: content padding does not clip, so rows scroll *through* it
                // while the sticky header pins below it, leaving a strip where the day being left
                // behind shows above the day being read. The header's own top padding gives the
                // list its breathing room instead, and rows now pass under an opaque header.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                // Rows within a day sit tight so the group reads as one visual block.
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                groups.forEach { group ->
                    val dayItems = group.items
                    val dayTracks = dayItems.filterIsInstance<TimelineItem.TrackItem>().map { it.summary }
                    val away = awayDays[group.date]
                    // Keyed by the run's own newest instant, not by its label: a date can appear
                    // twice now (a westward crossing lives the same date twice), and two headers
                    // sharing a key is a hard crash in a lazy list rather than a cosmetic clash.
                    stickyHeader(key = "header:${group.items.first().startedAt}") {
                        DayHeader(group.label, dayTracks, dayItems, away) {
                            viewModel.importExport.shareTracks(dayTracks.map { it.id }) { intent ->
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
                                zone = item.clock,
                                endZone = item.endZone,
                                onOpen = { onOpen(item.summary.id) },
                                onDelete = {
                                    val id = item.summary.id
                                    viewModel.delete(id)
                                    undo.show("Track deleted") { viewModel.restoreTrack(id) }
                                },
                                // DEBUG: long-press replays the track through the Record tab's live view.
                                onReplay = if (BuildConfig.DEV_TOOLS) {
                                    { onReplay(item.summary) }
                                } else {
                                    null
                                },
                            )
                            is TimelineItem.StayItem -> StayRow(
                                item = item,
                                shape = shape,
                                zone = item.clock,
                                highlighted = item.rowKey() == highlightKey,
                                onMerge = onMerge,
                                onClick = {
                                    item.place?.let { onOpenPlace(it.key) }
                                },
                            )
                            is TimelineItem.GapItem ->
                                GapRow(item, shape, item.clock, onOpenPlace, onMerge)
                        }
                    }
                }
            }
        }
        TimelineFastScroller(state = listState, dayAnchors = dayAnchors)
    }
}

/**
 * Haptic CLOCK_TICK when a scrubbed value crosses keys, throttled (30 ms) so a fast drag feels like
 * a picker, not a buzz; a plain holder because gesture lambdas capture a composition and go stale.
 * [tickOnFirst]: does the first non-null key after construction (or a [reset]) tick.
 */
internal class ThrottledTick(private val view: View, private val tickOnFirst: Boolean) {
    private var last: Any? = null
    private var lastTickAt = 0L

    fun onChange(key: Any?) {
        val changedKey = key != null && key != last
        val firstKeyTicks = last != null || tickOnFirst
        if (changedKey && firstKeyTicks) {
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
 * can be grabbed and dragged through the history; the drag snaps to day headers (never inside a
 * day), a bubble names the day under the thumb, and crossing days ticks like the track scrubber.
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
            delay(1_500.milliseconds)
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
                                val center = trackY(change.position.y) + grabDelta
                                applyFraction((center - thumbPx / 2) / trackPx)
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

/**
 * The journey a day belongs to, above its date. **Repeated on every day of that journey**, which is
 * the point: a sticky header holds one row at a time, so a heading that appeared once at the top of
 * the block would scroll away and leave the days under it unattributed. Repeating it means whatever
 * day is stuck to the top always says which journey it is part of.
 *
 * Named by where the journey was spent, falling back to the plain fact of being away when nothing in
 * it can be named. Nights and distance are not here — they would repeat on every header of a journey
 * to say something that does not change, and the Insights tab states them once.
 *
 * **How the nights were placed is deliberately not shown**: a journey resting partly on unobserved
 * nights looks the same as one observed throughout, because the difference is not something a reader
 * can act on. Provenance stays on the model, exactly as [StayDeriver.Provenance] does for stay rows.
 */
@Composable
private fun TravelHeading(away: AwayDay) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 2.dp),
    ) {
        // Shared with the Travel place category on purpose: a chip on a place and a heading over a
        // run of days are read in different places, and the same glyph means "a trip" in both.
        Icon(
            Icons.Filled.Luggage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            TravelNaming.label(away.summary.destinations, away.summary.travel.nightCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayHeader(
    label: String,
    dayTracks: List<TrackSummary>,
    dayItems: List<TimelineItem>,
    away: AwayDay?,
    onShare: () -> Unit,
) {
    val totals = remember(dayTracks) { dayActivityTotals(dayTracks) }
    val categoryTotals = remember(dayItems) {
        dayCategoryTotals(dayItems, System.currentTimeMillis())
    }
    Column(
        // Opaque background: the header is sticky, rows scroll underneath it. The gap to the first
        // row is the header's own, so neither totals line has to know whether it is the last one.
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 14.dp, bottom = 6.dp),
    ) {
        away?.let { TravelHeading(it) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Which day of the journey this is — the band above carries the journey itself, so
                // this says only where in it the reader has scrolled to. Same size as the date it
                // continues, the tint alone telling the two apart.
                away?.let {
                    Text(
                        "· Day ${it.ordinal} of ${it.dayCount} away",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
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
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val units = LocalUnits.current
            for (total in totals) {
                DayTotal(
                    icon = activityIcon(total.activity),
                    description = total.activity?.label,
                    tint = travelColor(),
                    text = "${units.distance(total.meters)} · ${formatDurationMs(total.durationMs)}",
                )
            }
        }
        // Where the day went, under what it covered — a second line rather than more of the first,
        // which doesn't wrap and would clip a day holding several of each. Wrapping here because a
        // varied day has more categories than a day has activities.
        if (categoryTotals.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (total in categoryTotals) {
                    DayTotal(
                        icon = total.category.icon,
                        description = total.category.label,
                        // Group color, as the rows below wear it — the totals line then reads as the
                        // same palette the day's stays were drawn in.
                        tint = categoryColor(total.category),
                        text = formatDurationMs(total.durationMs),
                    )
                }
            }
        }
    }
}

/**
 * One figure in a day header's totals: a small tinted glyph and its number. Both lines are built from
 * this, so the glyph size, the gap and the type scale can't drift between them — they have to read as
 * one block, which is the whole reason the second line sits under the first.
 */
@Composable
private fun DayTotal(icon: ImageVector, description: String?, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The rows cut into days, each dated on **its own clock** — a stay abroad falls under the day it was
 * lived in, not the one the device was having.
 *
 * Grouped by *runs* rather than by value, which is the whole difference from a global calendar: once
 * each row answers on its own zone the dates are no longer a partition. Flying east to west, the
 * 18th happens twice with the crossing between; flying the other way, a date is skipped. `groupBy`
 * would weld the two halves of a repeated date into one heading with a flight's worth of rows
 * between them, in the wrong order — so a repeat has to be allowed to stand as two days, because
 * that is what it was.
 */
internal fun groupTimelineByDay(items: List<TimelineItem>): List<DayGroup> {
    val today = LocalDate.now(timelineZone())
    val groups = mutableListOf<DayGroup>()
    var run = mutableListOf<TimelineItem>()
    var date: LocalDate? = null
    for (item in items) {
        val itemDate = item.filedOn
        if (itemDate != date) {
            date?.let { groups += DayGroup(it, dayLabel(it, today), run) }
            run = mutableListOf()
            date = itemDate
        }
        run += item
    }
    date?.let { groups += DayGroup(it, dayLabel(it, today), run) }
    return groups
}

/** One day's rows. The date rides along with the label because a label is for reading and a date
 *  is what answers whether the day falls inside a travel. */
internal class DayGroup(val date: LocalDate, val label: String, val items: List<TimelineItem>)

/**
 * A day inside a travel: which one, where in it, and whether it is that travel's *latest* day —
 * the rows run newest-first, so that is the day the band announcing the travel sits above.
 *
 * Two travels can share a boundary day (arrived home in the morning, left again that evening); the
 * later one wins it, which is also the one whose band the reader is scrolling into.
 */
private class AwayDay(val summary: TravelNaming.Summary, val ordinal: Int, val dayCount: Int)

private fun awayDaysOf(travels: List<TravelNaming.Summary>, zone: ZoneId): Map<LocalDate, AwayDay> =
    buildMap {
        for (summary in travels) {
            val days = TravelDeriver.daysCovered(summary.travel, zone)
            days.forEachIndexed { index, date -> put(date, AwayDay(summary, index + 1, days.size)) }
        }
    }

/**
 * The zone to read a row in when nothing places it — an import with no usable endpoint, a stop in
 * the middle of an ocean, a gazetteer row this Android's tz database has never heard of. Resolved
 * per call rather than captured, so a device that changes zone mid-process moves with it.
 *
 * It is no longer *the* timeline zone: a row is sliced and read in the zone of the place it happened
 * in ([TimelineItem.zone]), which is the only way a stay abroad ends its day when that country's day
 * ended. What survives from when this was one global zone is the invariant behind it — whatever
 * clock a row's bounds were clamped to, every reader of those bounds must ask the same one.
 */
internal fun timelineZone(): ZoneId = ZoneId.systemDefault()

/**
 * A gazetteer zone id as a [ZoneId], falling back to the device's where it can't be one.
 *
 * The ids ship inside a checked-in asset while the tz database ships with Android, so the two drift
 * apart on their own schedules: a zone renamed upstream (`Europe/Kiev` → `Europe/Kyiv`) or dropped
 * leaves the asset naming something this device cannot resolve. That is a wrong clock on one row,
 * which is worth a fallback; it is not worth taking the timeline down for, which is what letting
 * [ZoneId.of] throw here would do.
 */
internal fun zoneOrDevice(zoneId: String?): ZoneId =
    zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: timelineZone()

/** The clock to read a row on: the one it was sliced in, or the device's where nothing placed it.
 *  One reading, because a row dated on one clock and timed on another contradicts itself. */
internal val TimelineItem.clock: ZoneId get() = zone ?: timelineZone()

/**
 * The day a row is filed under. Filing takes **two** values — which instant, and on whose clock —
 * and pairing them is the whole of it: `filedAt` with the device's zone, or `startedAt` with the
 * row's own, both type-check and both are wrong. So the pairing is written once, here.
 */
internal val TimelineItem.filedOn: LocalDate get() = filedAt.toLocalDate(clock)

/** A bound the day slicing put there, rather than a time anything happened at — in [zone], which
 *  must be the zone the slicing used ([timelineZone]). */
private fun isLocalMidnight(epochMs: Long, zone: ZoneId): Boolean =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime() == java.time.LocalTime.MIDNIGHT

internal fun TimelineItem.rowKey(): String = when (this) {
    is TimelineItem.TrackItem -> "track:${summary.id}"
    is TimelineItem.StayItem -> "stay:${stay.afterTrackId}:${stay.start}"
    is TimelineItem.GapItem -> "gap:${gap.start}"
}

private val dayHeaderFormat by PerLocale { DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", it) }

private val dayHeaderFormatThisYear by PerLocale { DateTimeFormatter.ofPattern("EEEE, d MMM", it) }

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
    val progress by viewModel.importExport.restoreProgress.collectAsStateWithLifecycle()
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importExport.restoreBackup(uri) { summary ->
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
            "No tracks yet. They appear here once recording captures some movement."
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
    zone: ZoneId,
    /** Where it arrived, when that is a different clock — see [TimelineItem.TrackItem.endZone]. */
    endZone: ZoneId?,
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
        val activity = ActivityType.ofName(track.activityType)
        val reader = timelineZone()
        // Each end on its own clock where they differ — a track is the one recorded row that can
        // cross a border, and flattening it onto the departure's clock hides the crossing. The two
        // times then no longer span the duration beside them, which is why each carries its own
        // offset: read together they say the drive lost an hour to the border, which is the truth.
        val shiftColor = zoneShiftColor
        ListRowCard(
            // Long-press replays the track, which Card's own onClick can't express.
            modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onReplay),
            shape = shape,
            // Activity token: a clear category cue that stays quiet.
            icon = activityIcon(activity),
            tint = travelColor(),
            iconDescription = ActivityType.labelFor(track.activityType),
            // What happened leads; when it happened is the metadata line.
            title = "${ActivityType.labelFor(track.activityType)} · " +
                LocalUnits.current.distance(track.distanceMeters),
            titleColor = MaterialTheme.colorScheme.onSurface,
            subtitle = buildAnnotatedString {
                appendTime(track.startedAt, zone, reader, shiftColor)
                track.endedAt?.let { endedAt ->
                    append(" – ")
                    appendTime(endedAt, endZone ?: zone, reader, shiftColor)
                }
                append(" · ${formatDuration(track.startedAt, track.endedAt)}")
            },
        )
    }
}

/**
 * A history sweep, while it runs: distances and end times shift behind it as each track is
 * re-derived, so it says so instead of the list quietly rearranging itself. Determinate — the total
 * is known up front — and it removes itself when the sweep ends.
 */
@Composable
private fun SweepBanner(progress: SweepStatus.Progress, modifier: Modifier = Modifier) {
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
                // is the backstop, not the plan. "Updating", not "Trimming" or "Correcting": a
                // sweep re-derives, and hands back as readily as it takes.
                Text(
                    when (progress.kind) {
                        SweepStatus.Kind.EDGE_STAYS -> "Updating recording overruns"
                        SweepStatus.Kind.STATS -> "Updating track distances"
                    },
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

/**
 * A derived stationary period between two tracks. A resolved place shows its label, an unnamed one
 * the city it sits in, and an unnamed recurring cluster its visit count as well. Tap → name. (The
 * derivation's observed/inferred provenance is deliberately NOT rendered: the customer can't act on
 * it either way.)
 */
@Composable
private fun StayRow(
    item: TimelineItem.StayItem,
    shape: RoundedCornerShape,
    zone: ZoneId,
    highlighted: Boolean,
    onMerge: (TrackMerge.Plan) -> Unit,
    onClick: () -> Unit,
) {
    val place = item.place
    val named = place?.label != null
    // A short same-activity stay can be swiped to merge its two tracks — the merged track replaces
    // the stay and both originals, and Undo unmerges. Ineligible stays (no plan) aren't swipeable.
    MergeSwipeable(item.merge, shape, onMerge) {
        StayCard(item, shape, named, highlighted, zone, onClick)
    }
}

/**
 * Wraps an interval's card in the swipe-to-merge affordance, or hands it back bare when the
 * interval carries no offer. Both interval rows — stays and short gaps — merge on one rule, so the
 * gesture, its color, its icon and its label are described once here rather than per row type.
 */
@Composable
private fun MergeSwipeable(
    plan: TrackMerge.Plan?,
    shape: RoundedCornerShape,
    onMerge: (TrackMerge.Plan) -> Unit,
    card: @Composable () -> Unit,
) {
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
    zone: ZoneId,
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
    // A merge-eligible short stop is marked in tertiary (matching the swipe-to-merge hint) as a
    // *badge* on the disc's corner rather than as its glyph: being mergeable is a fact about the
    // stay, while the glyph answers where the user was, and neither should cost the other.
    val mergeable = item.merge != null
    // A categorized place says what the stop was for in the icon column alone — its glyph and its
    // group's tint — where an untagged one shows the generic pin. Deliberately not repeated as text
    // in the subtitle: the row would then name in words what it has just drawn, and the line is
    // shared with when and how long, which nothing else says.
    val category = place?.category
    // Accent means categorized here as it does on the Places list (placeDiscTint) — one rule, so the
    // same place can't read as accented on one screen and neutral on the other.
    val tint = placeDiscTint(category)
    val end = stay.end
    val startsAtMidnight = isLocalMidnight(stay.start, zone)
    val endsAtMidnight = end != null && isLocalMidnight(end, zone)
    // Ongoing from midnight = all of today so far; completed midnight-to-midnight slices of a
    // multi-day stay read the same — and neither states a clock time, so neither is marked.
    val allDay = startsAtMidnight && (end == null || endsAtMidnight)
    val visits = place?.visitCount?.takeIf { !named && it >= PlaceResolver.NOTABLE_VISIT_MIN }
    val reader = timelineZone()
    val shiftColor = zoneShiftColor
    ListRowCard(
        shape = shape,
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        icon = category.discIcon,
        tint = tint,
        iconDescription = category?.label ?: "Stay",
        discAlpha = placeDiscAlpha(category),
        badge = if (mergeable) Icons.Filled.Pause else null,
        badgeDescription = if (mergeable) "Short stop, can be merged away" else null,
        // The place leads; when (with midnight slices phrased humanly) is the metadata line. The
        // gazetteer's city stands in where the user has said nothing, dimmed by `named` below so a
        // worked-out name never reads as one they chose. A merge-eligible stop the gazetteer can't
        // reach names the situation instead.
        title = place?.name ?: if (mergeable) "Short stop" else "Stayed",
        titleColor = placeTitleColor(named),
        subtitle = buildAnnotatedString {
            // Whichever bounds this row states, it states them marked; the wording around them is
            // the same as it ever was.
            when {
                allDay -> append("All day")
                end == null -> {
                    appendTime(stay.start, zone, reader, shiftColor)
                    append(" – now")
                }
                startsAtMidnight -> {
                    append("Until ")
                    appendTime(end, zone, reader, shiftColor)
                }
                endsAtMidnight -> {
                    append("From ")
                    appendTime(stay.start, zone, reader, shiftColor)
                }
                else -> {
                    appendTime(stay.start, zone, reader, shiftColor)
                    // A stop the recorder only caught the tail end of lands on one clock minute at
                    // both bounds; "09:11 – 09:11" reads as a rendering fault rather than a moment.
                    // Compared as minutes rather than as rendered text: the same question, without
                    // formatting both ends again to ask it.
                    if (end / 60_000 != stay.start / 60_000) {
                        append(" – ")
                        appendTime(end, zone, reader, shiftColor)
                    }
                }
            }
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
    )
}

/**
 * Movement the recorder missed: neighboring track endpoints disagree. Deliberately subdued — most
 * such gaps are one place misclustered as two, so the card names each side it holds as a full-width
 * tappable line (the app's row-tap language, not inline links) opening that place, where a re-pin or
 * wider radius fixes the split. Two pins joined by a dashed connector in the icon column draw the
 * unrecorded leg as a map would. Newest-first timeline: the destination sits above (adjacent to
 * the later track), the source below.
 *
 * A gap spanning midnight renders once per day, and a day is only told what happened in it: the
 * departure appears on the day it happened, the arrival on the day it happened, and a day the
 * absence merely passes through gets neither pin — a dashed line and "all day" is the whole card,
 * which is the honest amount the app knows about that day.
 */
@Composable
private fun GapRow(
    item: TimelineItem.GapItem,
    shape: RoundedCornerShape,
    zone: ZoneId,
    onOpenPlace: (String) -> Unit,
    onMerge: (TrackMerge.Plan) -> Unit,
) {
    // A gap short enough to be one outing the recorder split swipes away exactly as a short stop
    // does — the leg it missed survives as the merged track's segment break. Longer gaps are real
    // absences and aren't swipeable.
    MergeSwipeable(item.merge, shape, onMerge) { GapCard(item, shape, zone, onOpenPlace) }
}

@Composable
private fun GapCard(
    item: TimelineItem.GapItem,
    shape: RoundedCornerShape,
    zone: ZoneId,
    onOpenPlace: (String) -> Unit,
) {
    val gap = item.gap
    // A midnight bound is a slice seam, not a bound of the absence (StayRow reads its own the same
    // way): the gap runs on into the neighbouring day, so this slice knows neither how long the
    // absence was nor where it ended. Each side is named only on the day that side happened —
    // otherwise every day of a three-day gap claims the arrival, two days before it happened.
    // A crossing's halves were cut at the arrival's midnight, not at one of their own, so neither
    // holds a seam: each states the end it speaks for and says nothing about the other, which is
    // sitting under its own day's heading elsewhere in the list.
    //
    // Which reduces the whole card to two questions — does this row hold the real departure, and
    // does it hold the real arrival — answered once here and read everywhere below.
    val reader = timelineZone()
    val ordinary = !item.spansClocks
    val holdsDeparture =
        item.departureZone != null && !(ordinary && isLocalMidnight(gap.start, zone))
    val holdsArrival =
        item.arrivalZone != null && !(ordinary && isLocalMidnight(gap.end, zone))
    val arrival = if (holdsArrival) item.toPlace else null
    val departure = if (holdsDeparture) item.fromPlace else null
    // Both ends on one row only where a hop landed on the day it left; each then needs its own
    // clock, since the line between them can carry only one.
    val shiftColor = zoneShiftColor
    val arrivalAt = if (item.spansClocks) gapEndTime(gap.end, zone, reader, shiftColor) else null
    val departureAt = item.departureZone?.takeIf { item.spansClocks }
        ?.let { gapEndTime(gap.start, it, reader, shiftColor) }
    Card(modifier = Modifier.fillMaxWidth(), shape = shape) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            GapPlaceLine(arrival, arrivalAt, onOpenPlace)
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tertiary marks a mergeable interval throughout the timeline — the same accent
                // the short-stop row wears, so the swipe is discoverable in both places.
                val strokeColor = if (item.merge != null) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                }
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
                    buildAnnotatedString {
                        append("missing recording · ")
                        // A duration says the same thing on any clock, so the two-end case states
                        // one and is marked nowhere; a half that states a real bound marks it, as
                        // every clock time on the timeline does.
                        when {
                            holdsDeparture && holdsArrival ->
                                append(formatDurationMs(gap.end - gap.start))
                            holdsDeparture -> {
                                append("from ")
                                appendTime(gap.start, zone, reader, shiftColor)
                            }
                            holdsArrival -> {
                                append("until ")
                                appendTime(gap.end, zone, reader, shiftColor)
                            }
                            else -> append("all day")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            GapPlaceLine(departure, departureAt, onOpenPlace)
        }
    }
}

/** When one end of a crossing happened, on that end's own clock, its offset raised against it. */
private fun gapEndTime(epochMs: Long, zone: ZoneId, reader: ZoneId, shiftColor: Color) =
    buildAnnotatedString { appendTime(epochMs, zone, reader, shiftColor) }

/**
 * One side of a gap: a full-width tappable line (pin glyph + place name, ripple across the row
 * like every other tappable row) opening the place's detail — stay-less clusters have zero-visit
 * rows (summarize emits every cluster), so every known side opens. An unknown side renders
 * nothing; its position tells the story.
 *
 * [at] is set only on a crossing, where each end runs on its own clock and the row has no single one
 * to state above the connector. It trails the name rather than leading it: which two places the
 * absence lies between is what the row is for, and the times qualify them.
 */
@Composable
private fun GapPlaceLine(
    place: PlaceResolver.ResolvedStay?,
    at: AnnotatedString?,
    onOpenPlace: (String) -> Unit,
) {
    if (place == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPlace(place.key) }
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
            text = place.name ?: "unnamed place",
            style = MaterialTheme.typography.titleMedium,
            color = placeTitleColor(named = place.label != null),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (at != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = at,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
