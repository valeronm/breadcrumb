package io.github.valeronm.breadcrumb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.data.OnlinePlaceSearch
import io.github.valeronm.breadcrumb.data.OnlineRouting
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.CityAtlas
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.EndTime
import io.github.valeronm.breadcrumb.domain.ManualPath
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.PlaceSearch
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TripPlaces
import io.github.valeronm.breadcrumb.domain.isNamed
import io.github.valeronm.breadcrumb.domain.pin
import io.github.valeronm.breadcrumb.domain.placeCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

/** What the add-trip form offers: every movement type, carried transport first — a trip goes
 *  missing because nothing recorded it, and that happens to walks as readily as to flights. */
private val MANUAL_TRIP_TYPES = listOf(
    ActivityType.FLIGHT,
    ActivityType.TRANSIT,
    ActivityType.FERRY,
    ActivityType.TAXI,
    ActivityType.DRIVING,
    ActivityType.WALKING,
    ActivityType.RUNNING,
    ActivityType.CYCLING,
    ActivityType.UNKNOWN,
)

private const val HOUR_MS = 3_600_000L

/** Material's icon size, which its library keeps internal. */
private val GlyphSize = 24.dp

/** Where an empty end's pickers open: noon of [day] when the Timeline supplied the day the user
 *  was looking at, today's noon with nothing better to go on. */
private fun noonOf(day: LocalDate?, zone: ZoneId): ZonedDateTime =
    (day ?: LocalDate.now(zone)).atTime(12, 0).atZone(zone)

/**
 * What the form opens holding. The Timeline's top bar knows only which day was on screen; a gap's
 * page knows the places the absence lies between and when it ran.
 */
internal class TripDraft(
    /** The day an end with no time of its own opens its pickers on. */
    val day: LocalDate?,
    val origin: TripDraftEnd? = null,
    val destination: TripDraftEnd? = null,
    /** Set where the form is restating a trip that already exists — see [EditedTrip]. */
    val editing: EditedTrip? = null,
    /** The recorded tracks either side of the absence, the one before first, which bound when the
     *  trip can have been. */
    val tracksAround: Pair<Long?, Long?> = null to null,
    /** A type the trip is already known to have, which the form opens on where it offers it. */
    val activity: ActivityType? = null,
)

/** The manual track a form was opened on: its row is rewritten in place rather than a new one inserted. */
internal class EditedTrip(
    val trackId: Long,
    /** The road shape stored between the trip's ends, or empty for a trip of its ends alone. */
    val path: List<Coordinate>,
)

/**
 * One end a caller can already fill in.
 *
 * [timeMs] is an instant rather than a wall clock, because neither half of a wall clock is
 * available at the point one would be built: the zone comes from resolving the pin, which the form
 * does after it opens, and the pickers' minute resolution would round the bound off the
 * neighboring track — a departure rounded back past that track's last fix overlaps it, and the
 * insert refuses an overlap. Held exactly until a picked time replaces it, so a trip committed as
 * drafted meets the recording on either side of the absence rather than a minute inside it.
 */
internal class TripDraftEnd(
    /** Null where the caller knows when but not where — a gap side the recorder never got a fix for. */
    val at: Coordinate?,
    /** The user's own name for the spot, never a derived one — see [TripEnd.placeName]. */
    val placeName: String?,
    /** Null where the caller knows where but not when. */
    val timeMs: Long?,
)

/** One end of the trip being entered — a pin and a time, each settable in any order, and either of
 *  them possibly already known to whoever opened the form ([TripDraftEnd]). */
private class TripEnd(drafted: TripDraftEnd?) {
    var pin by mutableStateOf(drafted?.at)

    /** The name the pin was picked *by* — a saved place's label, or an online hit's name — which
     *  [TripPlaces.plan] makes a place of. Null for a hand-placed pin, which has no name to give,
     *  and for a picked *city*, which must not become a capture circle at its centroid. */
    var placeName by mutableStateOf(drafted?.placeName)

    /** What this end holds of its own, the bound it was drafted with to begin with. */
    var own by mutableStateOf(drafted?.timeMs?.let { EndTime.Held(it) } ?: EndTime.None)
        private set

    /** Placed from the other end by a road's duration; held apart from [own] because a lifted
     *  coupling must give back the time that was recorded or typed. */
    var coupledMs by mutableStateOf<Long?>(null)

    fun side(atMs: Long?): ManualPath.StaySide? {
        val at = pin ?: return null
        return atMs?.let { ManualPath.StaySide(it, at) }
    }

    fun epochIn(zone: ZoneId): Long? = coupledMs ?: own.epochIn(zone)

    /** The day this end shows on [zone]'s clock: an estimate's, else its own. */
    fun dateIn(zone: ZoneId): LocalDate? = coupledMs?.toLocalDate(zone) ?: own.dateIn(zone)

    /** Makes [time] this end's own, in place of any estimate standing in for what it held. */
    fun hold(time: EndTime) {
        own = time
        coupledMs = null
    }

    /** Where this end's pickers open: on the time it shows, else on [fallback] — moved to the date
     *  it holds alone, where it holds one. */
    fun pickerStart(zone: ZoneId, fallback: ZonedDateTime): ZonedDateTime =
        epochIn(zone)?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: own.dateIn(zone)?.let(fallback::with) ?: fallback
}

/**
 * A road path with the pins and profile it was asked for, drawn only while those are what the form
 * says; switching away from a type and back brings it back.
 */
private class RoutedLeg(
    val profile: OnlineRouting.Profile,
    val from: Coordinate,
    val to: Coordinate,
    val shape: List<Coordinate>,
    val durationMs: Long,
) {
    /** The length of the line the trip will be stored along — the saved row's figure, not the
     *  router's, which measures between road-snapped ends. */
    val lengthM: Double by lazy { ManualPath.lengthM(ManualPath.line(from, to, shape), AndroidDistance) }
}

/**
 * The path a trip being edited was saved with, as the leg it would be asked for again; its
 * duration is the row's span, which is what the saved times were coupled by.
 */
private fun TripDraft.storedRoute(): RoutedLeg? {
    val edited = editing ?: return null
    if (edited.path.isEmpty()) return null
    val profile = activity?.let(OnlineRouting.Profile::of) ?: return null
    val from = origin ?: return null
    val to = destination ?: return null
    val fromMs = from.timeMs ?: return null
    val toMs = to.timeMs ?: return null
    return RoutedLeg(profile, from.at ?: return null, to.at ?: return null, edited.path, toMs - fromMs)
}

/** One end's date or time being edited: which end, on whose clock, and where the picker opens
 *  ([TripEnd.pickerStart]). */
private class TimeEdit(
    val which: ManualPath.End,
    val zone: ZoneId,
    /** The date rather than the time. */
    val date: Boolean,
    val start: ZonedDateTime,
    /** The days the date picker offers, where known. */
    val days: ClosedRange<LocalDate>?,
)

/**
 * A recorded track a trip being entered meets at one of its ends: its path, and the fix and the
 * bound it meets the trip at — its end where it comes before the trip, else its start.
 */
internal class TouchingTrack(
    val line: JourneyLine,
    val touching: Coordinate,
    val boundMs: Long,
    /** The place the touching end is stated to. */
    val placeId: Long?,
) {
    val activity: ActivityType? get() = ActivityType.ofName(line.activityType)
    val side get() = ManualPath.StaySide(boundMs, touching, placeId)
}

/**
 * Enter a trip nothing recorded — a flight, a leg with the phone dead — as two pins and two times,
 * inserted as a manual track, with a router's road shape between them where one was asked for. Each
 * end's typed time is read in the IANA zone of the city the atlas resolves its pin to (shown on the
 * end's card, never silently applied), so a past flight's times can be entered as the boarding pass
 * states them.
 * Everything is local until the check mark commits, so backing out discards by construction — the
 * [PlaceEditScreen] pattern.  What the form opens holding is [draft]'s — the ends a gap's page
 * could already fill in, or nothing but the day the Timeline was showing. **It is also how a manual trip
 * is edited** ([TripDraft.editing]): the same two pins, two times and road shape, rewritten onto the
 * row they came from, since what a hand-entered trip *is* and what the form asks for are the same
 * thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTripScreen(
    viewModel: TrackListViewModel,
    draft: TripDraft,
    onClose: () -> Unit,
) {
    // Keyed on the draft: a second gap's form can open while the first is still animating out, and
    // an unkeyed remember would hand it the ends the last one was filled with.
    val origin = remember(draft) { TripEnd(draft.origin) }
    val destination = remember(draft) { TripEnd(draft.destination) }
    // A default beyond the draft's would be a guess the user commits without reading: a missing
    // leg's two ends say nothing about how it was made.
    var activity by remember(draft) {
        mutableStateOf(draft.activity?.takeIf { it in MANUAL_TRIP_TYPES })
    }
    // The end being placed, if any — the one thing search, the saved places and a long-press act
    // on, so with both ends set none of them is offered until an end is chosen to move.
    var picking by remember(draft) { mutableStateOf(nextToPlace(origin, destination)) }
    var editing by remember { mutableStateOf<TimeEdit?>(null) }
    var choosingType by remember { mutableStateOf(false) }
    var centerRequest by remember(draft) { mutableStateOf<MapCenterRequest?>(null) }
    var frameKey by remember(draft) { mutableIntStateOf(0) }
    // Where the map is looking, as of its last settle — what the place search sorts around. Keyed
    // like the ends beside it: a second form opening while the first animates out gets a map of its
    // own, and would otherwise sort around wherever that one was left.
    var mapCenter by remember(draft) { mutableStateOf<Coordinate?>(null) }
    var routed by remember(draft) { mutableStateOf(draft.storedRoute()) }
    // The tracks the form was opened between, whatever is typed after: a gap's draft names them, and
    // a trip being edited sits between its neighbors on the timeline.
    val touching by produceState<Pair<TouchingTrack?, TouchingTrack?>>(null to null, draft) {
        val (before, after) = draft.editing?.let { viewModel.tracksAround(it.trackId) }
            ?: draft.tracksAround
        value = viewModel.touchingTracks(before, after)
    }
    val touchingBefore = touching.first?.takeIf { draft.editing != null || draft.origin != null }
    val touchingAfter = touching.second?.takeIf { draft.editing != null || draft.destination != null }
    val drawnTouching = listOfNotNull(touchingBefore, touchingAfter)
    var fetchingRoute by remember(draft) { mutableStateOf(false) }
    val profile = activity?.let(OnlineRouting.Profile::of)
    val leg = routed?.takeIf { it.profile == profile && it.from == origin.pin && it.to == destination.pin }
    val context = LocalContext.current
    val routingOn = remember { AppSettings.isOnlineRouting(context) }
    val scope = rememberCoroutineScope()

    val originCity by produceState<CityAtlas.City?>(null, origin.pin) {
        value = origin.pin?.let { viewModel.cityAt(it) }
    }
    val destinationCity by produceState<CityAtlas.City?>(null, destination.pin) {
        value = destination.pin?.let { viewModel.cityAt(it) }
    }
    val originZone = zoneOrDevice(originCity?.zoneId)
    val destinationZone = zoneOrDevice(destinationCity?.zoneId)

    val originDays = ManualPath.openDays(touching.first?.boundMs, touching.second?.boundMs, originZone)
    val destinationDays = ManualPath.openDays(touching.first?.boundMs, touching.second?.boundMs, destinationZone)
    val departMs = origin.epochIn(originZone)
    val arriveMs = destination.epochIn(destinationZone)
    val continuing = ManualPath.continuing(
        departMs,
        arriveMs,
        previousEndMs = touching.first?.boundMs,
        nextStartMs = touching.second?.boundMs,
    )

    // With a road path attached the two times are one fact and its duration: the end at [from]
    // keeps its own time and the other is placed from it ([ManualPath.coupledEnd]).
    fun coupleTimes(from: ManualPath.End, durationMs: Long) {
        val forward = from == ManualPath.End.ORIGIN
        val kept = if (forward) origin else destination
        val other = if (forward) destination else origin
        val at = kept.own.epochIn(if (forward) originZone else destinationZone) ?: return
        kept.coupledMs = null
        other.coupledMs = ManualPath.coupledEnd(at, durationMs, forward)
    }

    fun keptEnd(): ManualPath.End? = when {
        destination.coupledMs != null -> ManualPath.End.ORIGIN
        origin.coupledMs != null -> ManualPath.End.DESTINATION
        else -> null
    }

    // Where an end's time can be estimated from the other: a road between two ends that each hold
    // a time of their own, neither yet estimated. With one timed end the road places the other
    // when it arrives, leaving nothing to choose.
    val canEstimate = leg != null &&
        keptEnd() == null &&
        origin.own.epochIn(originZone) != null &&
        destination.own.epochIn(destinationZone) != null

    fun estimate(end: ManualPath.End) {
        val durationMs = leg?.durationMs ?: return
        val from = if (end == ManualPath.End.ORIGIN) ManualPath.End.DESTINATION else ManualPath.End.ORIGIN
        coupleTimes(from, durationMs)
    }

    fun restoreTimes() {
        origin.coupledMs = null
        destination.coupledMs = null
    }

    // Resolved here: the result lands outside the composition.
    val noRoadsMessage = stringResource(R.string.addtrip_no_roads)

    fun followRoads() {
        val by = profile ?: return
        val from = origin.pin ?: return
        val to = destination.pin ?: return
        fetchingRoute = true
        scope.launch {
            val found = viewModel.routeOnline(by, from, to)
            fetchingRoute = false
            if (found == null) {
                viewModel.say(noRoadsMessage)
            } else {
                routed = RoutedLeg(by, from, to, found.shape, found.durationMs)
                frameKey++
                // The end already kept may be the user's choice, which a new road leaves standing.
                val keep = keptEnd() ?: ManualPath.anchor(
                    originTimed = origin.own.epochIn(originZone) != null,
                    destinationTimed = destination.own.epochIn(destinationZone) != null,
                    originNamed = origin.placeName != null,
                    destinationNamed = destination.placeName != null,
                )
                keep?.let { coupleTimes(it, found.durationMs) }
            }
        }
    }

    val error = when {
        departMs != null && arriveMs != null && arriveMs <= departMs ->
            stringResource(R.string.addtrip_error_order)
        departMs != null && departMs > System.currentTimeMillis() ->
            stringResource(R.string.addtrip_error_future)
        else -> null
    }
    // Resolved here: the save callback below runs outside the composition.
    val overlapsMessage = stringResource(R.string.addtrip_overlaps)
    val goneMessage = stringResource(R.string.addtrip_gone)
    // The pins are asked about separately rather than taken as implied by the times: a typed time
    // does wait for its pin, but a drafted one arrives without asking (a gap side the recorder
    // never fixed knows when it was and not where).
    val ready = activity != null &&
        origin.pin != null &&
        destination.pin != null &&
        departMs != null &&
        arriveMs != null &&
        error == null

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun endOf(which: ManualPath.End) = if (which == ManualPath.End.ORIGIN) origin else destination

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Expanded,
        enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded),
    )
    // An end is moved on the map, so the sheet is lowered while it is picked. A cancelled pick
    // raises it again only if it was lowered for that rather than by the user.
    var sheetLowered by remember { mutableStateOf(false) }

    fun placePin(at: Coordinate, placeName: String? = null) {
        val end = endOf(picking ?: return)
        // Placing a pin — from a search result, a map pin or a long press — is done with the
        // field: drop its focus and the keyboard with it, or the results list closes onto a
        // keyboard covering the cards it just filled in.
        focusManager.clearFocus()
        keyboard?.hide()
        end.pin = at
        end.placeName = placeName
        picking = nextToPlace(origin, destination)
        if (picking == null) {
            // The trip is whole: the sheet comes back up, and the trip is fitted once it has, so
            // the fit reads the sheet where it stopped.
            sheetLowered = false
            if (sheetState.currentValue == SheetValue.Expanded) {
                frameKey++
            } else {
                scope.launch {
                    sheetState.expand()
                    frameKey++
                }
            }
        }
    }

    fun stopPicking() {
        picking = null
        if (sheetLowered) {
            sheetLowered = false
            scope.launch { sheetState.expand() }
        }
    }

    // Only the dot of the track the end being picked meets places that end, and at that track's own
    // bound.
    fun takeTouching(track: TouchingTrack) {
        val which = picking ?: return
        if (track !== (if (which == ManualPath.End.ORIGIN) touching.first else touching.second)) return
        endOf(which).hold(EndTime.Held(track.boundMs))
        placePin(track.touching)
    }

    // Only an end with a pin to keep can be left unpicked.
    val canStopPicking = picking?.let { endOf(it).pin != null } == true
    BackHandler(enabled = canStopPicking, onBack = ::stopPicking)

    // Asked again on every tap: panning away and tapping the row is the way back to the end.
    fun centerOn(end: TripEnd) {
        end.pin?.let { centerRequest = MapCenterRequest(it) }
    }

    fun editOrigin(date: Boolean) = TimeEdit(
        ManualPath.End.ORIGIN, originZone, date,
        origin.pickerStart(originZone, noonOf(draft.day, originZone)),
        originDays,
    )

    // An hour past the departure, on the arrival's own clock: the pickers open a short scroll from
    // any real arrival instead of at an arbitrary noon.
    fun editDestination(date: Boolean) = TimeEdit(
        ManualPath.End.DESTINATION, destinationZone, date,
        destination.pickerStart(
            destinationZone,
            departMs?.let { Instant.ofEpochMilli(it + HOUR_MS).atZone(destinationZone) }
                ?: noonOf(draft.day, destinationZone),
        ),
        destinationDays,
    )

    val storedPlaces by viewModel.storedPlaces.collectAsStateWithLifecycle()
    val tripPlaces = remember(
        storedPlaces, origin.pin, origin.placeName, destination.pin, destination.placeName,
    ) {
        TripPlaces.plan(
            listOf(TripPlaces.End(origin.pin, origin.placeName), TripPlaces.End(destination.pin, destination.placeName)),
            storedPlaces,
            AndroidDistance,
            // The preview writes nothing, so its rows are never stamped.
            createdAt = 0,
        )
    }
    val (originPlace, destinationPlace) = tripPlaces.outcomes
    val agreement = remember(tripPlaces) {
        StayDeriver.Agreement(StayDeriver.Params(), AndroidDistance, tripPlaces.seeds)
    }
    fun stay(left: ManualPath.StaySide?, right: ManualPath.StaySide?) =
        if (left != null && right != null) ManualPath.stayMs(left, right, agreement) else null
    val stayBeforeMs = stay(touchingBefore?.side, origin.side(departMs))
    val stayAfterMs = stay(destination.side(arriveMs), touchingAfter?.side)
    val cover = remember { MapCover() }
    fun pick(end: ManualPath.End) {
        picking = end
        if (sheetState.currentValue == SheetValue.Expanded) {
            sheetLowered = true
            scope.launch { sheetState.partialExpand() }
        }
    }
    BottomSheetScaffold(
        scaffoldState = rememberBottomSheetScaffoldState(
            sheetState,
        ),
        containerColor = MaterialTheme.colorScheme.background,
        sheetPeekHeight = BottomSheetDefaults.SheetPeekHeight +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        sheetDragHandle = {
            BottomSheetDefaults.DragHandle(
                Modifier.onGloballyPositioned { cover.sheetTop = it.boundsInWindow().top },
            )
        },
        sheetContent = {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TripEndCard(
                        shape = groupedRowShape(0, 3),
                        mark = endMark(originPlace, ManualPath.End.ORIGIN in continuing),
                        label = stayBeforeMs?.let { stringResource(R.string.addtrip_departure_stayed, durationText(it)) }
                            ?: stringResource(R.string.addtrip_departure),
                        end = origin,
                        city = originCity,
                        zone = originZone,
                        active = picking == ManualPath.End.ORIGIN,
                        onCenter = { centerOn(origin) },
                        onPick = { pick(ManualPath.End.ORIGIN) },
                        onEditDate = { editing = editOrigin(date = true) },
                        days = originDays,
                        onEditTime = { editing = editOrigin(date = false) },
                        canEstimate = canEstimate,
                        onEstimate = { estimate(ManualPath.End.ORIGIN) },
                        onRestore = ::restoreTimes,
                    )
                    TripLegRow(
                        shape = groupedRowShape(1, 3),
                        activity = activity,
                        onShow = { frameKey++ },
                        onChangeType = { choosingType = true },
                        lengthM = leg?.lengthM ?: origin.pin?.let { from ->
                            destination.pin?.let { to -> ManualPath.lengthM(listOf(from, to), AndroidDistance) }
                        },
                        departMs = departMs,
                        departZone = originZone,
                        arriveMs = arriveMs,
                        arriveZone = destinationZone,
                    )
                    TripEndCard(
                        shape = groupedRowShape(2, 3),
                        mark = endMark(destinationPlace, ManualPath.End.DESTINATION in continuing),
                        label = stayAfterMs?.let { stringResource(R.string.addtrip_arrival_stayed, durationText(it)) }
                            ?: stringResource(R.string.addtrip_arrival),
                        end = destination,
                        city = destinationCity,
                        zone = destinationZone,
                        active = picking == ManualPath.End.DESTINATION,
                        onCenter = { centerOn(destination) },
                        onPick = { pick(ManualPath.End.DESTINATION) },
                        onEditDate = { editing = editDestination(date = true) },
                        days = destinationDays,
                        onEditTime = { editing = editDestination(date = false) },
                        canEstimate = canEstimate,
                        onEstimate = { estimate(ManualPath.End.DESTINATION) },
                        onRestore = ::restoreTimes,
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    Text(
                        stringResource(
                            if (draft.editing != null) {
                                R.string.addtrip_title_edit
                            } else {
                                R.string.addtrip_title_add
                            },
                        ),
                    )
                },
                navigationIcon = { BackNavIcon(onClose) },
                actions = {
                    SaveAction(enabled = ready) {
                        val type = activity ?: return@SaveAction
                        val from = origin.pin ?: return@SaveAction
                        val to = destination.pin ?: return@SaveAction
                        if (departMs == null || arriveMs == null) return@SaveAction
                        viewModel.saveManualTrack(
                            draft.editing?.trackId,
                            TrackListViewModel.ManualTrip(
                                type,
                                TrackListViewModel.ManualTripEnd(
                                    TrackRepository.ManualEnd(from, departMs),
                                    origin.placeName,
                                ),
                                TrackListViewModel.ManualTripEnd(
                                    TrackRepository.ManualEnd(to, arriveMs),
                                    destination.placeName,
                                ),
                                leg?.shape.orEmpty(),
                            ),
                        ) { result ->
                            val refusal = when (result) {
                                is TrackRepository.ManualTrackResult.Saved -> null
                                TrackRepository.ManualTrackResult.Overlapping -> overlapsMessage
                                TrackRepository.ManualTrackResult.NotEditable -> goneMessage
                            }
                            if (refusal == null) {
                                onClose()
                            } else {
                                viewModel.say(refusal)
                            }
                        }
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            val queryState = remember { TextFieldState() }
            // A query is for the end being picked, and is not carried to the next one.
            LaunchedEffect(picking) { queryState.clearText() }
            val query by remember(queryState) { derivedStateOf { queryState.text.toString() } }
            // An unnamed row reads as the unnamed cluster it seeds, which this form neither draws
            // nor offers.
            val savedPlaces = remember(storedPlaces) { storedPlaces.filter { it.isNamed } }
            val summaries by viewModel.places.collectAsStateWithLifecycle()
            val visits = remember(summaries) {
                summaries.orEmpty().mapNotNull { s -> s.place?.let { it.id to s.visitCount } }.toMap()
            }
            // Keyed on the counts' contents: most summaries emissions leave them as they were, and a
            // new field rebuilds the map's whole pin source.
            val placeField = remember(savedPlaces, visits) {
                savedPlaces.map { place ->
                    OverviewPlace(
                        marker = PlaceMarker(place.pin, place),
                        key = PlaceResolver.keyOf(place.id),
                        visits = visits[place.id] ?: 0,
                    )
                }
            }
            // Pre-folded once per list — PlaceSearch's contract for filtering per keystroke.
            val foldedLabels = remember(savedPlaces) { savedPlaces.map { PlaceSearch.fold(it.label.orEmpty()) } }
            // Nearest what the map is looking at first: a name is rarely unique across a history —
            // "Home", "Hotel", the same shop in three cities — and the ground on screen is the only
            // thing said about which one is meant. Falls back to the table's order until the map has
            // settled once, which is the order this list had before there was anything to sort by.
            // Derived rather than keyed on the map's center, which moves on every camera settle: it
            // is read only while there is a query to sort.
            val placeMatches by remember(savedPlaces, foldedLabels) {
                derivedStateOf {
                    val needle = PlaceSearch.fold(query)
                    if (needle.isEmpty()) {
                        emptyList()
                    } else {
                        val matches = savedPlaces.filterIndexed { i, _ -> foldedLabels[i].contains(needle) }
                        val from = mapCenter
                        if (from == null) {
                            matches
                        } else {
                            // Measured once per place and sorted on the answer: a comparator that
                            // measured would run an ellipsoidal solve on both sides of every
                            // comparison, and a one-letter query can match hundreds of places.
                            matches.map { it to AndroidDistance.meters(from.lat, from.lon, it.lat, it.lon) }
                                .sortedBy { (_, meters) -> meters }
                                .map { (place, _) -> place }
                        }
                    }
                }
            }
            // Where each matched place sits, so a saved pin says as much about itself as a city or
            // an online hit does — two spots the user called "Mum's" are told apart by nothing else.
            // Filled in as rows appear and kept for the form's life: typing a name walks the same
            // handful of places on every keystroke, and a stored null is an answer (nothing in the
            // atlas reaches there), not a miss to retry.
            val placeCities = remember { mutableStateMapOf<Long, CityAtlas.City?>() }
            LaunchedEffect(placeMatches) {
                for (place in placeMatches) {
                    if (place.id in placeCities) continue
                    placeCities[place.id] = viewModel.cityAt(place.pin)
                }
            }
            val cityHits by produceState(emptyList<CityAtlas.Hit>(), query) {
                value = if (query.isBlank()) {
                    emptyList()
                } else {
                    // Let typing settle before a scan of 160k names; the field filter above is
                    // cheap enough to run unthrottled.
                    delay(150)
                    viewModel.searchCities(query, limit = 6)
                }
            }
            fun pickResult(at: Coordinate, placeName: String? = null) {
                placePin(at, placeName)
                queryState.clearText()
            }
            val onlineHits by rememberOnlineHits(query, near = origin.pin ?: destination.pin, viewModel)
            Box(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onGloballyPositioned {
                        val bounds = it.boundsInWindow()
                        cover.mapTop = bounds.top
                        cover.mapBottom = bounds.bottom
                    },
            ) {
                MapLibreTripMap(
                    origin = origin.pin,
                    destination = destination.pin,
                    places = placeField,
                    showPlaces = picking != null,
                    shape = leg?.shape.orEmpty(),
                    activity = activity,
                    touching = drawnTouching,
                    onTouchingTap = ::takeTouching,
                    originPlace = originPlace,
                    destinationPlace = destinationPlace,
                    continuing = continuing,
                    onLongPress = ::placePin,
                    onPlaceTap = ::placePin,
                    center = centerRequest,
                    frameKey = frameKey,
                    onCenterSettled = { mapCenter = it },
                    obscured = cover::insets,
                    modifier = Modifier.fillMaxSize(),
                    controls = {
                        FrameControl(stringResource(R.string.addtrip_show_whole_trip)) { frameKey++ }
                    },
                )
                val halo = touchHalo(FilterChipDefaults.Height)
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(top = MapInset)
                        .onGloballyPositioned { cover.headerBottom = it.boundsInWindow().bottom },
                    // Negative where the halos exceed the gap: the touch targets overlap, the
                    // chips do not.
                    verticalArrangement = Arrangement.spacedBy(8.dp - halo * 2),
                ) {
                    if (picking != null) {
                        SearchDropdown(
                            query = query,
                            open = query.isNotBlank(),
                            field = { anchor ->
                                SearchField(
                                    state = queryState,
                                    placeholder = stringResource(R.string.addtrip_search_placeholder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = MapInset)
                                        .padding(bottom = halo)
                                        .shadow(3.dp, CircleShape)
                                        .then(anchor),
                                )
                            },
                        ) {
                            for (place in placeMatches) {
                                val label = place.label ?: continue
                                SearchResultRow(
                                    icon = Icons.Filled.Place,
                                    label = label,
                                    detail = placeCities[place.id]?.let { localityLabel(it) },
                                ) {
                                    pickResult(place.pin, label)
                                }
                            }
                            for (hit in cityHits) {
                                SearchResultRow(
                                    icon = Icons.Filled.LocationCity,
                                    label = hit.name,
                                    detail = countryDisplayName(hit.country),
                                ) {
                                    pickResult(Coordinate(hit.lat, hit.lon))
                                }
                            }
                            // Below the local sections, minus what they already show — the
                            // geocoder returns big cities too, and "Lisbon" twice reads as a
                            // glitch, not as two sources.
                            val online = remember(cityHits, onlineHits) {
                                val cities = cityHits.map { PlaceSearch.fold(it.name) }.toSet()
                                OnlinePlaceSearch.distinctRows(
                                    onlineHits.filter { PlaceSearch.fold(it.name) !in cities },
                                )
                            }
                            for (hit in online) {
                                SearchResultRow(
                                    icon = Icons.Filled.TravelExplore,
                                    label = hit.name,
                                    detail = hit.locality,
                                ) {
                                    // The one pick that knows its own name — carried so the
                                    // commit can create the place this end will have stayed at.
                                    pickResult(Coordinate(hit.lat, hit.lon), hit.name)
                                }
                            }
                            if (online.isNotEmpty()) OsmCredit()
                            if (placeMatches.isEmpty() && cityHits.isEmpty() && online.isEmpty()) {
                                Text(
                                    stringResource(R.string.addtrip_no_matches),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                    Column(
                        Modifier.padding(horizontal = MapInset),
                        verticalArrangement = Arrangement.spacedBy(8.dp - halo * 2),
                    ) {
                        // The switch on the Privacy page takes the offer away entirely rather than
                        // leaving a chip that does nothing. On, the chip is the path's own
                        // description.
                        val roadTrip = profile != null && origin.pin != null && destination.pin != null
                        if (routingOn && roadTrip) {
                            MapFilterChip(
                                selected = leg != null,
                                enabled = !fetchingRoute,
                                label = if (leg == null) {
                                    stringResource(R.string.addtrip_follow_roads)
                                } else {
                                    stringResource(
                                        R.string.addtrip_along_roads,
                                        distanceText(leg.lengthM),
                                        durationText(leg.durationMs),
                                    )
                                },
                            ) {
                                if (leg == null) {
                                    followRoads()
                                } else {
                                    routed = null
                                    frameKey++
                                }
                            }
                        }
                        picking?.let { which ->
                            MapStackLegend {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(
                                            if (which == ManualPath.End.DESTINATION) {
                                                R.string.addtrip_tap_destination
                                            } else {
                                                R.string.addtrip_tap_origin
                                            },
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    if (canStopPicking) {
                                        IconButton(onClick = ::stopPicking, modifier = Modifier.size(28.dp)) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = stringResource(R.string.common_cancel),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (choosingType) {
        ActivityTypeDialog(
            options = MANUAL_TRIP_TYPES,
            selected = activity,
            onDismiss = { choosingType = false },
        ) {
            activity = it
            choosingType = false
        }
    }

    editing?.let { edit ->
        val end = endOf(edit.which)
        val closeDialogs = { editing = null }
        // A pick of one half keeps the other as the end shows it, an estimate included.
        val shownMs = end.epochIn(edit.zone)
        fun commit(time: EndTime) {
            end.hold(time)
            if (time.epochIn(edit.zone) != null) leg?.let { coupleTimes(edit.which, it.durationMs) }
            closeDialogs()
        }
        // Keyed on what is being edited: the picker states live at one call site, and without the
        // key, opening the arrival picker would show whatever the departure picker last held.
        key(end, edit.date) {
            if (edit.date) {
                LocalDateDialog(
                    initial = edit.start.toLocalDate(),
                    confirmLabel = stringResource(R.string.common_ok),
                    onConfirm = { commit(EndTime.withDate(shownMs, it, edit.zone)) },
                    onDismiss = closeDialogs,
                    days = edit.days,
                )
            } else {
                val initial = edit.start.toLocalTime()
                val timeState = rememberTimePickerState(
                    initialHour = initial.hour,
                    initialMinute = initial.minute,
                )
                TimePickerDialog(
                    onDismissRequest = closeDialogs,
                    title = {
                        Column(Modifier.padding(bottom = 16.dp)) {
                            Text(
                                // Whole, rather than a noun the dialog appends "time" to.
                                stringResource(
                                    when (edit.which) {
                                        ManualPath.End.ORIGIN -> R.string.addtrip_departure_time_title
                                        ManualPath.End.DESTINATION -> R.string.addtrip_arrival_time_title
                                    },
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // The hour typed here is read on the end's clock, not the reader's.
                            zoneShiftLabel(edit.start.toInstant().toEpochMilli(), edit.zone, timelineZone())?.let {
                                Text(
                                    stringResource(R.string.addtrip_local_clock_note, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                // The picker opened on the day the end shows, or holds alone.
                                commit(
                                    EndTime.Typed(
                                        edit.start.toLocalDate(),
                                        LocalTime.of(timeState.hour, timeState.minute),
                                    ),
                                )
                            },
                        ) { Text(stringResource(R.string.common_ok)) }
                    },
                    dismissButton = {
                        TextButton(onClick = closeDialogs) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    },
                ) { TimePicker(timeState) }
            }
        }
    }
}

/**
 * How an end's row is marked, as the timeline will mark the stay there: the place it will show, if
 * any ([TripPlaces.plan]) — or a point where it carries straight on from a recorded track and no
 * stay will stand.
 */
private class EndMark(val icon: ImageVector, val disc: DiscStyle, val name: String?)

@Composable
private fun endMark(place: TripPlaces.Outcome, continues: Boolean): EndMark {
    val neutral = placeDiscStyle(null)
    val name = place.name
    return when {
        continues -> EndMark(Icons.Filled.FiberManualRecord, neutral, name)
        place is TripPlaces.Outcome.Held ->
            EndMark(place.place.placeCategory.discIcon, placeDiscStyle(place.place.placeCategory), name)
        name != null -> EndMark(Icons.Filled.Place, neutral, name)
        else -> EndMark(Icons.Outlined.Place, neutral, null)
    }
}

/** The end still missing a pin, the origin first, or null with both placed. */
private fun nextToPlace(origin: TripEnd, destination: TripEnd): ManualPath.End? = when {
    origin.pin == null -> ManualPath.End.ORIGIN
    destination.pin == null -> ManualPath.End.DESTINATION
    else -> null
}

/** Where a spot is, as this form says it throughout: the atlas's city and its country spelled
 *  out. One spelling, so a place named in the results list and the same place named on an end's
 *  card cannot read as two different answers. */
@Composable
private fun localityLabel(city: CityAtlas.City): String =
    "${city.name}, ${countryDisplayName(city.country)}"

/**
 * One end of the trip as the timeline row of the stay it will leave or arrive at. Its date and time
 * wait for the pin, because until one is placed there is no zone to read them in.
 */
@Composable
private fun TripEndCard(
    shape: RoundedCornerShape,
    mark: EndMark,
    /** Which end this is, with the stay the timeline will show beside it. */
    label: String,
    end: TripEnd,
    city: CityAtlas.City?,
    zone: ZoneId,
    active: Boolean,
    onCenter: () -> Unit,
    /** Makes this end the one search, the saved places and a long-press place. */
    onPick: () -> Unit,
    onEditDate: () -> Unit,
    /** The days this end's date picker offers, a single one leaving nothing to pick. */
    days: ClosedRange<LocalDate>?,
    onEditTime: () -> Unit,
    canEstimate: Boolean,
    onEstimate: () -> Unit,
    onRestore: () -> Unit,
) {
    val pin = end.pin
    val dayFixed = days != null && days.start == days.endInclusive
    // The time an estimate along the road stands in for until restored.
    val ownMs = end.own.epochIn(zone)
    val title = when {
        mark.name != null -> mark.name
        city != null -> localityLabel(city)
        pin != null -> stringResource(R.string.addtrip_pin_placed)
        else -> stringResource(R.string.addtrip_no_pin)
    }
    val unset = pin == null
    Card(
        onClick = onCenter,
        shape = shape,
        colors = rowCardColors(outlined = unset),
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else rowCardBorder(outlined = unset),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            IconDisc(mark.icon, mark.disc, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            color = statedTitleColor(mark.name != null),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!active) {
                        IconButton(onClick = onPick) {
                            Icon(
                                Icons.Outlined.EditLocationAlt,
                                contentDescription = stringResource(R.string.addtrip_change_place),
                            )
                        }
                    }
                }
                // Rendered off the same instant the row will store, in the app's own formats — the
                // year included, because a hand-entered trip is usually months old — and the time
                // marked with its shift from the reader's clock, exactly as the timeline will mark
                // the committed trip's.
                val at = end.epochIn(zone)
                val reader = timelineZone()
                val shiftColor = zoneShiftColor
                val estimated = end.coupledMs != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val date = end.dateIn(zone)
                    AssistChip(
                        onClick = onEditDate,
                        enabled = pin != null && !dayFixed,
                        label = {
                            Text(date?.format(compactDayYearFormat) ?: stringResource(R.string.addtrip_pick_date))
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = onEditTime,
                        enabled = pin != null,
                        label = {
                            Text(
                                at?.let { markedTime(it, zone, reader, shiftColor, LocalReaderClock.current) }
                                    ?: AnnotatedString(stringResource(R.string.addtrip_pick_time)),
                            )
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    // An estimate over no time of the end's own has nothing to restore.
                    if (if (estimated) ownMs != null else canEstimate) {
                        TextButton(
                            onClick = if (estimated) onRestore else onEstimate,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (estimated) R.string.addtrip_restore_time else R.string.addtrip_estimate,
                                ),
                            )
                        }
                    }
                }
                // Shown with the time it replaced, which is what Restore gives back.
                if (estimated) {
                    Text(
                        ownMs?.let {
                            annotatedStringResource(
                                R.string.addtrip_estimated_was,
                                markedTime(it, zone, reader, shiftColor, LocalReaderClock.current),
                            )
                        } ?: AnnotatedString(stringResource(R.string.addtrip_estimated)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/** The trip as its timeline track row will read once saved. */
@Composable
private fun TripLegRow(
    shape: RoundedCornerShape,
    activity: ActivityType?,
    /** Frames the whole trip, as tapping an end's card centers on that end. */
    onShow: () -> Unit,
    onChangeType: () -> Unit,
    lengthM: Double?,
    departMs: Long?,
    departZone: ZoneId,
    arriveMs: Long?,
    arriveZone: ZoneId,
) {
    val name = activity?.let { stringResource(it.labelRes) }
    // A track always has both ends.
    val span = if (departMs != null && arriveMs != null) {
        val length = if (arriveMs > departMs) durationText(departMs, arriveMs) else null
        trackRowSpan(departMs, departZone, arriveMs, arriveZone, length)
    } else {
        null
    }
    ListRowCard(
        shape = shape,
        icon = activityIcon(activity),
        disc = activityDiscStyle(activity),
        iconDescription = name,
        title = name?.let { trackRowTitle(it, lengthM) } ?: stringResource(R.string.addtrip_no_type),
        titleColor = statedTitleColor(stated = activity != null),
        subtitle = span,
        onClick = onShow,
        outlined = activity == null,
        trailing = {
            // Into the row's end padding by its touch halo, so the glyph keeps the row's 16 dp inset.
            IconButton(onClick = onChangeType, modifier = Modifier.offset(x = touchHalo(GlyphSize))) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.addtrip_change_type))
            }
        },
    )
}
