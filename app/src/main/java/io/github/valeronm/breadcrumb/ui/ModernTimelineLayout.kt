package io.github.valeronm.breadcrumb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.data.Settings.TimelineColorPalette
import io.github.valeronm.breadcrumb.data.TrackPoints
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.PlaceOverlap
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.RoutePlaces
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.TrackSettle
import io.github.valeronm.breadcrumb.domain.TravelNaming
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

/** Resolve the row before choosing a day: a foreign visit need not share the device's date. */
internal fun timelineJumpDay(jump: TimelineJump.Row, items: List<TimelineItem>): LocalDate =
    items.firstOrNull(jump::matches)?.filedOn
        ?: groupTimelineByDay(items).firstOrNull { it.items.last().startedAt <= jump.anchorMs }?.date
        ?: java.time.Instant.ofEpochMilli(jump.anchorMs).atZone(timelineZone()).toLocalDate()

internal enum class TimelineSheetAnchor { Hidden, PartiallyExpanded, Expanded }

private sealed interface ModernDetailTarget {
    data class Track(val id: Long) : ModernDetailTarget
    data class Place(val key: String) : ModernDetailTarget
}

internal fun <T> pushModernTimelineDetail(stack: List<T>, target: T): List<T> = stack + target

internal fun <T> popModernTimelineDetail(stack: List<T>): List<T> =
    if (stack.isEmpty()) stack else stack.dropLast(1)

internal fun <T> resetModernTimelineDetails(): List<T> = emptyList()

/** Keep map framing fixed throughout a drag and its settling animation. */
internal fun settledMapBottomPadding(
    state: AnchoredDraggableState<TimelineSheetAnchor>,
    fullHeightPx: Float,
    fallbackOffsetPx: Float,
): Int {
    val offset = state.anchors.positionOf(state.settledValue).takeUnless { it.isNaN() } ?: fallbackOffsetPx
    return (fullHeightPx - offset).roundToInt().coerceAtLeast(0)
}

@Composable
internal fun ModernTimelineLayout(
    items: List<TimelineItem>,
    recording: TimelineItem.RecordingItem?,
    viewModel: TrackListViewModel,
    undo: UndoSnackbar,
    jump: TimelineJump?,
    onJumpShown: () -> Unit,
    viewedDay: TimelineViewedDay,
    listViewedDay: TimelineViewedDay,
    activityTypesByTrackId: Map<Long, ActivityType?>,
    colorPalette: TimelineColorPalette,
    displayOptions: AppSettings.TimelineDisplayOptions,
    awayDays: Map<LocalDate, AwayDay>,
    onOpenJourney: (TravelNaming.Summary) -> Unit,
    onAddTrip: (TripDraft) -> Unit,
    onReplay: (TrackSummary) -> Unit,
    onOpenRecording: () -> Unit,
    onAdjustPlace: (PlaceResolver.PlaceSummary) -> Unit,
    onRemovePlace: (Place) -> Unit,
    onMergePlace: (PlaceResolver.PlaceSummary) -> Unit,
    onVisit: (StayDeriver.Stay) -> Unit,
    onGap: (PlaceResolver.PlaceGap) -> Unit,
    onSplitTrack: (Long, Long) -> Unit,
) {
    var mapDay by rememberSaveable { mutableStateOf(LocalDate.now(timelineZone())) }
    var detailStack by remember { mutableStateOf(emptyList<ModernDetailTarget>()) }
    var selectedGraphPoint by remember { mutableStateOf<TrackPoint?>(null) }
    val listState = rememberLazyListState()
    var sheetExpansionAllowed by remember { mutableStateOf(false) }
    var sheetCollapseAllowed by remember { mutableStateOf(false) }
    viewedDay.read = { mapDay }
    val rowJumpDay = remember(jump, items) { (jump as? TimelineJump.Row)?.let { timelineJumpDay(it, items) } }
    LaunchedEffect(jump, rowJumpDay) {
        when (jump) {
            is TimelineJump.Row -> {
                detailStack = resetModernTimelineDetails()
                mapDay = checkNotNull(rowJumpDay)
            }
            is TimelineJump.Day -> {
                detailStack = resetModernTimelineDetails()
                mapDay = jump.date
            }
            TimelineJump.Home -> {
                detailStack = resetModernTimelineDetails()
                mapDay = LocalDate.now(timelineZone())
            }
            null -> Unit
        }
    }

    LaunchedEffect(mapDay) {
        detailStack = resetModernTimelineDetails()
        selectedGraphPoint = null
        listState.scrollToItem(0)
    }
    val dayItems = remember(items, mapDay) { items.filter { it.filedOn == mapDay } }
    val dayRecording = remember(recording, mapDay) { recording?.takeIf { it.filedOn == mapDay } }
    val context = LocalContext.current
    val dayTracks = remember(dayItems) { dayItems.filterIsInstance<TimelineItem.TrackItem>().map { it.summary } }
    val dayTrackKeys = remember(dayTracks) { dayTracks.map { it.id to it.endedAt } }
    val dayContextLines by produceState(emptyList<JourneyLine>(), dayTrackKeys) {
        value = emptyList()
        viewModel.journeyPolylines.linesFor(dayTracks).collect { value = it }
    }
    val sheetScope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val topInsetPx = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
        val peekHeightPx = fullHeightPx * 0.5f
        val flingThresholdPx = with(LocalDensity.current) { 125.dp.toPx() }
        val sheetState = remember { AnchoredDraggableState(TimelineSheetAnchor.PartiallyExpanded) }
        val flingBehavior = AnchoredDraggableDefaults.flingBehavior(sheetState)
        var listAnchorBeforeDetails by remember { mutableStateOf(TimelineSheetAnchor.PartiallyExpanded) }
        val closeDetail = {
            if (detailStack.isNotEmpty()) {
                val returningToList = detailStack.size == 1
                detailStack = popModernTimelineDetail(detailStack)
                if (returningToList) {
                    sheetScope.launch { sheetState.animateTo(listAnchorBeforeDetails) }
                }
            }
        }
        BackHandler(enabled = detailStack.isNotEmpty(), onBack = closeDetail)
        val openTrackDetail: (Long) -> Unit = { id ->
            if (detailStack.isEmpty()) listAnchorBeforeDetails = sheetState.settledValue
            detailStack = pushModernTimelineDetail(detailStack, ModernDetailTarget.Track(id))
            selectedGraphPoint = null
            sheetScope.launch { sheetState.animateTo(TimelineSheetAnchor.PartiallyExpanded) }
        }
        val openPlaceDetail: (String) -> Unit = { key ->
            if (detailStack.isEmpty()) listAnchorBeforeDetails = sheetState.settledValue
            detailStack = pushModernTimelineDetail(detailStack, ModernDetailTarget.Place(key))
            selectedGraphPoint = null
            sheetScope.launch { sheetState.animateTo(TimelineSheetAnchor.PartiallyExpanded) }
        }
        val selectedTarget = detailStack.lastOrNull()
        val selectedTrackId = (selectedTarget as? ModernDetailTarget.Track)?.id
        val selectedTrackSummary = remember(dayTracks, selectedTrackId) {
            dayTracks.firstOrNull { it.id == selectedTrackId }
        }
        LaunchedEffect(dayTracks, selectedTrackId) {
            if (selectedTrackId != null && selectedTrackSummary == null) {
                detailStack = resetModernTimelineDetails()
                selectedGraphPoint = null
            }
        }
        val selectedTrackPoints by produceState<TrackPoints?>(null, selectedTrackId, selectedTrackSummary) {
            value = selectedTrackId?.let { viewModel.getTrackPoints(it) }
        }
        val storedPlaces by viewModel.storedPlaces.collectAsStateWithLifecycle()
        val routeEndPlaces = remember(
            selectedTrackPoints,
            storedPlaces,
            selectedTrackSummary?.startPlaceId,
            selectedTrackSummary?.endPlaceId,
        ) {
            RoutePlaces.ends(
                selectedTrackPoints?.good.orEmpty(),
                storedPlaces,
                AndroidDistance,
                startPlaceId = selectedTrackSummary?.startPlaceId,
                endPlaceId = selectedTrackSummary?.endPlaceId,
            )
        }
        val routeDwells by produceState(emptyList<DwellDetector.Dwell>(), selectedTrackPoints?.good) {
            value = selectedTrackPoints?.good?.let { points ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    DwellDetector.detect(points, DwellDetector.TRACK_OVERLAY, AndroidDistance)
                }
            }.orEmpty()
        }
        val routeOverruns = remember(selectedTrackPoints) {
            TrackSettle.overruns(selectedTrackPoints?.good.orEmpty(), selectedTrackPoints?.edgeStay.orEmpty())
        }
        val placeSummaries by viewModel.places.collectAsStateWithLifecycle()
        val pendingPlace by viewModel.pendingPlaceRow.collectAsStateWithLifecycle()
        val selectedPlaceKey = (selectedTarget as? ModernDetailTarget.Place)?.key
        val selectedPlaceSummary = rememberPlaceSummary(placeSummaries, selectedPlaceKey, null, pendingPlace)
        val selectedColorMode = remember(selectedTrackId) { mutableStateOf(ColorMode.SPEED) }
        val sharedTrackColoring = remember(selectedTrackId) { mutableStateOf<TrackColoring?>(null) }
        val sharedTrackSeams = remember(selectedTrackId) { mutableStateOf<TrackQuality.Seams?>(null) }
        var showDayContext by remember(selectedTrackId) { mutableStateOf(false) }
        var showNoisyOverride by remember(selectedTrackId) { mutableStateOf<Boolean?>(null) }
        val showNoisy = showNoisyTrackPoints(selectedTrackPoints?.good?.size, showNoisyOverride)
        val settledBottomPadding = settledMapBottomPadding(sheetState, fullHeightPx, peekHeightPx)
        LaunchedEffect(fullHeightPx, topInsetPx) {
            sheetState.updateAnchors(
                DraggableAnchors {
                    TimelineSheetAnchor.Expanded at topInsetPx
                    TimelineSheetAnchor.PartiallyExpanded at peekHeightPx
                    TimelineSheetAnchor.Hidden at fullHeightPx
                },
            )
        }
        val nestedScroll = remember(sheetState, flingThresholdPx) {
            object : NestedScrollConnection {
                private var sheetDragged = false

                override fun onPreScroll(available: Offset, source: NestedScrollSource) = Offset.Zero

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                    if (available.y < 0f && !sheetExpansionAllowed) return Offset.Zero
                    if (available.y > 0f && !sheetCollapseAllowed) return Offset.Zero
                    val delta = sheetState.dispatchRawDelta(available.y)
                    if (delta != 0f) sheetDragged = true
                    return Offset(0f, delta)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (!sheetDragged) return Velocity.Zero
                    sheetDragged = false
                    val offset = sheetState.offset
                    val anchors = sheetState.anchors
                    val canFling = if (available.y < 0f) sheetExpansionAllowed else sheetCollapseAllowed
                    val target = if (canFling && abs(available.y) >= flingThresholdPx) {
                        val movingUp = available.y < 0f
                        (0 until anchors.size)
                            .mapNotNull(anchors::anchorAt)
                            .filter { anchor ->
                                val position = anchors.positionOf(anchor)
                                if (movingUp) position < offset else position > offset
                            }
                            .minByOrNull { abs(anchors.positionOf(it) - offset) }
                    } else {
                        null
                    } ?: anchors.closestAnchor(offset)
                    target?.let { sheetState.animateTo(it, spring(stiffness = Spring.StiffnessMediumLow)) }
                    return available
                }
            }
        }
        val sheetOffset = sheetState.offset.takeUnless { it.isNaN() } ?: peekHeightPx
        val sheetColors = MaterialTheme.colorScheme
        val sheetContainerColor = lerp(
            sheetColors.surfaceContainerLow,
            sheetColors.surfaceContainerLowest,
            0.35f,
        )
        val visibleHeight = with(LocalDensity.current) {
            (fullHeightPx - sheetOffset).coerceAtLeast(1f).toDp()
        }
        val sheetHidden = sheetState.currentValue == TimelineSheetAnchor.Hidden
        Box(Modifier.fillMaxSize()) {
            val modernMapState = when (selectedTarget) {
                null -> null
                is ModernDetailTarget.Track -> {
                    val summary = selectedTrackSummary
                    val loaded = selectedTrackPoints
                    if (summary == null || loaded == null) {
                        ModernTimelineMapState.Loading(settledBottomPadding)
                    } else {
                        val activity = ActivityType.ofName(summary.activityType)
                        val source = TrackOrigin.fromCode(summary.source)
                        val modes = remember(loaded.good, source) { availableColorModes(loaded.good, source) }
                        LaunchedEffect(selectedTrackId, modes) {
                            if (selectedColorMode.value !in modes && modes.isNotEmpty()) {
                                selectedColorMode.value = modes.first()
                            }
                        }
                        ModernTimelineMapState.Track(
                            id = summary.id,
                            points = loaded.good,
                            noisyPoints = loaded.noisy,
                            showNoisy = showNoisy,
                            activity = activity,
                            colorMode = selectedColorMode.value,
                            selectedPoint = selectedGraphPoint,
                            dwells = routeDwells,
                            overruns = routeOverruns,
                            endPlaces = routeEndPlaces,
                            coloring = sharedTrackColoring.value,
                            seams = sharedTrackSeams.value,
                            contextLines = dayContextLines.filter { it.trackId != summary.id },
                            showContextLines = showDayContext,
                            showLegend = modes.isNotEmpty(),
                            legendBottomPadding = visibleHeight + 20.dp,
                            bottomPaddingPx = settledBottomPadding,
                        )
                    }
                }
                is ModernDetailTarget.Place -> {
                    selectedPlaceSummary?.let { summary ->
                        ModernTimelineMapState.PlaceTarget(
                            key = selectedTarget.key,
                            marker = PlaceMarker(summary.anchor, summary.place),
                            radiusM = summary.radiusM,
                            bottomPaddingPx = settledBottomPadding,
                        )
                    } ?: ModernTimelineMapState.Loading(settledBottomPadding)
                }
            }
            TimelineMapPage(
                items = items,
                viewModel = viewModel,
                day = mapDay,
                away = awayDays[mapDay],
                onSelectDay = { mapDay = it },
                onOpenPlace = openPlaceDetail,
                onOpenJourney = onOpenJourney,
                colorPalette = colorPalette,
                displayOptions = displayOptions,
                backgroundOnly = true,
                mapBottomPaddingPx = settledBottomPadding,
                modernDetailState = modernMapState,
            )
            if (selectedTrackSummary != null && selectedTarget is ModernDetailTarget.Track) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.timeline_other_day_routes), style = MaterialTheme.typography.labelLarge)
                        Switch(checked = showDayContext, onCheckedChange = { showDayContext = it })
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(visibleHeight)
                    .offset { IntOffset(0, sheetOffset.roundToInt()) }
                    .pointerInput(listState) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            sheetExpansionAllowed = !listState.canScrollForward
                            sheetCollapseAllowed = !listState.canScrollBackward
                            waitForUpOrCancellation(pass = PointerEventPass.Final)
                        }
                    }
                    .then(if (selectedTarget == null) Modifier.nestedScroll(nestedScroll) else Modifier),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = sheetContainerColor,
                contentColor = sheetColors.onSurface,
                tonalElevation = 0.dp,
            ) {
                MaterialTheme(
                    colorScheme = sheetColors.copy(
                        background = sheetContainerColor,
                        surfaceContainerHighest = sheetContainerColor,
                    ),
                ) {
                    Column(Modifier.fillMaxSize().background(sheetContainerColor)) {
                        Box(
                            Modifier.fillMaxWidth().height(if (selectedTarget == null) 28.dp else 16.dp).anchoredDraggable(
                                state = sheetState,
                                orientation = Orientation.Vertical,
                                flingBehavior = flingBehavior,
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.width(32.dp).height(4.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)),
                            )
                        }
                        if (selectedTarget == null) {
                            DaySelector(
                                mapDay,
                                onSelect = { mapDay = it },
                                onAddTrip = { onAddTrip(TripDraft(day = mapDay)) },
                                onShare = {
                                    viewModel.importExport.shareTracks(dayTracks.map { it.id }) { intent ->
                                        if (intent != null) context.startActivity(intent)
                                    }
                                },
                                canShare = dayTracks.isNotEmpty(),
                            )
                        }
                        if (selectedTarget != null) {
                            when (selectedTarget) {
                                is ModernDetailTarget.Track -> {
                                    val summary = selectedTrackSummary
                                    if (summary == null) {
                                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                                    } else {
                                        TrackDetailScreen(
                                            trackId = selectedTarget.id,
                                            summary = summary,
                                            viewModel = viewModel,
                                            onBack = closeDetail,
                                            onSplit = { at ->
                                                onSplitTrack(selectedTarget.id, at)
                                                closeDetail()
                                            },
                                            onEditTrip = onAddTrip,
                                            embeddedPanel = true,
                                            externalTrackPoints = selectedTrackPoints,
                                            useExternalTrackPoints = true,
                                            externalShowNoisy = showNoisy,
                                            onShowNoisyChanged = { showNoisyOverride = it },
                                            onSelectedPoint = { selectedGraphPoint = it },
                                            externalColorMode = selectedColorMode.value,
                                            onColorModeChanged = { selectedColorMode.value = it },
                                            onTrackColoringChanged = { coloring, seams ->
                                                sharedTrackColoring.value = coloring
                                                sharedTrackSeams.value = seams
                                            },
                                            headerDragModifier = Modifier.anchoredDraggable(
                                                state = sheetState,
                                                orientation = Orientation.Vertical,
                                                flingBehavior = flingBehavior,
                                            ),
                                        )
                                    }
                                }
                                is ModernDetailTarget.Place -> {
                                    val place = selectedPlaceSummary
                                    if (place == null) {
                                        LaunchedEffect(selectedTarget.key, placeSummaries) {
                                            if (placeSummaries != null) closeDetail()
                                        }
                                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                                    } else {
                                        val canMerge = remember(place.place, placeSummaries) {
                                            val row = place.place ?: return@remember false
                                            PlaceOverlap.candidatesFor(
                                                row,
                                                placeSummaries.orEmpty().mapNotNull { it.place },
                                                AndroidDistance,
                                            ).isNotEmpty()
                                        }
                                        PlaceDetailScreen(
                                            summary = place,
                                            viewModel = viewModel,
                                            onBack = closeDetail,
                                            onOpenVisit = onVisit,
                                            onOpenGap = onGap,
                                            onOpenPlace = openPlaceDetail,
                                            onAdjustArea = { onAdjustPlace(place) },
                                            onRemove = onRemovePlace,
                                            canMerge = canMerge,
                                            onOpenMerge = { onMergePlace(place) },
                                            embeddedPanel = true,
                                            headerDragModifier = Modifier.anchoredDraggable(
                                                state = sheetState,
                                                orientation = Orientation.Vertical,
                                                flingBehavior = flingBehavior,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        if (selectedTarget == null) {
                            awayDays[mapDay]?.let { away ->
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TravelHeading(away) { onOpenJourney(away.summary) }
                                    AwayDayMarker(away, MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (selectedTarget == null) {
                            DayTotals(
                                dayTracks = dayTracks,
                                dayItems = dayItems,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                show = displayOptions.showDaySummary,
                                colorPalette = colorPalette,
                            )
                        }
                        if (selectedTarget == null && dayItems.isEmpty() && dayRecording == null) {
                            EmptyState(
                                stringResource(R.string.timeline_map_empty_day),
                                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                            )
                        } else if (selectedTarget == null) {
                            TimelineListPage(
                                items = dayItems,
                                recording = dayRecording,
                                viewModel = viewModel,
                                undo = undo,
                                // Wait for the requested day before its list can consume the jump.
                                jump = jump.takeUnless { it is TimelineJump.Row && mapDay != rowJumpDay },
                                onJumpShown = onJumpShown,
                                viewedDay = listViewedDay,
                                onOpen = openTrackDetail,
                                onOpenPlace = openPlaceDetail,
                                onOpenJourney = onOpenJourney,
                                onAddTrip = onAddTrip,
                                onReplay = onReplay,
                                onOpenRecording = onOpenRecording,
                                awayDays = awayDays,
                                displayOptions = displayOptions,
                                activityTypesByTrackId = activityTypesByTrackId,
                                colorPalette = colorPalette,
                                listStateOverride = listState,
                                singleDayMode = true,
                            )
                        }
                    }
                }
            }
            if (sheetHidden) {
                FloatingActionButton(
                    onClick = {
                        sheetScope.launch { sheetState.animateTo(TimelineSheetAnchor.PartiallyExpanded) }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Icon(Icons.Filled.ExpandLess, contentDescription = stringResource(R.string.timeline_show_day))
                }
            }
        }
    }
}
