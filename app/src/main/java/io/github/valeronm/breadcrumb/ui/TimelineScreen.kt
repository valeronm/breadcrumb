package io.github.valeronm.breadcrumb.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.SweepStatus
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.TravelDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.activityTotals
import io.github.valeronm.breadcrumb.domain.dayCategoryTotals
import io.github.valeronm.breadcrumb.util.PerLocale
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

/**
 * The day whose rows top the Timeline's viewport, published for the top bar's add-trip action —
 * a trip added while looking at a day is usually a trip on it. Read on tap rather than observed:
 * a plain holder, not snapshot state, so scrolling recomposes nothing above the tab. [read]
 * returns null wherever there is no list to ask (the empty and restoring states).
 */
internal class TimelineViewedDay {
    var read: () -> LocalDate? = { null }
}

/** What the backup picker filters on. Named rather than spelled at the launch, which is all. */
private val BACKUP_MIME_TYPES =
    arrayOf("application/gzip", "application/x-gzip", "application/octet-stream")

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
    viewedDay: TimelineViewedDay,
    onOpen: (Long) -> Unit,
    onOpenPlace: (String) -> Unit,
    /** Open the add-trip form on an absence, holding whatever the gap row it came from knows. */
    onAddTrip: (TripDraft) -> Unit,
    onReplay: (TrackSummary) -> Unit,
) {
    val context = LocalContext.current

    // No list, no day — the empty and restoring branches below return before the real reader is
    // installed, and a closure left over from a previous composition would read dead state.
    viewedDay.read = { null }

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
    // Resolved here because [dayLabel] is callable from a plain function and [stringResource] is not;
    // each header then words itself, rather than reading its text out of a list it must stay aligned
    // with. Day label -> its header's lazy-item index: what the fast scroller jumps between.
    val todayText = stringResource(R.string.relative_today).standaloneCase()
    val yesterdayText = stringResource(R.string.relative_yesterday).standaloneCase()
    val today = LocalDate.now(timelineZone())
    val dayAnchors = remember(groups, today, todayText, yesterdayText) {
        buildList {
            var index = 0
            groups.forEach { group ->
                add(ScrollStop(dayLabel(group.date, today, todayText, yesterdayText), index))
                index += group.items.size + 1
            }
        }
    }
    // The topmost visible item's group: the last anchor at or above it — the anchors are parallel
    // to [groups], so the span arithmetic stays theirs alone. Run only when the holder is asked,
    // never per scroll frame.
    viewedDay.read = {
        val first = listState.firstVisibleItemIndex
        dayAnchors.indexOfLast { it.itemIndex <= first }.takeIf { it >= 0 }?.let { groups[it].date }
    }
    // The holder outlives this tab (it belongs to the top bar's scope): a closure left behind
    // would pin the whole day-group derivation of a list no longer on screen.
    DisposableEffect(viewedDay) {
        onDispose { viewedDay.read = { null } }
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
            if (i >= 0) (dayAnchors[g].itemIndex + 1 + i) to groups[g].items[i] else null
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
                ?.let { listState.scrollToItem(dayAnchors[it].itemIndex) }
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
        if (group >= 0) listState.scrollToItem(dayAnchors[group].itemIndex)
        onDayTargetShown()
    }
    // Both interval rows offer the same merge, so they share one handler rather than two copies
    // of the undo wiring. Both undo messages are resolved here: the callbacks below run outside
    // the composition.
    val mergedMessage = stringResource(R.string.timeline_undo_merged)
    val deletedMessage = stringResource(R.string.timeline_undo_deleted)
    val onMerge = { plan: TrackMerge.Plan ->
        viewModel.mergeTracks(plan) { mergedId ->
            undo.show(mergedMessage) { viewModel.unmergeTracks(mergedId, plan) }
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
                        val label = dayLabel(group.date, today, todayText, yesterdayText)
                        DayHeader(label, dayTracks, dayItems, away) {
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
                                    undo.show(deletedMessage) { viewModel.restoreTrack(id) }
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
                                GapRow(item, shape, item.clock, onOpenPlace, onMerge, onAddTrip)
                        }
                    }
                }
            }
        }
        FastScroller(
            // A day is both where the drag lands and what it ticks on: the history is read by the
            // day, and a stop inside one would leave the reader mid-afternoon with the heading
            // above scrolled past.
            state = listState,
            stops = dayAnchors,
            contentDescription = stringResource(R.string.timeline_scroll_to_day),
            label = { it },
        )
    }
}

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
 * **How the nights were placed is deliberately not shown**: a journey resting partly on unplaced
 * nights looks the same as one placed throughout, because the difference is not something a reader
 * can act on.
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
        // The length leads and the names follow — the run of days is what makes the band a
        // journey, and an unnamed one still has it to say.
        val days = pluralStringResource(R.plurals.timeline_journey_days, away.dayCount, away.dayCount)
        val names = TravelNaming.title(away.summary.destinations)
        Text(
            if (names == null) days else "$days · $names",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    val totals = remember(dayTracks) { activityTotals(dayTracks, System.currentTimeMillis()) }
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
                // Which day of the journey this is — the band above carries the journey and its
                // length, so this says only where in it the reader stands. Same size as the date
                // it continues, the tint alone telling the two apart. The separator stays in code:
                // it is layout between the date and the marker, not part of what either says.
                away?.let {
                    Text(
                        "· " + stringResource(R.string.timeline_away_day, it.ordinal),
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
                        contentDescription = stringResource(R.string.timeline_share_day, label),
                        // Match the top bar's action-icon tint — plain onSurface reads too bright here.
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        // Day totals per recorded activity, in the row style: tinted glyph + distance · duration.
        // Wrapping, whole figures at a time: a travel day holds a flight, a train, a walk and a
        // drive at once, and a row would crush whatever doesn't fit into a one-character column.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val context = LocalContext.current
            for (total in totals) {
                DayTotal(
                    icon = activityIcon(total.type),
                    description = activityLabel(context, total.activityType),
                    tint = activityColor(total.type),
                    text = "${distanceText(total.meters)} · ${durationText(total.durationMs)}",
                )
            }
        }
        // Where the day went, under what it covered — its own line rather than more of the one
        // above, so places and movement never interleave when either wraps.
        if (categoryTotals.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (total in categoryTotals) {
                    DayTotal(
                        icon = total.category.icon,
                        description = stringResource(total.category.labelRes),
                        // Group color, as the rows below wear it — the totals line then reads as the
                        // same palette the day's stays were drawn in.
                        tint = categoryColor(total.category),
                        text = durationText(total.durationMs),
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
    val groups = mutableListOf<DayGroup>()
    var run = mutableListOf<TimelineItem>()
    var date: LocalDate? = null
    for (item in items) {
        val itemDate = item.filedOn
        if (itemDate != date) {
            date?.let { groups += DayGroup(it, run) }
            run = mutableListOf()
            date = itemDate
        }
        run += item
    }
    date?.let { groups += DayGroup(it, run) }
    return groups
}

/**
 * One day's rows, carrying the date and not a heading for it. Naming the day is [dayLabel]'s job on
 * the screen: a date is what answers whether the day falls inside a travel, while a heading is
 * language, and phrasing it here would put a locale-resolved formatter — which reaches the Android
 * framework — inside a function whose whole point is being testable on a plain JVM.
 */
internal class DayGroup(val date: LocalDate, val items: List<TimelineItem>)

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

internal fun TimelineItem.rowKey(): String = when (this) {
    is TimelineItem.TrackItem -> "track:${summary.id}"
    is TimelineItem.StayItem -> "stay:${stay.afterTrackId}:${stay.start}"
    is TimelineItem.GapItem -> "gap:${gap.start}"
}

private val dayHeaderFormat by PerLocale { localizedDateFormat("EEEEdMMMMy", it) }

private val dayHeaderFormatThisYear by PerLocale { localizedDateFormat("EEEEdMMMM", it) }

/**
 * A day header stands on its own, so it takes the capital its language would give it there. The two
 * relative names are passed in already resolved, which keeps this callable from inside a `remember`
 * — [stringResource] is not.
 */
private fun dayLabel(
    date: LocalDate,
    today: LocalDate,
    todayText: String,
    yesterdayText: String,
): String = when {
    date == today -> todayText
    date == today.minusDays(1) -> yesterdayText
    // The current year goes without saying.
    date.year == today.year -> date.format(dayHeaderFormatThisYear).standaloneCase()
    else -> date.format(dayHeaderFormat).standaloneCase()
}

/**
 * The Timeline's empty state — the only place that offers restoring a backup.
 * With tracks present a restore would have to merge with them, so the offer disappears as soon
 * as the first track exists.
 *
 * Octet-stream is accepted alongside the gzip types because that is what a file manager hands over
 * for an extension it does not recognize; the importer rejects a file that isn't a backup anyway.
 */
@Composable
private fun EmptyTracksState(viewModel: TrackListViewModel) {
    val appContext = LocalContext.current.applicationContext
    val progress by viewModel.importExport.restoreProgress.collectAsStateWithLifecycle()
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importExport.restoreBackup(uri) { summary ->
            val message = if (summary == null) {
                appContext.getString(R.string.timeline_restore_failed)
            } else {
                appContext.getString(R.string.timeline_restored, summary.tracks, summary.places)
            }
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }
    EmptyState(
        stringResource(
            if (progress == null) R.string.timeline_empty else R.string.timeline_restoring,
        ),
        Modifier.fillMaxSize().padding(24.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        val restoring = progress
        if (restoring == null) {
            TextButton(onClick = {
                restoreLauncher.launch(BACKUP_MIME_TYPES)
            }) { Text(stringResource(R.string.timeline_restore_button)) }
        } else {
            Text(
                restoring.tracksTotal?.let {
                    stringResource(R.string.timeline_restoring_count_of, restoring.tracksDone, it)
                } ?: stringResource(R.string.timeline_restoring_count, restoring.tracksDone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackRow(
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
        iconDescription = stringResource(R.string.common_delete),
        onDismiss = onDelete,
    ) {
        val activity = ActivityType.ofName(track.activityType)
        val activityName = activityLabel(LocalContext.current, track.activityType)
        val reader = timelineZone()
        // Each end on its own clock where they differ — a track is the one recorded row that can
        // cross a border, and flattening it onto the departure's clock hides the crossing. The two
        // times then no longer span the duration beside them, which is why each carries its own
        // offset: read together they say the drive lost an hour to the border, which is the truth.
        val shiftColor = zoneShiftColor
        val readerClock = LocalReaderClock.current
        ListRowCard(
            // Long-press replays the track, which Card's own onClick can't express.
            modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onReplay),
            shape = shape,
            icon = activityIcon(activity),
            disc = activityDiscStyle(activity),
            iconDescription = activityName,
            // What happened leads; when it happened is the metadata line.
            title = "$activityName · " + distanceText(track.distanceMeters),
            titleColor = MaterialTheme.colorScheme.onSurface,
            // Worded before the builder, which is not a composable scope.
            subtitle = durationText(track.startedAt, track.endedAt).let { length ->
                buildAnnotatedString {
                    appendTime(track.startedAt, zone, reader, shiftColor, readerClock)
                    track.endedAt?.let { endedAt ->
                        append(" – ")
                        appendTime(endedAt, endZone ?: zone, reader, shiftColor, readerClock)
                    }
                    append(" · $length")
                }
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
                // is the backstop, not the plan.
                Text(
                    stringResource(R.string.timeline_sweep),
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
 * the city it sits in, and an unnamed recurring cluster its visit count as well. Tap → name.
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
        iconDescription = stringResource(R.string.timeline_merge),
        onDismiss = { onMerge(plan) },
    ) { card() }
}

@Composable
internal fun StayCard(
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
    // Accent means categorized here as it does on the Places list (placeDiscStyle) — one rule, so
    // the same place can't read as accented on one screen and neutral on the other.
    val disc = placeDiscStyle(category)
    val visits = place?.visitCount?.takeIf { !named && it >= PlaceResolver.NOTABLE_VISIT_MIN }
    // Worded here: the annotated-string builder below is not a composable scope, and every one of
    // these brackets a clock time it draws rather than interpolates.
    val visitsText = visits?.let { visitCountLabel(it) }
    val stated = stayBounds(stay.start, stay.end, item.holdsStart, item.holdsEnd)
    // Which bounds carry a duration beside them is [StayBounds.withDuration]'s to say. What this row
    // adds is the other floor: a stay spanning less than StayDeriver.REPORTABLE_DURATION_MS shows
    // none either, the stop having been longer than its bounds say, so "0m" is worse than silence.
    val reportableText = stay.reportableDurationMs(System.currentTimeMillis())
        ?.takeIf { stated.withDuration }
        ?.let { durationText(it) }
    val reader = timelineZone()
    val shiftColor = zoneShiftColor
    val readerClock = LocalReaderClock.current
    fun marked(atMs: Long) = markedTime(atMs, zone, reader, shiftColor, readerClock)
    // Whichever bounds the row states, marked, spliced into a whole sentence — so the wording around
    // a time is the language's to arrange, not this `when`'s. Each bound is formatted inside the
    // branch that states it: most branches state one, and a row that names no clock time formats none.
    val bounds = when (stated) {
        StayBounds.AllDay -> AnnotatedString(stringResource(R.string.timeline_all_day))
        is StayBounds.Since ->
            annotatedStringResource(R.string.timeline_stay_since, marked(stated.start))
        is StayBounds.Until ->
            annotatedStringResource(R.string.timeline_stay_until, marked(stated.end))
        is StayBounds.From ->
            annotatedStringResource(R.string.timeline_stay_from, marked(stated.start))
        is StayBounds.At -> marked(stated.moment)
        is StayBounds.Between -> annotatedStringResource(
            R.string.timeline_stay_between,
            marked(stated.start),
            marked(stated.end),
        )
    }
    ListRowCard(
        shape = shape,
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        icon = category.discIcon,
        disc = disc,
        iconDescription = category?.let { stringResource(it.labelRes) } ?: stringResource(R.string.place_stay),
        badge = if (mergeable) Icons.Filled.Pause else null,
        badgeDescription = if (mergeable) stringResource(R.string.timeline_short_stop_mergeable) else null,
        // The place leads; when (with midnight slices phrased humanly) is the metadata line. The
        // gazetteer's city stands in where the user has said nothing, dimmed by `named` below so a
        // worked-out name never reads as one they chose. A merge-eligible stop the gazetteer can't
        // reach names the situation instead.
        title = place?.name ?: stringResource(
            if (mergeable) R.string.timeline_short_stop else R.string.timeline_stayed,
        ),
        titleColor = placeTitleColor(named),
        subtitle = buildAnnotatedString {
            append(bounds)
            if (reportableText != null) {
                append(" · ")
                append(reportableText)
            }
            if (visitsText != null) {
                append(" · $visitsText")
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
 * **An absence is cut once, at the midnight opening the day it ended** — never per day the way a
 * stay is. A stay reports something true about each day it covers; an absence reports nothing about
 * the days in the middle of it, so cutting per day gave those a row carrying no time, no pin and no
 * end to offer, whose whole content was that nothing is known. Its two ends are real, and each
 * belongs to its own day: the departure day says when recording stopped, the arrival day when it
 * resumed, and each half says nothing about the end sitting under the other's heading. The days in
 * between get no rows at all, which is what an unrecorded day is — how long it ran is read off the
 * two day headings.
 *
 * A crossing takes the same single cut for a second reason: its ends ran on different clocks, and
 * cutting it at either end's midnights throughout would file one absence under two calendars.
 *
 * The other way to close a gap is to say what happened in it, so the card offers the add-trip form
 * pre-filled with the ends it holds.
 */
@Composable
private fun GapRow(
    item: TimelineItem.GapItem,
    shape: RoundedCornerShape,
    zone: ZoneId,
    onOpenPlace: (String) -> Unit,
    onMerge: (TrackMerge.Plan) -> Unit,
    onAddTrip: (TripDraft) -> Unit,
) {
    // A gap short enough to be one outing the recorder split swipes away exactly as a short stop
    // does — the leg it missed survives as the merged track's segment break. Longer gaps are real
    // absences and aren't swipeable.
    MergeSwipeable(item.merge, shape, onMerge) { GapCard(item, shape, zone, onOpenPlace, onAddTrip) }
}

@Composable
internal fun GapCard(
    item: TimelineItem.GapItem,
    shape: RoundedCornerShape,
    zone: ZoneId,
    onOpenPlace: (String) -> Unit,
    onAddTrip: (TripDraft) -> Unit,
) {
    val gap = item.gap
    // The whole card is drawn from the two questions the slicer answered when it cut — see
    // GapItem.holdsDeparture. Read once here, never re-derived: an absence inside one day is one row
    // holding both ends, anything longer is two, each saying nothing about the end sitting under the
    // other's day heading.
    val reader = timelineZone()
    val holdsDeparture = item.holdsDeparture
    val holdsArrival = item.holdsArrival
    val arrival = if (holdsArrival) item.toPlace else null
    val departure = if (holdsDeparture) item.fromPlace else null
    // Both ends on one row only where a hop landed on the day it left; each then needs its own
    // clock, since the line between them can carry only one.
    val shiftColor = zoneShiftColor
    val readerClock = LocalReaderClock.current
    val arrivalAt =
        if (item.spansClocks) markedTime(gap.end, zone, reader, shiftColor, readerClock) else null
    val departureAt = item.departureZone?.takeIf { item.spansClocks }
        ?.let { markedTime(gap.start, it, reader, shiftColor, readerClock) }
    // The trip this absence is missing, as far as this row can state it — the same two questions
    // again, so an end the card doesn't name is an end the form isn't handed: the far side of an
    // absence cut at midnight is a fact about the other day's row, and would go into the form as
    // fact and be committed unread.
    // Built once per row rather than per composition — the ripple invalidates this card on every
    // press, and nothing here is read until the "+" is tapped. The row's own data and the clock it
    // is read on decide all of it, so those are the whole key.
    val draft = remember(item, zone) {
        TripDraft(
            day = item.filedOn,
            origin = if (holdsDeparture) draftEndOf(departure, gap.from, gap.start) else null,
            destination = if (holdsArrival) draftEndOf(arrival, gap.to, gap.end) else null,
        )
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = shape) {
        // One whole sentence per case, worded here because the Text below is not a composable scope.
        // A duration says the same thing on any clock, so the two-end case states one and is marked
        // nowhere; a half that states a real bound marks it, as every clock time on the timeline does.
        // A gap row holds at least one end: the slicer emits an absence holding both, or a
        // crossing half holding exactly one. There is no wording for "neither" — it would have to
        // state a clock time for an end the row does not speak for.
        val gapLine = when {
            holdsDeparture && holdsArrival -> annotatedStringResource(
                R.string.timeline_gap_lasting,
                durationText(gap.end - gap.start),
            )
            holdsDeparture -> annotatedStringResource(
                R.string.timeline_gap_from,
                markedTime(gap.start, zone, reader, shiftColor, readerClock),
            )
            holdsArrival -> annotatedStringResource(
                R.string.timeline_gap_until,
                markedTime(gap.end, zone, reader, shiftColor, readerClock),
            )
            else -> error("a gap row holds at least one end; this one was stamped with neither")
        }
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
                    modifier = Modifier.weight(1f),
                    text = gapLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
                // The card's one action, so it carries the card's only accent — the same glyph the
                // Timeline's own top bar opens the form with. Offered on every gap row, because
                // every gap row now speaks for at least one end: the rows that could offer nothing
                // were the per-day middles, and those are no longer cut.
                IconButton(onClick = { onAddTrip(draft) }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.action_add_missing_trip),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            GapPlaceLine(departure, departureAt, onOpenPlace)
        }
    }
}

/**
 * One end of an absence as the add-trip form takes it, under the name the user gave that spot if any.
 *
 * **[at] is where the recording itself was**, not where the place holding it is pinned: this end is
 * timed at the instant the neighbouring track began or ended, and that track's own first or last fix
 * is where the phone demonstrably was then. A place's pin is the middle of a label — it can sit a
 * street from the car park the recording stopped in — so drawing the entered leg from there would
 * leave a jump between it and the path it is filling in. Falls back to the pin where the recorder
 * had no fix at all, and to nothing where neither exists; the form asks the map for the rest.
 */
private fun draftEndOf(
    place: PlaceResolver.ResolvedStay?,
    at: Coordinate?,
    atMs: Long,
) = TripDraftEnd(at = at ?: place?.pin, placeName = place?.label, timeMs = atMs)

/**
 * One side of a gap: a full-width tappable line (category disc + place name, ripple across the row
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
    val category = place.category
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPlace(place.key) }
            // A floor rather than more padding, which would loosen the connector this row sits
            // against: the 36.dp disc and 4.dp either side leave the row short of a finger.
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconDisc(
            icon = category.discIcon,
            style = placeDiscStyle(category),
            contentDescription = category?.let { stringResource(it.labelRes) },
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = place.name ?: stringResource(R.string.timeline_unnamed_place),
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
