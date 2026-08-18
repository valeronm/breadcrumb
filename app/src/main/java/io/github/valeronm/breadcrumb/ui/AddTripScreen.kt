package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.OnlinePlaceSearch
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.CityAtlas
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.PlaceSearch
import io.github.valeronm.breadcrumb.domain.pin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

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

/** Where an empty end's pickers open: noon of [day] when the Timeline supplied the day the user
 *  was looking at, today's noon with nothing better to go on. */
private fun noonOf(day: LocalDate?, zone: ZoneId): ZonedDateTime =
    (day ?: LocalDate.now(zone)).atTime(12, 0).atZone(zone)

/**
 * What the form opens holding. The Timeline's top bar knows only which day was on screen; a gap
 * row knows the places the absence lies between and when it ran, and hands over whichever of its
 * two ends that row itself speaks for — both where the absence sat inside one day, otherwise only
 * the end on its own side of the cut, since the other is a fact about a different day's row and
 * would arrive here as an unread guess.
 */
internal class TripDraft(
    /** The day an end with no time of its own opens its pickers on. */
    val day: LocalDate?,
    val origin: TripDraftEnd? = null,
    val destination: TripDraftEnd? = null,
    /** Set where the form is restating a trip that already exists — see [EditedTrip]. */
    val editing: EditedTrip? = null,
)

/**
 * The manual track a form was opened on: its row is rewritten in place rather than a new one
 * inserted, and [activity] — what it currently says it was — is the type the chips open on. That is
 * the one exception to the form's rule that nothing preselects a type: an existing trip already
 * carries an answer, and offering it as unset would ask the user to re-state what they are not here
 * to change.
 */
internal class EditedTrip(val trackId: Long, val activity: ActivityType?)

/**
 * One end a caller can already fill in.
 *
 * [timeMs] is an instant rather than a wall clock, because neither half of a wall clock is
 * available at the point one would be built: the zone comes from resolving the pin, which the form
 * does after it opens, and the pickers' minute resolution would round the bound off the
 * neighbouring track — a departure rounded back past that track's last fix overlaps it, and the
 * insert refuses an overlap. Held exactly until a picked time replaces it, so a trip committed as
 * drafted meets the recording on either side of the absence rather than a minute inside it.
 */
internal class TripDraftEnd(
    /** Null where the caller knows when but not where — a gap side the recorder never got a fix for. */
    val at: Coordinate?,
    /** The user's own name for the spot, never a derived one — see [TripEnd.placeName]. */
    val placeName: String?,
    val timeMs: Long,
)

/** One end of the trip being entered — a pin and a time, each settable in any order, and either of
 *  them possibly already known to whoever opened the form ([TripDraftEnd]). */
private class TripEnd(drafted: TripDraftEnd?) {
    var pin by mutableStateOf(drafted?.at)

    /** The name the pin was picked *by* — a saved place's label, or an online hit's name — shown
     *  on the end's card, and what a place created from this end on commit will be called (a spot
     *  an existing place already covers creates nothing, which is what makes carrying a saved
     *  place's label here safe). Null for a hand-placed pin, which has no name to give, and for a
     *  picked *city*, which must not become a 150 m capture circle at its centroid. */
    var placeName by mutableStateOf(drafted?.placeName)
    var date by mutableStateOf<LocalDate?>(null)
    var time by mutableStateOf<LocalTime?>(null)

    private val draftedAt = drafted?.timeMs

    /** The instant this end names — its wall clock read in [zone] once one is set, until then the
     *  one it was drafted with (see [TripDraftEnd]); null when it holds no time at all. */
    fun epochIn(zone: ZoneId): Long? {
        val d = date ?: return draftedAt
        val t = time ?: return draftedAt
        return ZonedDateTime.of(d, t, zone).toInstant().toEpochMilli()
    }

    /** Where this end's pickers open: on the time it already holds, else [fallback]. */
    fun pickerStart(zone: ZoneId, fallback: ZonedDateTime): ZonedDateTime =
        epochIn(zone)?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: fallback
}

/** One end's time being edited: which end the pickers write to, under which name, on whose clock,
 *  and where they open when the end holds nothing yet ([fallback]). Carried as values so the
 *  dialog never has to recover an end's role by identity. */
private class TimeEdit(
    val end: TripEnd,
    /** The picker's heading, whole rather than a noun the dialog appends "time" to. */
    @StringRes val titleRes: Int,
    val zone: ZoneId,
    val fallback: ZonedDateTime,
)

/**
 * Enter a trip nothing recorded — a flight, a leg with the phone dead — as two pins and two times,
 * inserted as a two-point manual track. Each end's typed time is read in the IANA zone of the city
 * the atlas resolves its pin to (shown on the end's card, never silently applied), so a past
 * flight's times can be entered as the boarding pass states them; with no pin placed yet the time
 * row stays disabled rather than guessing a zone. Everything is local until the check mark commits,
 * so backing out discards by construction — the [PlaceEditScreen] pattern, including the
 * screen-local snackbar host (the app's sits under this layer).
 *
 * What the form opens holding is [draft]'s — the ends a gap row could already fill in, or nothing
 * but the day the Timeline was showing. **It is also how a manual trip is edited**
 * ([TripDraft.editing]): the same two pins and two times, rewritten onto the row they came from,
 * since what a hand-entered trip *is* and what the form asks for are the same thing.
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
    // Unset unless the trip already exists (see [EditedTrip]). How a missing leg was made is the one
    // thing nothing here can work out — the two ends are known on the gap path and say nothing about
    // it — so a default would be a guess the user commits without reading.
    var activity by remember(draft) { mutableStateOf(draft.editing?.activity) }
    // Which end the next long-press (or quick-pick) places: the one still missing a pin, and the
    // origin where both need one. Placing the origin advances to the destination once, so the
    // common path is two presses with no toggling.
    var placingDestination by remember(draft) {
        mutableStateOf(origin.pin != null && destination.pin == null)
    }
    var editing by remember { mutableStateOf<TimeEdit?>(null) }
    var editingDate by remember { mutableStateOf<LocalDate?>(null) }
    var centerRequest by remember(draft) { mutableStateOf<MapCenterRequest?>(null) }
    // Where the map is looking, as of its last settle — what the place search sorts around. Keyed
    // like the ends beside it: a second form opening while the first animates out gets a map of its
    // own, and would otherwise sort around wherever that one was left.
    var mapCenter by remember(draft) { mutableStateOf<Coordinate?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val originCity by produceState<CityAtlas.City?>(null, origin.pin) {
        value = origin.pin?.let { viewModel.cityAt(it) }
    }
    val destinationCity by produceState<CityAtlas.City?>(null, destination.pin) {
        value = destination.pin?.let { viewModel.cityAt(it) }
    }
    val originZone = zoneOrDevice(originCity?.zoneId)
    val destinationZone = zoneOrDevice(destinationCity?.zoneId)
    val departMs = origin.epochIn(originZone)
    val arriveMs = destination.epochIn(destinationZone)
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
    fun placePin(at: Coordinate, placeName: String? = null) {
        // Placing a pin — from a search result, a map pin or a long press — is done with the
        // field: drop its focus and the keyboard with it, or the results list closes onto a
        // keyboard covering the cards it just filled in.
        focusManager.clearFocus()
        keyboard?.hide()
        val end = if (placingDestination) destination else origin
        end.pin = at
        end.placeName = placeName
        if (end === origin && destination.pin == null) placingDestination = true
    }

    // Tapping an end makes it the one the map places, and where it already has a pin takes the
    // camera there: the end you can see is the one you are navigating away from to find the other,
    // and on a form opened from a gap it is the only ground on screen worth starting from. Asked
    // again on every tap, including a tap on the end that is already active — panning away and
    // tapping the card is the way back.
    fun activate(end: TripEnd) {
        placingDestination = end === destination
        end.pin?.let { centerRequest = MapCenterRequest(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            type,
                            TrackListViewModel.ManualTripEnd(
                                TrackRepository.ManualEnd(from, departMs),
                                origin.placeName,
                            ),
                            TrackListViewModel.ManualTripEnd(
                                TrackRepository.ManualEnd(to, arriveMs),
                                destination.placeName,
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
                                scope.launch { snackbarHostState.showSnackbar(refusal) }
                            }
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (type in MANUAL_TRIP_TYPES) {
                    FilterChip(
                        selected = activity == type,
                        onClick = { activity = type },
                        label = { Text(stringResource(type.labelRes)) },
                        leadingIcon = {
                            Icon(activityIcon(type), contentDescription = null, Modifier.size(18.dp))
                        },
                    )
                }
            }
            var query by remember { mutableStateOf("") }
            val savedPlaces by viewModel.storedPlaces.collectAsStateWithLifecycle()
            val placeField = remember(savedPlaces) {
                savedPlaces.map { place ->
                    OverviewPlace(
                        marker = PlaceMarker(place.pin, place),
                        key = PlaceResolver.keyOf(place.id),
                    )
                }
            }
            // Pre-folded once per list — PlaceSearch's contract for filtering per keystroke.
            val foldedLabels = remember(savedPlaces) { savedPlaces.map { PlaceSearch.fold(it.label) } }
            // Nearest what the map is looking at first: a name is rarely unique across a history —
            // "Home", "Hotel", the same shop in three cities — and the ground on screen is the only
            // thing said about which one is meant. Falls back to the table's order until the map has
            // settled once, which is the order this list had before there was anything to sort by.
            val placeMatches = remember(query, savedPlaces, foldedLabels, mapCenter) {
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
            val onlineHits by produceState(emptyList<OnlinePlaceSearch.Hit>(), query) {
                value = if (query.isBlank()) {
                    emptyList()
                } else {
                    // A longer settle than the local scans: this one puts the query on the wire.
                    delay(400)
                    viewModel.searchOnline(query, near = origin.pin ?: destination.pin)
                }
            }
            PlacesSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.addtrip_search_placeholder),
                modifier = Modifier.fillMaxWidth(),
            )
            Card(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    MapLibreTripMap(
                        origin = origin.pin,
                        destination = destination.pin,
                        places = placeField,
                        onLongPress = ::placePin,
                        onPlaceTap = ::placePin,
                        center = centerRequest,
                        onCenterSettled = { mapCenter = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    LegendSurface(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                        Text(
                            stringResource(
                                if (placingDestination) {
                                    R.string.addtrip_tap_destination
                                } else {
                                    R.string.addtrip_tap_origin
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    // Floating over the map rather than in the column: results coming and going
                    // must not resize the MapView underneath (see PlaceEditScreen on why a resized
                    // map is unfixable).
                    if (query.isNotBlank()) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                                for (place in placeMatches) {
                                    PinSearchResult(
                                        icon = Icons.Filled.Place,
                                        label = place.label,
                                        detail = placeCities[place.id]?.let { localityLabel(it) },
                                    ) {
                                        placePin(place.pin, place.label)
                                        query = ""
                                    }
                                }
                                for (hit in cityHits) {
                                    PinSearchResult(
                                        icon = Icons.Filled.LocationCity,
                                        label = hit.name,
                                        detail = countryDisplayName(hit.country),
                                    ) {
                                        placePin(Coordinate(hit.lat, hit.lon))
                                        query = ""
                                    }
                                }
                                // Below the local sections, minus what they already show — the
                                // geocoder returns big cities too, and "Lisbon" twice reads as a
                                // glitch, not as two sources. Deduped against itself as well:
                                // Photon hands back distinct OSM objects (a suburb, its station)
                                // whose rows would read identically.
                                val online = remember(cityHits, onlineHits) {
                                    val cities = cityHits.map { PlaceSearch.fold(it.name) }.toSet()
                                    onlineHits.filter { PlaceSearch.fold(it.name) !in cities }
                                        .distinctBy {
                                            PlaceSearch.fold(it.name) to PlaceSearch.fold(it.locality.orEmpty())
                                        }
                                }
                                for (hit in online) {
                                    PinSearchResult(
                                        icon = Icons.Filled.TravelExplore,
                                        label = hit.name,
                                        detail = hit.locality,
                                    ) {
                                        // The one pick that knows its own name — carried so the
                                        // commit can create the place this end will have stayed at.
                                        placePin(Coordinate(hit.lat, hit.lon), hit.name)
                                        query = ""
                                    }
                                }
                                if (online.isNotEmpty()) {
                                    // ODbL's credit, at the results it applies to.
                                    Text(
                                        stringResource(R.string.addtrip_osm_credit),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    )
                                }
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
                    }
                }
            }
            TripEndCard(
                titleRes = R.string.addtrip_origin,
                atRes = R.string.addtrip_departure_at,
                setTimeRes = R.string.addtrip_set_departure,
                end = origin,
                city = originCity,
                zone = originZone,
                active = !placingDestination,
                onActivate = { activate(origin) },
                onEditTime = {
                    editing = TimeEdit(
                        origin, R.string.addtrip_departure_time_title, originZone,
                        origin.pickerStart(originZone, noonOf(draft.day, originZone)),
                    )
                },
            )
            TripEndCard(
                titleRes = R.string.addtrip_destination,
                atRes = R.string.addtrip_arrival_at,
                setTimeRes = R.string.addtrip_set_arrival,
                end = destination,
                city = destinationCity,
                zone = destinationZone,
                active = placingDestination,
                onActivate = { activate(destination) },
                onEditTime = {
                    // An hour past the departure, on the arrival's own clock: the pickers open a
                    // short scroll from any real arrival instead of at an arbitrary noon.
                    editing = TimeEdit(
                        destination, R.string.addtrip_arrival_time_title, destinationZone,
                        destination.pickerStart(
                            destinationZone,
                            departMs?.let { Instant.ofEpochMilli(it + HOUR_MS).atZone(destinationZone) }
                                ?: noonOf(draft.day, destinationZone),
                        ),
                    )
                },
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    editing?.let { edit ->
        val end = edit.end
        val closeDialogs = {
            editing = null
            editingDate = null
        }
        // Keyed on the end being edited: the picker states live at one call site, and without the
        // key, opening the arrival picker would show whatever the departure picker last held.
        key(end) {
            val chosenDate = editingDate
            if (chosenDate == null) {
                LocalDateDialog(
                    initial = end.date ?: edit.fallback.toLocalDate(),
                    confirmLabel = stringResource(R.string.addtrip_next),
                    onConfirm = { editingDate = it },
                    onDismiss = closeDialogs,
                )
            } else {
                val initial = end.time ?: edit.fallback.toLocalTime()
                val timeState = rememberTimePickerState(
                    initialHour = initial.hour,
                    initialMinute = initial.minute,
                )
                TimePickerDialog(
                    onDismissRequest = closeDialogs,
                    title = { Text(stringResource(edit.titleRes)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                end.date = chosenDate
                                end.time = LocalTime.of(timeState.hour, timeState.minute)
                                closeDialogs()
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

/** One row of the pin search's results: a saved place or an atlas city, tapped to become the
 *  active end's pin. */
@Composable
private fun PinSearchResult(
    icon: ImageVector,
    label: String,
    detail: String?,
    onPick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            // A floor rather than more padding: a row carrying a locality line under its name is
            // tall enough already, and one without it would otherwise stand at two thirds of a
            // finger — in a stacked, scrolling list, which is where a mis-hit picks the wrong pin.
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        // Stacked, not side by side: a hotel's name and a spelled-out locality routinely overrun
        // one line between them, and two texts sharing a row collide instead of wrapping.
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Where a spot is, as this form says it throughout: the atlas's city and its country spelled
 *  out. One spelling, so a place named in the results list and the same place named on an end's
 *  card cannot read as two different answers. */
@Composable
private fun localityLabel(city: CityAtlas.City): String =
    "${city.name}, ${countryDisplayName(city.country)}"

/**
 * One end of the trip: which city (and so whose clock) its pin resolved to, and its time row.
 * Tapping the card makes it the end the map places; the active one wears the primary border. The
 * time row waits for the pin, because until one is placed there is no zone to read the time in.
 */
@Composable
private fun TripEndCard(
    @StringRes titleRes: Int,
    /** "Departure %s, " — prefixes the date, ahead of a clock time this card draws itself. */
    @StringRes atRes: Int,
    /** The whole invitation ("Set departure time"), not a noun something appends "time" to. */
    @StringRes setTimeRes: Int,
    end: TripEnd,
    city: CityAtlas.City?,
    zone: ZoneId,
    active: Boolean,
    onActivate: () -> Unit,
    onEditTime: () -> Unit,
) {
    val pin = end.pin
    // What the pin was picked by outranks where it resolved to: "JFK Airport" says more than the
    // city holding it, and the zone line below still names the clock.
    val pickedName = end.placeName
    val locality = when {
        pickedName != null -> pickedName
        city != null -> localityLabel(city)
        pin != null -> stringResource(R.string.addtrip_pin_placed)
        else -> stringResource(R.string.addtrip_no_pin)
    }
    OutlinedCard(
        onClick = onActivate,
        border = if (active) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            CardDefaults.outlinedCardBorder()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(titleRes), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    locality,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Rendered off the same instant the row will store, in the app's own formats — the
            // year included, because a hand-entered trip is usually months old — and the time
            // marked with its shift from the reader's clock, exactly as the timeline will mark
            // the committed trip's.
            val at = end.epochIn(zone)
            val reader = timelineZone()
            val shiftColor = zoneShiftColor
            // The line is a control, not a caption — it opens both pickers — so it takes a row's
            // worth of height and a bounded ripple rather than the glyph box a clickable Text
            // would leave for a finger.
            Text(
                if (at != null) {
                    annotatedStringResource(
                        atRes,
                        at.toLocalDate(zone).format(compactDayYearFormat),
                        markedTime(at, zone, reader, shiftColor, LocalReaderClock.current),
                    )
                } else {
                    AnnotatedString(stringResource(setTimeRes))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (pin != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = pin != null, role = Role.Button, onClick = onEditTime)
                    .heightIn(min = 48.dp)
                    .wrapContentHeight(),
            )
            // Which clock the pickers will write on, said before a time exists to carry the
            // mark — as an offset from the reader's own, the way the timeline says it, never as
            // a zone id. Gone once a time is set (the superscript above takes over), and absent
            // entirely on the reader's own clock, where there is nothing to warn about.
            if (pin != null && at == null) {
                zoneShiftLabel(System.currentTimeMillis(), zone, reader)?.let { shift ->
                    Text(
                        stringResource(R.string.addtrip_local_clock, shift),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
