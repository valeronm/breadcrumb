package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.TravelDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.activityTotals
import io.github.valeronm.breadcrumb.domain.dayCategoryTotals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

/**
 * The day whose rows top the Timeline's viewport, published for the top bar's add-trip action —
 * a trip added while looking at a day is usually a trip on it. Read on tap rather than observed:
 * a plain holder, not snapshot state, so scrolling recomposes nothing above the tab. [read]
 * returns null wherever there is no list to ask (the empty and restoring states).
 */
internal class TimelineViewedDay {
    var read: () -> LocalDate? = { null }
}

/**
 * A one-shot request to the Timeline from another screen. One slot for every kind, consumed by
 * the view that answers it and handed back empty through `onJumpShown`, so the value a view
 * composed with is never mistaken for one that arrived while it was up. A request identical to
 * one still pending is the same write and collapses into it — harmless, since every kind lands in
 * one place whether asked once or twice.
 */
internal sealed interface TimelineJump {
    /**
     * A request landing on one row, found by [matches]; where the row is gone (a re-derivation
     * moved it), the day holding [anchorMs] stands in.
     */
    sealed interface Row : TimelineJump {
        val anchorMs: Long

        fun matches(item: TimelineItem): Boolean
    }

    /** Land on this stay — a visit tapped on a place's detail. */
    data class Visit(val stay: StayDeriver.Stay) : Row {
        override val anchorMs: Long get() = stay.start

        // Multi-day stays are sliced per day, but the first slice keeps the stay's original start.
        override fun matches(item: TimelineItem): Boolean =
            item is TimelineItem.StayItem &&
                item.stay.afterTrackId == stay.afterTrackId &&
                item.stay.start == stay.start
    }

    /**
     * Land on this gap at its departure end, or at its arrival end — a gap longer than a day is two
     * rows, one per end. [gap] may be the derivation's or either half the slicer cut from it: a half
     * keeps the real bound it holds, which is the one [anchorMs] reads.
     */
    data class Gap(val gap: StayDeriver.Gap, val atDeparture: Boolean) : Row {
        override val anchorMs: Long get() = gap.boundAt(departure = atDeparture)

        override fun matches(item: TimelineItem): Boolean =
            item is TimelineItem.GapItem &&
                item.gap.afterTrackId == gap.afterTrackId &&
                (if (atDeparture) item.holdsDeparture else item.holdsArrival)
    }

    /** Land on this day — a journey's day, or the map's parting day on a return to the list. */
    data class Day(val date: LocalDate) : TimelineJump

    /** Back to today — the open tab tapped again. */
    data object Home : TimelineJump
}

private enum class TimelinePage(@StringRes val labelRes: Int) {
    LIST(R.string.timeline_view_list),
    MAP(R.string.timeline_view_map),
}

/** One instance for the switch's sake: a list rebuilt per pass would recompose it per pass too. */
private val timelinePageLabels = TimelinePage.entries.map { it.labelRes }

/** The Timeline tab: the day-by-day list and one day on a map, two views under a [ViewSwitchRow]. */
@Composable
internal fun TimelineTab(
    /** Null while the derivation is still running — see [TrackListViewModel.timeline]. */
    items: List<TimelineItem>?,
    viewModel: TrackListViewModel,
    undo: UndoSnackbar,
    /** The request pending from another screen, if any — see [TimelineJump]. */
    jump: TimelineJump?,
    onJumpShown: () -> Unit,
    viewedDay: TimelineViewedDay,
    onOpen: (Long) -> Unit,
    onOpenPlace: (String) -> Unit,
    /** Open the journey detail — the band over a day inside one is the way in from here. */
    onOpenJourney: (TravelNaming.Summary) -> Unit,
    onOpenGap: (afterTrackId: Long) -> Unit,
    onReplay: (TrackSummary) -> Unit,
    /** Open the Record tab — the recording row's tap, that being where the recording draws live. */
    onOpenRecording: () -> Unit,
    onOpenData: () -> Unit,
) {
    // No list, no day — the empty and restoring branches below return before a real reader is
    // installed, and a closure left over from a previous composition would read dead state.
    viewedDay.read = { null }

    // Held on the empty/progress screen for the whole restore or import, not just while the list
    // is empty: the first inserted batch would otherwise replace this screen (and its progress
    // text) with a timeline that keeps re-deriving as tracks pour in. The finished timeline appears
    // at once.
    val restoreProgress by viewModel.importExport.restoreProgress.collectAsStateWithLifecycle()
    val googleImportProgress by viewModel.importExport.googleTimelineImportProgress.collectAsStateWithLifecycle()
    val recording by viewModel.recordingRow.collectAsStateWithLifecycle()
    // In priority order: a restore or import outranks everything, since that screen reports its
    // progress for the whole run; then "not derived yet"; then a history that really is empty.
    // Gated above the switch rather than per view: both views would show the same blank.
    when {
        restoreProgress != null || googleImportProgress != null -> {
            EmptyTracksState(viewModel, onOpenData)
            return
        }
        items == null -> {
            DerivingState(Modifier.fillMaxSize())
            return
        }
        // A recording alone is not an empty history: the list shows its row.
        items.isEmpty() && recording == null -> {
            EmptyTracksState(viewModel, onOpenData)
            return
        }
    }

    // The two views are one timeline looked at two ways, so they share their day: a switch to the
    // map opens it on the day being read, and a return to the list lands on the day the map was
    // moved to. Saveable so the shared day survives process death; today until the list says
    // otherwise, the map answering "where was I" and today being the day that is usually about.
    var mapDay by rememberSaveable { mutableStateOf(LocalDate.now(timelineZone())) }
    // Which view is showing — a standing preference about how you read your timeline, persisted
    // as the Places tab's is: the two tabs wear the same switch, and the same choice should
    // survive the same way. A remembered map is never a map of some past day: the shared day
    // above resets to today on a fresh open. Only the switch itself writes the preference — a
    // date tapped into the map and a jump target landing on the list are errands, and a
    // recreation mid-errand returns to the preferred view.
    val context = LocalContext.current
    var page by remember {
        mutableStateOf(if (AppSettings.timelineViewMap(context)) TimelinePage.MAP else TimelinePage.LIST)
    }
    // The day the map was entered on. A return to the list scrolls only if the map moved off it —
    // a glance at the day being read and back must not cost the reading position. Every route to
    // the map rewrites it, so the initial value is never compared; it only has to be some day.
    var mapOpenedOn by rememberSaveable { mutableStateOf(mapDay) }
    // The map's parting request to the list, consumed by the same jump a journey tap sends.
    var mapDayTarget by remember { mutableStateOf<LocalDate?>(null) }

    // The list view's own reader; the holder handed in answers for whichever view is showing, so a
    // trip added while looking at a day's map starts on that day just as it does from the list.
    val listViewedDay = remember { TimelineViewedDay() }
    viewedDay.read = {
        if (page == TimelinePage.MAP) mapDay else listViewedDay.read()
    }
    // The holder outlives this tab (it belongs to the top bar's scope): a closure left behind
    // would pin the composition it captured after the tab is gone.
    DisposableEffect(viewedDay) {
        onDispose { viewedDay.read = { null } }
    }

    val switchTo = { selected: TimelinePage ->
        when (selected) {
            // Read before the switch composes anything: the reader answers for the list only
            // while it is on screen. Falls back to the map's last day when the list cannot
            // say (it is what the map held, which is as synced as the tab can be).
            TimelinePage.MAP -> {
                val day = listViewedDay.read() ?: mapDay
                mapDay = day
                mapOpenedOn = day
                page = TimelinePage.MAP
            }
            TimelinePage.LIST -> {
                if (mapDay != mapOpenedOn) mapDayTarget = mapDay
                page = TimelinePage.LIST
            }
        }
        AppSettings.setTimelineViewMap(context, selected == TimelinePage.MAP)
    }

    // The tab's half of a landing, taken before either view sees it and total over the kinds, so
    // which view a request is bound for is settled in one place. A Row or a Day is the list's to
    // consume — its effects only run while the list is composed, so a request arriving while the
    // map is showing would otherwise wait forever; straight to the list rather than through
    // [switchTo], since the request carries its own destination and the map's parting day would
    // race it. Home is the open tab tapped again, and the list answers it by running itself to its
    // top, leaving only the map's home — today — to be answered here, where the showing view is
    // known. [mapOpenedOn] deliberately stays put: home means today on both views, so a later
    // return to the list should land there too.
    SideEffect(jump) {
        when (jump) {
            is TimelineJump.Row, is TimelineJump.Day -> page = TimelinePage.LIST
            TimelineJump.Home ->
                if (page == TimelinePage.MAP) {
                    mapDay = LocalDate.now(timelineZone())
                    onJumpShown()
                }
            null -> Unit
        }
    }

    // The journey context both views draw — which days sit inside a trip, and where. Derived at
    // the tab rather than per view: a view is disposed on switching away and would rebuild the
    // index per switch (the reasoning PlacesTab keeps for its map dots).
    val travels by viewModel.travels.collectAsStateWithLifecycle()
    val awayDays = remember(travels) { awayDaysOf(travels.orEmpty(), timelineZone()) }

    // Each view keeps its state across switches — above all the list's scroll position, which is
    // what makes "only if the map moved" above worth deciding.
    val viewStateHolder = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        ViewSwitchRow(
            labelsRes = timelinePageLabels,
            selectedIndex = page.ordinal,
            modifier = Modifier.padding(horizontal = 16.dp),
            onSelect = { switchTo(TimelinePage.entries[it]) },
        )
        when (page) {
            TimelinePage.LIST -> viewStateHolder.SaveableStateProvider(TimelinePage.LIST) {
                TimelineListPage(
                    items = items,
                    recording = recording,
                    viewModel = viewModel,
                    undo = undo,
                    // One slot, two senders: a request from another screen outranks the map's
                    // parting day — it is the fresher intention, and the map's day is dropped
                    // with it rather than scrolled to a moment later.
                    jump = jump ?: mapDayTarget?.let(TimelineJump::Day),
                    onJumpShown = {
                        mapDayTarget = null
                        onJumpShown()
                    },
                    viewedDay = listViewedDay,
                    onOpen = onOpen,
                    onOpenPlace = onOpenPlace,
                    onOpenJourney = onOpenJourney,
                    onOpenGap = onOpenGap,
                    onReplay = onReplay,
                    onOpenRecording = onOpenRecording,
                    awayDays = awayDays,
                )
            }
            TimelinePage.MAP -> viewStateHolder.SaveableStateProvider(TimelinePage.MAP) {
                TimelineMapPage(
                    items = items,
                    viewModel = viewModel,
                    day = mapDay,
                    away = awayDays[mapDay],
                    onSelectDay = { mapDay = it },
                    onOpenPlace = onOpenPlace,
                    onOpenJourney = onOpenJourney,
                )
            }
        }
    }
}

@Composable
private fun TimelineListPage(
    items: List<TimelineItem>,
    recording: TimelineItem.RecordingItem?,
    viewModel: TrackListViewModel,
    undo: UndoSnackbar,
    jump: TimelineJump?,
    onJumpShown: () -> Unit,
    viewedDay: TimelineViewedDay,
    onOpen: (Long) -> Unit,
    onOpenPlace: (String) -> Unit,
    onOpenJourney: (TravelNaming.Summary) -> Unit,
    onOpenGap: (afterTrackId: Long) -> Unit,
    onReplay: (TrackSummary) -> Unit,
    onOpenRecording: () -> Unit,
    /** Open the map page on this day — a day header's date is the way in from here. */
    /** Which days sit inside a journey — the tab derives it once for both views. */
    awayDays: Map<LocalDate, AwayDay>,
) {
    val context = LocalContext.current

    // The trailing stay closes at the recording's start.
    val groups = remember(items, recording) {
        groupTimelineByDay(recording?.let { listOf(it) + items } ?: items)
    }
    val listState = rememberLazyListState()
    val dayLabelOf = rememberDayLabel()
    val dayAnchors = remember(groups) { headingScrollStops(groups.map { it.date to it.items.size }) }
    // The topmost visible item's group: the last anchor at or above it — the anchors are parallel
    // to [groups], so the span arithmetic stays theirs alone. Run only when the holder is asked,
    // never per scroll frame.
    viewedDay.read = {
        val first = listState.firstVisibleItemIndex
        dayAnchors.indexOfLast { it.itemIndex <= first }.takeIf { it >= 0 }?.let { groups[it].date }
    }
    // The holder outlives this view (it belongs to the tab, which answers for it while the map is
    // showing): a closure left behind would pin the whole day-group derivation of a list no longer
    // on screen.
    DisposableEffect(viewedDay) {
        onDispose { viewedDay.read = { null } }
    }
    // The just-landed-on row's key: its card tints briefly so the eye finds it, then fades.
    // Watched through [snapshotFlow] rather than held as an effect's key, which would read it in
    // this function's own restart scope — the one the lazy list's intervals are built in, so a
    // highlight arriving and again fading would rebuild every day in the history. [collectLatest]
    // so a jump landing mid-fade restarts the fade rather than queueing behind it.
    var highlightKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { highlightKey }.filterNotNull().collectLatest {
            delay(1800.milliseconds)
            highlightKey = null
        }
    }
    // The request pending from elsewhere, answered against the rows as grouped — a landing needs
    // the anchors, so this re-runs when they change and the request is consumed once it has been
    // put on screen.
    val homeScroll = rememberCoroutineScope()
    LaunchedEffect(jump, groups) {
        when (jump) {
            null -> return@LaunchedEffect
            // Consumed as the scroll starts, not as it ends: the animation suspends, and a tab
            // switch cancelling it mid-way would otherwise leave the request pending to replay on
            // the way back, over the reading position it restores. Consuming it restarts this
            // effect, so the scroll runs in a scope of its own. animateScrollToItem jumps most
            // of the way and animates the last stretch, so this reads as a fast return from
            // anywhere in a multi-thousand-row history rather than a long fling.
            TimelineJump.Home -> {
                onJumpShown()
                homeScroll.launch { listState.animateScrollToItem(0) }
                return@LaunchedEffect
            }
            is TimelineJump.Row -> {
                val hit = groups.indices.firstNotNullOfOrNull { g ->
                    groups[g].items.indexOfFirst(jump::matches).takeIf { it >= 0 }?.let { g to it }
                }
                if (hit != null) {
                    val (g, i) = hit
                    val heading = dayAnchors[g].itemIndex
                    // The pinned heading covers the target, and a journey day's runs to several rows.
                    // scrollToItem lays the list out before returning.
                    listState.scrollToItem(heading + 1 + i)
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == heading }
                        ?.let { listState.scrollBy(-it.size.toFloat()) }
                    highlightKey = groups[g].items[i].rowKey()
                } else {
                    // By instant, not by date: the target arrives from a place screen carrying no
                    // zone, and dating it on the device's clock would miss the day a foreign stay
                    // was filed under. Groups descend, so the first whose oldest row is at or
                    // before the target holds it.
                    groups.indexOfFirst { it.items.last().startedAt <= jump.anchorMs }
                        .takeIf { it >= 0 }
                        ?.let { listState.scrollToItem(dayAnchors[it].itemIndex) }
                }
            }
            // A journey's *latest* day, where the block starts as the eye meets it, the rows
            // running newest first. The exact day is looked for before the nearest one because the
            // dates are no longer monotonic: a westward crossing can put an earlier date on a later
            // row (leave Tokyo on the 18th, land in Honolulu on the 17th), so "the first group at
            // or before" can walk past the day that is actually there. It stays as the fallback
            // for a journey whose last day holds no rows at all.
            is TimelineJump.Day -> {
                val date = jump.date
                val group = groups.indexOfFirst { it.date == date }.takeIf { it >= 0 }
                    ?: groups.indexOfFirst { it.date <= date }.takeIf { it >= 0 }
                    ?: groups.lastIndex
                if (group >= 0) listState.scrollToItem(dayAnchors[group].itemIndex)
            }
        }
        onJumpShown()
    }
    // Both interval rows offer the same merge, so they share one handler rather than two copies
    // of the undo wiring. Both undo messages are resolved here: the callbacks below run outside
    // the composition.
    val mergedMessage = stringResource(R.string.timeline_undo_merged)
    val deletedMessage = stringResource(R.string.timeline_undo_deleted)
    val onMerge = { plan: TrackMerge.Plan -> undo.mergeTracks(viewModel, plan, mergedMessage) }
    Box(Modifier.fillMaxSize()) {
        // Above the list, not inside it: dayAnchors counts lazy indices from zero, so a leading
        // item would put the fast scroller and the visit jump one row out for the sweep's
        // duration — and a progress banner that scrolls away is not much of one.
        Column(Modifier.fillMaxSize()) {
            SweepBanner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
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
                    stickyHeading(key = group.headingKey) {
                        val label = dayLabelOf(group.date)
                        DayHeader(
                            label = label,
                            dayTracks = dayTracks,
                            dayItems = dayItems,
                            away = away,
                            onOpenJourney = onOpenJourney,
                        ) {
                            viewModel.importExport.shareTracks(dayTracks.map { it.id }) { intent ->
                                if (intent != null) context.startActivity(intent)
                            }
                        }
                    }
                    itemsIndexed(dayItems, key = { _, item -> item.rowKey() }) { index, item ->
                        val shape = groupedRowShape(index, dayItems.size)
                        val highlighted = item.rowKey() == highlightKey
                        when (item) {
                            is TimelineItem.TrackItem -> TrackRow(
                                track = item.summary,
                                shape = shape,
                                zone = item.clock,
                                endZone = item.endZone,
                                onOpen = { onOpen(item.summary.id) },
                                onDelete = { undo.deleteTrack(viewModel, item.summary.id, deletedMessage) },
                                // DEBUG: long-press replays the track through the Record tab's live view.
                                onReplay = if (BuildConfig.DEV_TOOLS) {
                                    { onReplay(item.summary) }
                                } else {
                                    null
                                },
                            )
                            is TimelineItem.RecordingItem -> RecordingRow(
                                item = item,
                                shape = shape,
                                onClick = onOpenRecording,
                            )
                            is TimelineItem.StayItem -> StayRow(
                                item = item,
                                shape = shape,
                                zone = item.clock,
                                highlighted = highlighted,
                                onMerge = onMerge,
                                onClick = {
                                    item.place?.let { onOpenPlace(it.key) }
                                },
                            )
                            is TimelineItem.GapItem ->
                                GapRow(
                                    item = item,
                                    shape = shape,
                                    highlighted = highlighted,
                                    onMerge = onMerge,
                                    onOpen = { onOpenGap(item.gap.afterTrackId) },
                                )
                        }
                    }
                }
            }
        }
        FastScroller(
            state = listState,
            stops = dayAnchors,
            contentDescription = stringResource(R.string.timeline_scroll_to_day),
            label = { dayLabelOf(it) },
        )
    }
}

/**
 * One day of the timeline on a map: its tracks colored by activity and the places its stays
 * resolved to, in the journey map's vocabulary — same lines, same pins, one day at a time.
 *
 * The lines are loaded per selected day rather than history-wide: a day is a handful of tracks,
 * and the polyline cache makes stepping back to a recent day a cache hit. The load restarts from
 * empty on a day switch so the frame taken for the new day never covers the old day's lines.
 */
@Composable
private fun TimelineMapPage(
    items: List<TimelineItem>,
    viewModel: TrackListViewModel,
    day: LocalDate,
    /** The journey [day] belongs to, if any — the same band and marker the list's header carries,
     *  so a day says where it stands in a trip whichever way it is being read. */
    away: AwayDay?,
    onSelectDay: (LocalDate) -> Unit,
    onOpenPlace: (String) -> Unit,
    /** Open the journey detail — the band over a day inside one is the way in, as on the list. */
    onOpenJourney: (TravelNaming.Summary) -> Unit,
) {
    // Indexed once per emission rather than filtered per day step: filing is time-zone arithmetic
    // per row, and an arrow tap re-asking it of the whole history would pay O(history) for a
    // handful of rows. groupBy welds a crossing's repeated date into one entry, which is right
    // here — the map shows the whole date, however many runs the list cut it into.
    val itemsByDay = remember(items) { items.groupBy { it.filedOn } }
    val dayItems = itemsByDay[day].orEmpty()
    val dayTracks = remember(dayItems) {
        dayItems.filterIsInstance<TimelineItem.TrackItem>().map { it.summary }
    }
    // Keyed on the tracks' (id, endedAt) pairs rather than the list, so a timeline emission that
    // changed no track on this day doesn't restart the load and reset the map to empty mid-fill.
    val trackKeys = remember(dayTracks) { dayTracks.map { it.id to it.endedAt } }
    val lines by produceState(emptyList<JourneyLine>(), trackKeys) {
        value = emptyList()
        viewModel.journeyPolylines.linesFor(dayTracks).collect { value = it }
    }

    val placeSummaries by viewModel.places.collectAsStateWithLifecycle()
    val mapPlaces = rememberStayPlaces(dayItems, placeSummaries)

    Column(Modifier.fillMaxSize()) {
        DaySelector(day, onSelectDay)
        away?.let {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                TravelHeading(it) { onOpenJourney(it.summary) }
                // Band and marker share one line and one scale — this view has no date line for
                // the marker to continue, so it continues the band instead.
                AwayDayMarker(it, MaterialTheme.typography.labelLarge)
            }
        }
        // The same figures the list's day header states, so the two views describe a day alike.
        // Zero-height on an empty day, whose message below says all there is to say.
        DayTotals(
            dayTracks = dayTracks,
            dayItems = dayItems,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        if (dayTracks.isEmpty() && mapPlaces.isEmpty()) {
            // The day, not the history, is empty — a bare basemap would read as a broken map.
            EmptyState(
                stringResource(R.string.timeline_map_empty_day),
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            )
        } else {
            // The same card the journey detail draws its map in — a block of content, not the
            // page's own ground. Card padding keeps the texture-mode map off the back-gesture
            // edge strips.
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                shape = groupedRowShape(0, 1),
            ) {
                MapLibreJourneyMap(
                    lines = lines,
                    places = mapPlaces,
                    frameKey = day,
                    linesComplete = lines.size == dayTracks.size,
                    onOpenPlace = onOpenPlace,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * The map page's day: the arrows step it, the date itself opens a picker. The label is worded
 * exactly as the list's day headers are, so the two pages name a day the same way. The forward
 * arrow stops at today — the days ahead are empty by construction — while the picker is free to
 * jump anywhere, a hand-entered future trip being reachable that way.
 */
@Composable
private fun DaySelector(day: LocalDate, onSelect: (LocalDate) -> Unit) {
    val today = LocalDate.now(timelineZone())
    val dayLabelOf = rememberDayLabel()
    var pickerOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onSelect(day.minusDays(1)) }) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.timeline_map_prev_day),
            )
        }
        TextButton(onClick = { pickerOpen = true }, modifier = Modifier.weight(1f)) {
            Text(
                dayLabelOf(day),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onSelect(day.plusDays(1)) }, enabled = day < today) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.timeline_map_next_day),
            )
        }
    }
    if (pickerOpen) {
        // Composed only while open, so each opening starts the picker at the day being shown.
        LocalDateDialog(
            initial = day,
            confirmLabel = stringResource(R.string.common_ok),
            onConfirm = {
                pickerOpen = false
                onSelect(it)
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * The journey a day belongs to. On the list it sits above the date and is **repeated on every day
 * of that journey**, which is the point there: a sticky header holds one row at a time, so a
 * heading that appeared once at the top of the block would scroll away and leave the days under it
 * unattributed. The map view shows the same heading for its one day, where nothing repeats.
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
private fun TravelHeading(away: AwayDay, onOpen: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        // The band is the journey's presence on this screen, so tapping it opens the journey —
        // same destination as the Insights row, from the other end.
        modifier = Modifier.clickable(onClick = onOpen).padding(bottom = 2.dp),
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

/**
 * Which day of a journey this is — the band carries the journey, this says only where in it the
 * reader stands. [style] is the caller's: the marker continues whatever text it sits beside. The
 * separator stays in code: it is layout between the marker and what it follows, not part of what
 * either says.
 */
@Composable
private fun AwayDayMarker(away: AwayDay, style: TextStyle) {
    Text(
        "· " + stringResource(R.string.timeline_away_day, away.ordinal),
        style = style,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.DayHeader(
    label: String,
    dayTracks: List<TrackSummary>,
    dayItems: List<TimelineItem>,
    away: AwayDay?,
    onOpenJourney: (TravelNaming.Summary) -> Unit,
    onShare: () -> Unit,
) {
    away?.let { TravelHeading(it) { onOpenJourney(it.summary) } }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DayHeading(label)
            // Same size as the date it continues, the tint alone telling the two apart.
            away?.let { AwayDayMarker(it, MaterialTheme.typography.titleSmall) }
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
    DayTotals(dayTracks = dayTracks, dayItems = dayItems)
}

/**
 * A day's totals — per-activity movement, then per-category time — shared by the list's day
 * headers and the map view's day so the two views state a day in the same figures.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayTotals(
    dayTracks: List<TrackSummary>,
    dayItems: List<TimelineItem>,
    modifier: Modifier = Modifier,
) {
    val totals = remember(dayTracks) { activityTotals(dayTracks, System.currentTimeMillis()) }
    val categoryTotals = remember(dayItems) {
        dayCategoryTotals(dayItems, System.currentTimeMillis())
    }
    Column(modifier) {
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
 * the middle of an ocean, an atlas row this Android's tz database has never heard of. Resolved
 * per call rather than captured, so a device that changes zone mid-process moves with it.
 *
 * It is no longer *the* timeline zone: a row is sliced and read in the zone of the place it happened
 * in ([TimelineItem.zone]), which is the only way a stay abroad ends its day when that country's day
 * ended. What survives from when this was one global zone is the invariant behind it — whatever
 * clock a row's bounds were clamped to, every reader of those bounds must ask the same one.
 */
internal fun timelineZone(): ZoneId = ZoneId.systemDefault()

/**
 * An atlas zone id as a [ZoneId], falling back to the device's where it can't be one.
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
    is TimelineItem.RecordingItem -> "recording:$trackId"
    is TimelineItem.StayItem -> "stay:${stay.afterTrackId}:${stay.start}"
    is TimelineItem.GapItem -> "gap:${gap.start}"
}

/**
 * The Timeline's empty state, which also reports a restore or a Google Timeline import for as long
 * as one runs, wherever it was started.
 */
@Composable
internal fun restoringCount(done: Int, total: Int?): String =
    if (total == null) {
        pluralStringResource(R.plurals.timeline_restoring_count, done, done)
    } else {
        pluralStringResource(R.plurals.timeline_restoring_count_of, total, done, total)
    }

@Composable
internal fun googleImportingCount(done: Int, total: Int): String =
    pluralStringResource(R.plurals.timeline_google_importing_count_of, total, done, total)

@Composable
private fun EmptyTracksState(viewModel: TrackListViewModel, onOpenData: () -> Unit) {
    val restoring by viewModel.importExport.restoreProgress.collectAsStateWithLifecycle()
    val importing by viewModel.importExport.googleTimelineImportProgress.collectAsStateWithLifecycle()
    val restore = restoring
    val import = importing
    val progress = restore ?: import
    val headline = when {
        restore != null -> R.string.timeline_restoring
        import != null -> R.string.timeline_google_importing
        else -> R.string.timeline_empty
    }
    val count = when {
        restore != null -> restoringCount(restore.tracksDone, restore.tracksTotal)
        import?.tracksTotal != null ->
            googleImportingCount(import.tracksDone, import.tracksTotal)
        else -> null
    }
    EmptyState(stringResource(headline), Modifier.fillMaxSize().padding(24.dp)) {
        if (progress == null) {
            RestoreOrImportButton(viewModel, onOpenData)
            return@EmptyState
        }
        Spacer(Modifier.height(16.dp))
        count?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
        OperationProgressBar(progress.tracksDone, progress.tracksTotal, Modifier.fillMaxWidth())
    }
}

/** No distance or duration on the track being recorded: the Record tab is where those tick, and
 *  the tap goes there. */
@Composable
internal fun RecordingRow(
    item: TimelineItem.RecordingItem,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    val activityName = stringResource(item.label.labelRes)
    val startedAt = timeText(item.startedAt, item.clock)
    val recording = durationText(item.startedAt, endedAt = null)
    ListRowCard(
        shape = shape,
        icon = activityIcon(item.label),
        disc = activityDiscStyle(item.label),
        iconDescription = activityName,
        title = activityName,
        titleColor = MaterialTheme.colorScheme.onSurface,
        subtitle = AnnotatedString("$startedAt · $recording"),
        onClick = onClick,
    )
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
        val origin = TrackOrigin.fromCode(track.source)
        ListRowCard(
            // Long-press replays the track, which Card's own onClick can't express.
            modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onReplay),
            shape = shape,
            icon = activityIcon(activity),
            disc = activityDiscStyle(activity),
            iconDescription = activityName,
            mark = origin?.mark,
            // What happened leads; when it happened is the metadata line.
            title = trackRowTitle(activityName, track.distanceMeters),
            titleColor = MaterialTheme.colorScheme.onSurface,
            // Each end on its own clock where they differ — a track is the one recorded row that can
            // cross a border, and flattening it onto the departure's clock hides the crossing. The two
            // times then no longer span the duration beside them, which is why each carries its own
            // offset: read together they say the drive lost an hour to the border, which is the truth.
            subtitle = trackRowSpan(
                track.startedAt,
                zone,
                track.endedAt,
                endZone ?: zone,
                durationText(track.startedAt, track.endedAt),
            ),
        )
    }
}

/** A track row's title: what moved, and how far where that is known. */
@Composable
internal fun trackRowTitle(activityName: String, lengthM: Double?): String =
    if (lengthM == null) activityName else "$activityName · " + distanceText(lengthM)

/**
 * A track row's time line: its two clock times, each on its own end's clock, and how long it ran.
 * A track still recording has no end time.
 */
@Composable
internal fun trackRowSpan(startMs: Long, startZone: ZoneId, endMs: Long?, endZone: ZoneId, length: String?): AnnotatedString {
    // Resolved here: nothing composable may be read inside the builder (see appendTime).
    val reader = timelineZone()
    val shiftColor = zoneShiftColor
    val readerClock = LocalReaderClock.current
    return buildAnnotatedString {
        appendTime(startMs, startZone, reader, shiftColor, readerClock)
        endMs?.let {
            append(" – ")
            appendTime(it, endZone, reader, shiftColor, readerClock)
        }
        length?.let { append(" · $it") }
    }
}

/**
 * A history sweep, while it runs: distances and end times shift behind it as each track is
 * re-derived, so it says so instead of the list quietly rearranging itself. Determinate — a sweep
 * counts its work before starting it — and it removes itself when the sweep ends.
 *
 * It reads the sweep itself, and that is the point of it being a composable rather than a banner
 * the page hands progress to: a sweep emits a few times a second, and the page's own scope holds
 * the lazy list's content lambda, so a read up there re-runs the day groups over the whole history
 * once per tick. Here the emission restarts this and nothing else.
 */
@Composable
private fun SweepBanner(modifier: Modifier = Modifier) {
    val progress = SweepStatus.state.collectAsStateWithLifecycle().value ?: return
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
            OperationProgressBar(progress.done, progress.total, Modifier.fillMaxWidth())
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
    val named = place?.isNamed == true
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
        highlighted = highlighted,
        icon = category.discIcon,
        disc = disc,
        iconDescription = category?.let { stringResource(it.labelRes) } ?: stringResource(R.string.place_stay),
        badge = if (mergeable) Icons.Filled.Pause else null,
        badgeDescription = if (mergeable) stringResource(R.string.timeline_short_stop_mergeable) else null,
        mark = place?.place?.mark,
        // The place leads; when (with midnight slices phrased humanly) is the metadata line. The
        // atlas's city stands in where the user has said nothing, dimmed by `named` below so a
        // worked-out name never reads as one they chose. A merge-eligible stop the atlas can't
        // reach names the situation instead.
        title = place?.name ?: stringResource(
            if (mergeable) R.string.timeline_short_stop else R.string.timeline_stayed,
        ),
        titleColor = statedTitleColor(named),
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

@Composable
private fun GapRow(
    item: TimelineItem.GapItem,
    shape: RoundedCornerShape,
    highlighted: Boolean,
    onMerge: (TrackMerge.Plan) -> Unit,
    onOpen: () -> Unit,
) {
    // A gap short enough to be one outing the recorder split swipes away exactly as a short stop
    // does — the leg it missed survives as the merged track's segment break. Longer gaps are real
    // absences and aren't swipeable.
    MergeSwipeable(item.merge, shape, onMerge) {
        GapCard(item, shape, mergeable = item.merge != null, highlighted = highlighted, onClick = onOpen)
    }
}

/**
 * Movement the recorder missed: neighboring track endpoints disagree. Deliberately subdued, and one
 * tap target as a whole: the gap's page holds its places and every way to fill it. Two pins joined
 * by a dashed connector in the icon column draw the unrecorded leg as a map would. On the
 * newest-first timeline the destination sits above (adjacent to the later track) and the source
 * below; [chronological] turns that round for a list of gaps on their own.
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
 */
@Composable
internal fun GapCard(
    item: TimelineItem.GapItem,
    shape: RoundedCornerShape,
    /** Whether the host offers this gap's merge, which the connector's accent advertises. */
    mergeable: Boolean = false,
    highlighted: Boolean = false,
    chronological: Boolean = false,
    onClick: () -> Unit,
) {
    val gap = item.gap
    // The whole card is drawn from the two questions the slicer answered when it cut — see
    // GapItem.holdsDeparture. Read once here, never re-derived: an absence inside one day is one row
    // holding both ends, anything longer is two, each saying nothing about the end sitting under the
    // other's day heading.
    val holdsDeparture = item.holdsDeparture
    val holdsArrival = item.holdsArrival
    val arrival = if (holdsArrival) item.toPlace else null
    val departure = if (holdsDeparture) item.fromPlace else null
    val reader = timelineZone()
    val shiftColor = zoneShiftColor
    val readerClock = LocalReaderClock.current
    val bounds = gapBounds(gap, holdsDeparture, holdsArrival, AndroidDistance)

    // The slicer sets an end's clock exactly where the row holds that end, which is where these run.
    fun departureAt(atMs: Long) =
        markedTime(atMs, checkNotNull(item.departureZone), reader, shiftColor, readerClock)
    fun arrivalAt(atMs: Long) = markedTime(atMs, checkNotNull(item.arrivalZone), reader, shiftColor, readerClock)
    // A half states its one time on the line, worded as which way the absence runs on past the
    // day's edge; a row holding both ends puts each time beside its place.
    val line = when (bounds) {
        is GapBounds.From -> annotatedStringResource(R.string.timeline_gap_from, departureAt(bounds.start))
        is GapBounds.Until -> annotatedStringResource(R.string.timeline_gap_until, arrivalAt(bounds.end))
        is GapBounds.Both -> AnnotatedString(
            listOfNotNull(
                bounds.lastingMs?.let { durationText(it) },
                bounds.apartMeters?.let { fineDistanceText(it) },
            ).joinToString(" · "),
        )
    }
    val both = bounds as? GapBounds.Both
    MissingRecordingCard(shape, onClick, highlighted) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            val departureLine = @Composable { GapPlaceLine(departure, both?.let { departureAt(it.start) }) }
            val arrivalLine = @Composable { GapPlaceLine(arrival, both?.let { arrivalAt(it.end) }) }
            if (chronological) departureLine() else arrivalLine()
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GapConnector(mergeable)
                Spacer(Modifier.width(16.dp))
                Text(
                    modifier = Modifier.weight(1f),
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            if (chronological) arrivalLine() else departureLine()
        }
    }
}

@Composable
private fun GapConnector(mergeable: Boolean) {
    // Tertiary marks a mergeable interval throughout the timeline — the same accent the short-stop
    // row wears, so the swipe is discoverable in both places.
    val strokeColor = if (mergeable) {
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
}

/**
 * One side of a gap: category disc and place name. An unknown side renders nothing, its time
 * included; its position tells the story.
 *
 * [at] trails the name rather than leading it: which two places the absence lies between is what the
 * row is for, and the times qualify them.
 */
@Composable
private fun GapPlaceLine(
    place: PlaceResolver.ResolvedStay?,
    at: AnnotatedString?,
) {
    if (place == null) return
    val category = place.category
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A floor rather than more padding, which would loosen the connector this row sits against.
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
        // A long name truncates here, before the time and the origin glyph.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = place.name ?: stringResource(R.string.timeline_unnamed_place),
                style = MaterialTheme.typography.titleMedium,
                color = statedTitleColor(stated = place.isNamed),
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
        place.place?.mark?.let {
            Spacer(Modifier.width(8.dp))
            OriginGlyph(it)
        }
    }
}

@Composable
@ReadOnlyComposable
private fun visitCountLabel(n: Int): String = pluralStringResource(R.plurals.timeline_visits, n, n)
