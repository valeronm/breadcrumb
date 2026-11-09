package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

@Composable
internal fun GapDetailScreen(
    afterTrackId: Long,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onAddTrip: (TripDraft) -> Unit,
    onStayedHere: (StayClaim, placeId: Long) -> Unit,
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
    val saving by viewModel.savingManualTrack.collectAsStateWithLifecycle()
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
        // Saved, the gap is gone and the page closes on its own.
        viewModel.saveManualTrack(null, proposed.trip) { result ->
            if (result !is TrackRepository.ManualTrackResult.Saved) viewModel.say(overlapsMessage)
        }
    }

    fun stayedHere(detail: GapDetail, side: PlaceResolver.ResolvedStay) {
        val placeId = side.place?.id ?: return
        onStayedHere(StayClaim(detail.before.id, detail.after.id), placeId)
    }

    fun onFix(chosen: GapDiagnosis.Fix) {
        val detail = d ?: return
        if (fetching != null) return
        when (val action = gapAction(chosen, detail)) {
            is GapAction.AddTrip -> onAddTrip(action.draft)
            is GapAction.ProposeStart -> propose(chosen, action.draft, detail.gap.start)
            is GapAction.Merge -> onMerge(action.plan)
            is StayedHereChoice.Direct -> stayedHere(detail, action.side)
            is StayedHereChoice.Ask -> asking = action
        }
    }

    var proposalKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(proposal) { if (proposal != null) proposalKey++ }

    MapSheetPage(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(stringResource(R.string.gap_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = {
                    if (d != null) Text(gapDays(d), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (d != null) ShowOnTimelineAction { onShowOnTimeline(d.gap) }
                },
            )
        },
        subject = d,
        peek = { detail ->
            val proposed = proposal
            if (proposed == null) {
                GapFacts(detail, onOpenPlace)
                GapSuggestion(detail.verdict, busy = fetching == detail.verdict.suggested, ::onFix)
                // In the peek, so a lowered sheet still shows that more fixes follow.
                if (detail.verdict.availableOthers.isNotEmpty()) SheetHeading(R.string.gap_other_fixes)
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
        },
        raised = { detail ->
            val verdict = detail.verdict
            // A proposal is one decision on screen.
            if (proposal == null) {
                val available = verdict.availableOthers
                val unavailable = verdict.others - available.toSet()
                if (available.isNotEmpty()) {
                    available.forEach { other ->
                        OutlinedButton(
                            onClick = { onFix(other) },
                            enabled = fetching != other,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            BusyLabel(stringResource(other.labelRes()), busy = fetching == other)
                        }
                    }
                }
                if (unavailable.isNotEmpty()) {
                    SheetHeading(R.string.gap_unavailable_fixes)
                    unavailable.forEach { other ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(other.labelRes()))
                            }
                            Text(
                                stringResource(verdict.blocked.getValue(other).labelRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        map = { peekKey, _, obscured ->
            if (d != null) GapMap(d, proposal, viewModel, (peekKey != 0) to proposalKey, obscured)
        },
    )

    val choice = asking
    if (d != null && choice != null) {
        StayedHereSheet(
            choice = choice,
            onPick = { side ->
                asking = null
                stayedHere(d, side)
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
    frameKey: Any,
    obscured: () -> MapInsets,
) {
    val touching by rememberTouchingTracks(viewModel, detail.before.id, detail.after.id)
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

    // With a trip proposed, its ends' markers stand for their places.
    fun shown(end: ManualPath.End, outcome: TripPlaces.Outcome) =
        proposal == null || TripPlaces.markerOf(outcome, end in continuing) == TripPlaces.Marker.SPOT
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
        continuingArgb = INTERVAL_END_ARGB.takeIf { proposal == null },
        onLongPress = {},
        onPlaceTap = { _, _ -> },
        frameKey = frameKey,
        obscured = obscured,
        modifier = Modifier.fillMaxSize(),
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
    SpanEnds(
        from = detail.fromPlace,
        fromAt = markedTime(gap.start, detail.departureZone, reader, shiftColor, readerClock),
        figures = line,
        to = detail.toPlace,
        toAt = markedTime(gap.end, detail.arrivalZone, reader, shiftColor, readerClock),
        onOpenPlace = onOpenPlace,
    )
}

@Composable
internal fun GapSuggestion(verdict: GapDiagnosis.Verdict, busy: Boolean = false, onFix: (GapDiagnosis.Fix) -> Unit) {
    Button(
        onClick = { onFix(verdict.suggested) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        BusyLabel(stringResource(verdict.suggested.labelRes()), busy)
    }
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
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StayCard(
            stay,
            groupedRowShape(0, 2),
            highlighted = false,
            zone = detail.departureZone,
            onClick = { place?.let { onOpenPlace(it.key) } },
        )
        TripRowCard(
            shape = groupedRowShape(1, 2),
            activity = trip.activityType,
            name = stringResource(trip.activityType.labelRes),
            marks = listOfNotNull(TrackOrigin.MANUAL.mark),
            lengthM = proposal.lengthM,
            span = trackRowSpan(
                departMs,
                detail.departureZone,
                arriveMs,
                detail.arrivalZone,
                durationText(departMs, arriveMs),
            ),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onCancel, enabled = enabled) {
                Text(stringResource(R.string.common_cancel))
            }
            OutlinedButton(onClick = onEdit, enabled = enabled) {
                Text(stringResource(R.string.gap_proposal_edit))
            }
            Button(onClick = onSave, enabled = enabled) {
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

/** The day the gap began on its departure's clock, and the day it ended where that differs. */
@Composable
internal fun gapDays(detail: GapDetail): String {
    val dayLabel = rememberDayLabel()
    val first = detail.gap.start.toLocalDate(detail.departureZone)
    val last = detail.gap.end.toLocalDate(detail.arrivalZone)
    return if (first == last) dayLabel(first) else stringResource(R.string.gap_days, dayLabel(first), dayLabel(last))
}

@StringRes
internal fun GapDiagnosis.Fix.labelRes(): Int = when (this) {
    GapDiagnosis.Fix.MISSED_START -> R.string.gap_fix_missed_start
    GapDiagnosis.Fix.ADD_TRIP -> R.string.gap_fix_add_trip
    GapDiagnosis.Fix.STAYED_HERE -> R.string.gap_fix_stayed_here
    GapDiagnosis.Fix.MERGE -> R.string.gap_fix_merge
}

@StringRes
internal fun GapDiagnosis.Block.labelRes(): Int = when (this) {
    GapDiagnosis.Block.MERGE_ACTIVITIES_DIFFER -> R.string.gap_blocked_merge_activities
    GapDiagnosis.Block.MERGE_WRITERS_DIFFER -> R.string.gap_blocked_merge_writers
    GapDiagnosis.Block.STAY_NO_POINT_BEFORE -> R.string.gap_blocked_stay_no_point_before
    GapDiagnosis.Block.STAY_NO_POINT_AFTER -> R.string.gap_blocked_stay_no_point_after
    GapDiagnosis.Block.STAY_TOO_FAR -> R.string.gap_blocked_stay_too_far
    GapDiagnosis.Block.STAY_NO_NAMED_PLACE -> R.string.gap_blocked_stay_no_named_place
}

private val GapDiagnosis.Verdict.availableOthers: List<GapDiagnosis.Fix> get() = others.filter { it !in blocked }
