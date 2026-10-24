package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.OnlineRouting
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.domain.GapDiagnosis
import io.github.valeronm.breadcrumb.domain.ManualPath
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.Speed
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.TripPlaces
import kotlinx.coroutines.launch
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GapDetailScreen(
    afterTrackId: Long,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onAddTrip: (TripDraft) -> Unit,
    onStayedHere: (beforeId: Long, afterId: Long, side: PlaceResolver.ResolvedStay) -> Unit,
    onMerge: (TrackMerge.Plan) -> Unit,
    onShowOnTimeline: (StayDeriver.Gap) -> Unit,
    onOpenPlace: (String) -> Unit,
) {
    val flow = remember(afterTrackId) { viewModel.gapDetail(afterTrackId) }
    val state by flow.collectAsStateWithLifecycle(initialValue = null)
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(state == GapState.Gone) {
        if (state == GapState.Gone) back()
    }
    val d = state as? GapDetail
    var asking by remember(afterTrackId) { mutableStateOf<StayedHereChoice.Ask?>(null) }
    var proposal by remember(afterTrackId) { mutableStateOf<RoadProposal?>(null) }
    var fetching by remember(afterTrackId) { mutableStateOf<GapDiagnosis.Fix?>(null) }
    var saving by remember(afterTrackId) { mutableStateOf(false) }
    val context = LocalContext.current
    val routingOn = remember { AppSettings.isOnlineRouting(context) }
    val scope = rememberCoroutineScope()
    // Resolved here: the save callback runs outside the composition.
    val overlapsMessage = stringResource(R.string.addtrip_overlaps)

    // The tap is the ask that sends the two ends to the router.
    fun propose(fix: GapDiagnosis.Fix, draft: TripDraft, notBeforeMs: Long) {
        val profile = draft.activity?.let(OnlineRouting.Profile::of)?.takeIf { routingOn } ?: return onAddTrip(draft)
        val from = draft.origin?.at ?: return onAddTrip(draft)
        val to = draft.destination?.at ?: return onAddTrip(draft)
        fetching = fix
        scope.launch {
            val route = viewModel.routeOnline(profile, from, to)
            fetching = null
            val whole = route?.let { GapDrafts.withRoad(draft, it, notBeforeMs) }
            if (whole == null) onAddTrip(draft) else proposal = whole
        }
    }

    fun save(proposed: RoadProposal) {
        saving = true
        // Saved, the gap is gone and the page closes on its own.
        viewModel.saveManualTrack(null, proposed.trip) { result ->
            saving = false
            if (result !is TrackRepository.ManualTrackResult.Saved) viewModel.say(overlapsMessage)
        }
    }

    fun onFix(chosen: GapDiagnosis.Fix) {
        val detail = d ?: return
        if (fetching != null) return
        when (val action = gapAction(chosen, detail)) {
            is GapAction.AddTrip -> onAddTrip(action.draft)
            is GapAction.ProposeStart -> propose(chosen, action.draft, detail.gap.start)
            is GapAction.Merge -> onMerge(action.plan)
            is StayedHereChoice.Direct -> onStayedHere(detail.before.id, detail.after.id, action.side)
            is StayedHereChoice.Ask -> asking = action
        }
    }

    val density = LocalDensity.current
    val cover = remember { MapCover() }
    var handlePx by remember { mutableIntStateOf(0) }
    var peekPx by remember { mutableIntStateOf(0) }
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val peekHeight = if (peekPx == 0) {
        BottomSheetDefaults.SheetPeekHeight
    } else {
        with(density) { (handlePx + peekPx).toDp() } + navigationBar
    }
    // The first fit can land before the peek is measured, which frames the gap under the sheet.
    var frameKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(peekPx != 0) { if (peekPx != 0) frameKey++ }
    LaunchedEffect(proposal) { if (proposal != null) frameKey++ }

    BottomSheetScaffold(
        scaffoldState = rememberBottomSheetScaffoldState(
            rememberBottomSheetState(
                initialValue = SheetValue.PartiallyExpanded,
                enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded),
            ),
        ),
        containerColor = MaterialTheme.colorScheme.background,
        sheetPeekHeight = peekHeight,
        sheetDragHandle = {
            BottomSheetDefaults.DragHandle(
                Modifier
                    .onSizeChanged { handlePx = it.height }
                    .onGloballyPositioned { cover.sheetTop = it.boundsInWindow().top },
            )
        },
        sheetContent = {
            val detail = d ?: return@BottomSheetScaffold
            val verdict = detail.verdict
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Column(Modifier.onSizeChanged { peekPx = it.height }) {
                    val proposed = proposal
                    if (proposed == null) {
                        GapFacts(detail, onOpenPlace)
                        GapSuggestion(verdict, busy = fetching == verdict.suggested, ::onFix)
                    } else {
                        GapProposal(
                            proposed,
                            detail,
                            enabled = !saving,
                            onOpenPlace = onOpenPlace,
                            onCancel = { proposal = null },
                            onEdit = { onAddTrip(proposed.draft) },
                            onSave = { save(proposed) },
                        )
                    }
                }
                // A proposal is one decision on screen; Cancel brings the other ways back.
                if (proposal == null && verdict.others.isNotEmpty()) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        SectionHeading(stringResource(R.string.gap_other_fixes))
                    }
                    verdict.others.forEach { other ->
                        ListItem(
                            modifier = Modifier.clickable { onFix(other) },
                            trailingContent = if (fetching == other) {
                                { CircularProgressIndicator(Modifier.size(24.dp)) }
                            } else {
                                null
                            },
                        ) {
                            Text(stringResource(other.labelRes()))
                        }
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(stringResource(R.string.gap_title)) },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (d != null) {
                        IconButton(onClick = { onShowOnTimeline(d.gap) }) {
                            Icon(Icons.Filled.Route, contentDescription = stringResource(R.string.gap_show_on_timeline))
                        }
                    }
                },
            )
        },
    ) { inner ->
        val detail = d ?: return@BottomSheetScaffold
        // Full height under the sheet rather than padded above it: the peek is measured, and a map
        // resized once it is would stretch its last frame.
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding())
                .onGloballyPositioned {
                    val bounds = it.boundsInWindow()
                    cover.mapTop = bounds.top
                    cover.mapBottom = bounds.bottom
                },
        ) {
            GapMap(detail, proposal, viewModel, frameKey, cover::insets, onReframe = { frameKey++ })
        }
    }

    val choice = asking
    if (d != null && choice != null) {
        StayedHereSheet(
            choice = choice,
            onPick = { side ->
                asking = null
                onStayedHere(d.before.id, d.after.id, side)
            },
            onDismiss = { asking = null },
        )
    }
}

@Composable
private fun GapMap(
    detail: GapDetail,
    proposal: RoadProposal?,
    viewModel: TrackListViewModel,
    frameKey: Int,
    obscured: () -> MapInsets,
    onReframe: () -> Unit,
) {
    val beforeId = detail.before.id
    val afterId = detail.after.id
    val touching by produceState(emptyList<TouchingTrack>(), beforeId, afterId) {
        value = viewModel.touchingTracks(beforeId, afterId).toList().filterNotNull()
    }
    val continuing = if (proposal == null) {
        setOf(ManualPath.End.ORIGIN, ManualPath.End.DESTINATION)
    } else {
        val trip = proposal.trip
        ManualPath.continuing(trip.origin.end.timestampMs, trip.destination.end.timestampMs, detail.gap.start, detail.gap.end)
    }
    val storedPlaces by viewModel.storedPlaces.collectAsStateWithLifecycle()
    // Without a proposal both ends carry on a track and are drawn as points, which wear no place.
    val ends = if (proposal == null) {
        listOf(TripPlaces.Outcome.None, TripPlaces.Outcome.None)
    } else {
        val fromEnd = TripPlaces.End(detail.gap.from?.at, detail.fromPlace?.label)
        val toEnd = TripPlaces.End(detail.gap.to?.at, detail.toPlace?.label)
        remember(storedPlaces, fromEnd.at, fromEnd.name, toEnd.at, toEnd.name) {
            previewTripPlaces(listOf(fromEnd, toEnd), storedPlaces).outcomes
        }
    }

    // With a trip proposed, an end drawn as a pin wears its place, and an end carrying on a track
    // leaves no stay there to show.
    fun shown(end: ManualPath.End, outcome: TripPlaces.Outcome) =
        proposal == null || (end !in continuing && outcome == TripPlaces.Outcome.None)
    val sides = listOfNotNull(
        detail.fromPlace?.takeIf { shown(ManualPath.End.ORIGIN, ends[0]) },
        detail.toPlace?.takeIf { shown(ManualPath.End.DESTINATION, ends[1]) },
    )
    val places = remember(sides) {
        sides.distinctBy { it.key }.map { side ->
            OverviewPlace(
                marker = PlaceMarker(side.pin, side.place),
                key = side.key,
                visits = side.visitCount,
                dot = UnnamedDot.of(side.place, brief = false),
                radiusM = side.radiusM,
            )
        }
    }
    MapLibreTripMap(
        origin = detail.gap.from?.at,
        destination = detail.gap.to?.at,
        places = places,
        shape = proposal?.trip?.path.orEmpty(),
        activity = proposal?.trip?.activityType,
        touching = touching,
        originPlace = ends[0],
        destinationPlace = ends[1],
        continuing = continuing,
        onLongPress = {},
        onPlaceTap = { _, _ -> },
        frameKey = frameKey,
        obscured = obscured,
        modifier = Modifier.fillMaxSize(),
        controls = { FrameControl(stringResource(R.string.gap_show_both_ends), onReframe) },
    )
}

@Composable
internal fun GapFacts(detail: GapDetail, onOpenPlace: (String) -> Unit) {
    val gap = detail.gap
    val verdict = detail.verdict
    val reader = timelineZone()
    val shiftColor = zoneShiftColor
    val readerClock = LocalReaderClock.current
    val line = listOfNotNull(
        StayDeriver.reportable(gap.end - gap.start)?.let { durationText(it) },
        verdict.apartMeters?.let { fineDistanceText(it) },
        verdict.atLeastMps?.let { stringResource(R.string.gap_at_least_speed, speedText(Speed.mps(it).kmh)) },
    ).joinToString(" · ")
    // Departure above arrival: one gap on its own reads in time order, unlike the newest-first timeline.
    Column(Modifier.padding(vertical = 8.dp)) {
        GapEndLine(
            detail.fromPlace,
            markedTime(gap.start, detail.departureZone, reader, shiftColor, readerClock),
            onOpenPlace,
        )
        if (line.isNotEmpty()) {
            Text(
                text = line,
                modifier = Modifier.padding(start = GAP_TEXT_INDENT, end = 16.dp, top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GapEndLine(
            detail.toPlace,
            markedTime(gap.end, detail.arrivalZone, reader, shiftColor, readerClock),
            onOpenPlace,
        )
    }
}

@Composable
private fun GapEndLine(place: PlaceResolver.ResolvedStay?, at: AnnotatedString, onOpenPlace: (String) -> Unit) {
    val time = @Composable {
        Text(
            text = at,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
    if (place == null) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = GAP_TEXT_INDENT, end = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) { time() }
        return
    }
    val category = place.category
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPlace(place.key) }
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
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = place.name ?: stringResource(R.string.timeline_unnamed_place),
                style = MaterialTheme.typography.titleMedium,
                color = statedTitleColor(stated = place.isNamed),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(12.dp))
            time()
        }
        place.place?.mark?.let {
            Spacer(Modifier.width(8.dp))
            OriginGlyph(it)
        }
    }
}

@Composable
internal fun GapSuggestion(verdict: GapDiagnosis.Verdict, busy: Boolean = false, onFix: (GapDiagnosis.Fix) -> Unit) {
    Button(
        onClick = { onFix(verdict.suggested) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        FixLabel(verdict.suggested, busy)
    }
}

@Composable
private fun FixLabel(fix: GapDiagnosis.Fix, busy: Boolean) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
    }
    Text(stringResource(fix.labelRes()))
}

/** The rows the timeline will show once [proposal] is saved: the stay it leaves, then the trip. */
@Composable
internal fun GapProposal(
    proposal: RoadProposal,
    detail: GapDetail,
    enabled: Boolean,
    onOpenPlace: (String) -> Unit,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
) {
    val trip = proposal.trip
    val departMs = trip.origin.end.timestampMs
    val arriveMs = trip.destination.end.timestampMs
    val place = detail.fromPlace
    // No cluster exists until the trip is saved, and the row reads only the bounds and [place].
    val stay = TimelineItem.StayItem(
        stay = StayDeriver.Stay(detail.gap.start, departMs, detail.gap.afterTrackId, clusterId = -1),
        place = place,
        zone = detail.departureZone,
    )
    val activity = trip.activityType
    val activityName = stringResource(activity.labelRes)
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StayCard(
            stay,
            groupedRowShape(0, 2),
            named = place?.isNamed == true,
            highlighted = false,
            zone = detail.departureZone,
            onClick = { place?.let { onOpenPlace(it.key) } },
        )
        ListRowCard(
            shape = groupedRowShape(1, 2),
            icon = activityIcon(activity),
            disc = activityDiscStyle(activity),
            iconDescription = activityName,
            marks = listOfNotNull(TrackOrigin.MANUAL.mark),
            title = trackRowTitle(activityName, proposal.lengthM),
            titleColor = MaterialTheme.colorScheme.onSurface,
            subtitle = trackRowSpan(
                departMs,
                detail.departureZone,
                arriveMs,
                detail.arrivalZone,
                durationText(departMs, arriveMs),
            ),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_cancel))
            }
            OutlinedButton(onClick = onEdit, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.gap_proposal_edit))
            }
            Button(onClick = onSave, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_save))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StayedHereSheet(
    choice: StayedHereChoice.Ask,
    onPick: (PlaceResolver.ResolvedStay) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember(choice) { mutableStateOf(choice.preselected) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            Text(
                stringResource(R.string.gap_stayed_where),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // Departure first, as the facts list them.
            listOf(choice.from, choice.to).forEach { side ->
                OptionRow(
                    icon = side.category.discIcon,
                    label = side.name ?: stringResource(R.string.timeline_unnamed_place),
                    tint = categoryGlyphTint(side.category),
                    labelColor = statedTitleColor(stated = side.isNamed),
                    selected = side.key == picked.key,
                    onClick = { picked = side },
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onPick(picked) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.gap_fix_stayed_here))
            }
        }
    }
}

@StringRes
internal fun GapDiagnosis.Fix.labelRes(): Int = when (this) {
    GapDiagnosis.Fix.MISSED_START -> R.string.gap_fix_missed_start
    GapDiagnosis.Fix.ADD_TRIP -> R.string.gap_fix_add_trip
    GapDiagnosis.Fix.STAYED_HERE -> R.string.gap_fix_stayed_here
    GapDiagnosis.Fix.MERGE -> R.string.gap_fix_merge
}

/** The 16.dp gutter, the 36.dp place disc and the 16.dp after it: where an end's text starts. */
private val GAP_TEXT_INDENT = 68.dp
