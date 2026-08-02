package io.github.valeronm.breadcrumb.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.data.TrackRepository
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.CityAtlas
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.PlaceSearch
import io.github.valeronm.breadcrumb.domain.StayDeriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** What the add-trip form offers: carried transport plus the generic — a two-point straight line
 *  says nothing about a walk, and foot journeys are what the recorder is for. */
private val MANUAL_TRIP_TYPES = listOf(
    ActivityType.FLIGHT,
    ActivityType.FERRY,
    ActivityType.TAXI,
    ActivityType.DRIVING,
    ActivityType.UNKNOWN,
)

private const val HOUR_MS = 3_600_000L

/** Where an empty end's pickers open with nothing better to go on. */
private fun noonToday(zone: ZoneId): ZonedDateTime = LocalDate.now(zone).atTime(12, 0).atZone(zone)

/** One end of the trip being entered — a pin and a wall-clock time, each settable in any order. */
private class TripEnd {
    var pin by mutableStateOf<StayDeriver.Endpoint?>(null)
    var date by mutableStateOf<LocalDate?>(null)
    var time by mutableStateOf<LocalTime?>(null)

    /** The instant this end's wall clock names in [zone], or null until date and time are set. */
    fun epochIn(zone: ZoneId): Long? {
        val d = date ?: return null
        val t = time ?: return null
        return ZonedDateTime.of(d, t, zone).toInstant().toEpochMilli()
    }
}

/** One end's time being edited: which end the pickers write to, under which name, on whose clock,
 *  and where they open when the end holds nothing yet ([fallback]). Carried as values so the
 *  dialog never has to recover an end's role by identity. */
private class TimeEdit(val end: TripEnd, val label: String, val zone: ZoneId, val fallback: ZonedDateTime)

/**
 * Enter a trip nothing recorded — a flight, a leg with the phone dead — as two pins and two times,
 * inserted as a two-point manual track. Each end's typed time is read in the IANA zone of the city
 * the gazetteer resolves its pin to (shown on the end's card, never silently applied), so a past
 * flight's times can be entered as the boarding pass states them; with no pin placed yet the time
 * row stays disabled rather than guessing a zone. Everything is local until the check mark commits,
 * so backing out discards by construction — the [PlaceEditScreen] pattern, including the
 * screen-local snackbar host (the app's sits under this layer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTripScreen(
    viewModel: TrackListViewModel,
    onClose: () -> Unit,
) {
    val origin = remember { TripEnd() }
    val destination = remember { TripEnd() }
    var activity by remember { mutableStateOf(ActivityType.FLIGHT) }
    // Which end the next long-press (or quick-pick) places. Placing the origin advances to the
    // destination once, so the common path is two presses with no toggling.
    var placingDestination by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TimeEdit?>(null) }
    var editingDate by remember { mutableStateOf<LocalDate?>(null) }
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
        departMs != null && arriveMs != null && arriveMs <= departMs -> "Arrival must be after departure"
        departMs != null && departMs > System.currentTimeMillis() -> "Departure is in the future"
        else -> null
    }
    // Set times imply set pins: the time row waits for a pin, and a pin cannot be unset.
    val ready = departMs != null && arriveMs != null && error == null

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val placeActivePin = { at: StayDeriver.Endpoint ->
        // Placing a pin — from a search result, a map pin or a long press — is done with the
        // field: drop its focus and the keyboard with it, or the results list closes onto a
        // keyboard covering the cards it just filled in.
        focusManager.clearFocus()
        keyboard?.hide()
        if (placingDestination) {
            destination.pin = at
        } else {
            origin.pin = at
            if (destination.pin == null) placingDestination = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text("Add missing trip") },
                navigationIcon = { BackNavIcon(onClose) },
                actions = {
                    IconButton(
                        onClick = {
                            val from = origin.pin ?: return@IconButton
                            val to = destination.pin ?: return@IconButton
                            if (departMs == null || arriveMs == null) return@IconButton
                            viewModel.addManualTrack(
                                activity,
                                TrackRepository.ManualEnd(from, departMs),
                                TrackRepository.ManualEnd(to, arriveMs),
                            ) { result ->
                                when (result) {
                                    is TrackRepository.ManualInsertResult.Inserted -> onClose()
                                    TrackRepository.ManualInsertResult.Overlapping -> scope.launch {
                                        snackbarHostState.showSnackbar("Overlaps an existing track")
                                    }
                                }
                            }
                        },
                        enabled = ready,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Done")
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
                        label = { Text(type.label) },
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
                        marker = PlaceMarker(StayDeriver.Endpoint(place.lat, place.lon), place),
                        key = PlaceResolver.keyOf(place.id),
                    )
                }
            }
            // Pre-folded once per list — PlaceSearch's contract for filtering per keystroke.
            val foldedLabels = remember(savedPlaces) { savedPlaces.map { PlaceSearch.fold(it.label) } }
            val placeMatches = remember(query, savedPlaces, foldedLabels) {
                val needle = PlaceSearch.fold(query)
                if (needle.isEmpty()) {
                    emptyList()
                } else {
                    savedPlaces.filterIndexed { i, _ -> foldedLabels[i].contains(needle) }
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
            PlacesSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search cities and places",
                modifier = Modifier.fillMaxWidth(),
            )
            Card(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    MapLibreTripMap(
                        origin = origin.pin,
                        destination = destination.pin,
                        places = placeField,
                        onLongPress = placeActivePin,
                        onPlaceTap = placeActivePin,
                        modifier = Modifier.fillMaxSize(),
                    )
                    LegendSurface(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                        Text(
                            "Tap a place or long-press to set the " +
                                if (placingDestination) "destination" else "origin",
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
                                        detail = null,
                                    ) {
                                        placeActivePin(StayDeriver.Endpoint(place.lat, place.lon))
                                        query = ""
                                    }
                                }
                                for (hit in cityHits) {
                                    PinSearchResult(
                                        icon = Icons.Filled.LocationCity,
                                        label = hit.name,
                                        detail = countryDisplayName(hit.country),
                                    ) {
                                        placeActivePin(StayDeriver.Endpoint(hit.lat, hit.lon))
                                        query = ""
                                    }
                                }
                                if (placeMatches.isEmpty() && cityHits.isEmpty()) {
                                    Text(
                                        "No matches",
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
                title = "Origin",
                timeLabel = "Departure",
                end = origin,
                city = originCity,
                zone = originZone,
                active = !placingDestination,
                onActivate = { placingDestination = false },
                onEditTime = {
                    editing = TimeEdit(origin, "Departure", originZone, noonToday(originZone))
                },
            )
            TripEndCard(
                title = "Destination",
                timeLabel = "Arrival",
                end = destination,
                city = destinationCity,
                zone = destinationZone,
                active = placingDestination,
                onActivate = { placingDestination = true },
                onEditTime = {
                    // An hour past the departure, on the arrival's own clock: the pickers open a
                    // short scroll from any real arrival instead of at an arbitrary noon.
                    editing = TimeEdit(
                        destination, "Arrival", destinationZone,
                        departMs?.let { Instant.ofEpochMilli(it + HOUR_MS).atZone(destinationZone) }
                            ?: noonToday(destinationZone),
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
                val dateState = rememberDatePickerState(
                    initialSelectedDateMillis = (end.date ?: edit.fallback.toLocalDate())
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                )
                DatePickerDialog(
                    onDismissRequest = closeDialogs,
                    confirmButton = {
                        TextButton(
                            onClick = {
                                editingDate = dateState.selectedDateMillis?.let {
                                    Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()
                                }
                            },
                            enabled = dateState.selectedDateMillis != null,
                        ) { Text("Next") }
                    },
                    dismissButton = { TextButton(onClick = closeDialogs) { Text("Cancel") } },
                ) { DatePicker(dateState) }
            } else {
                val initial = end.time ?: edit.fallback.toLocalTime()
                val timeState = rememberTimePickerState(
                    initialHour = initial.hour,
                    initialMinute = initial.minute,
                )
                TimePickerDialog(
                    onDismissRequest = closeDialogs,
                    title = { Text("${edit.label} time") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                end.date = chosenDate
                                end.time = LocalTime.of(timeState.hour, timeState.minute)
                                closeDialogs()
                            },
                        ) { Text("OK") }
                    },
                    dismissButton = { TextButton(onClick = closeDialogs) { Text("Cancel") } },
                ) { TimePicker(timeState) }
            }
        }
    }
}

/** One row of the pin search's results: a saved place or a gazetteer city, tapped to become the
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (detail != null) {
            Spacer(Modifier.weight(1f))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** [countryNameOf] remembered per code — the ICU lookup would otherwise re-run for every result
 *  row on every keystroke — falling back to the raw code where it resolves to nothing. */
@Composable
private fun countryDisplayName(code: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(code, locale) { countryNameOf(code, locale).ifEmpty { code } }
}

/**
 * One end of the trip: which city (and so whose clock) its pin resolved to, and its time row.
 * Tapping the card makes it the end the map places; the active one wears the primary border. The
 * time row waits for the pin, because until one is placed there is no zone to read the time in.
 */
@Composable
private fun TripEndCard(
    title: String,
    timeLabel: String,
    end: TripEnd,
    city: CityAtlas.City?,
    zone: ZoneId,
    active: Boolean,
    onActivate: () -> Unit,
    onEditTime: () -> Unit,
) {
    val pin = end.pin
    val locality = when {
        city != null -> "${city.name}, ${countryDisplayName(city.country)}"
        pin != null -> "Pin placed"
        else -> "No pin yet"
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
                Text(title, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    locality,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Rendered off the same instant the row will store, in the app's own formats — the
            // year included, because a hand-entered trip is usually months old.
            val at = end.epochIn(zone)
            Text(
                if (at != null) {
                    "$timeLabel ${at.toLocalDate(zone).format(compactDayYearFormat)}, ${timeAt(at, zone)}"
                } else {
                    "Set ${timeLabel.lowercase()} time"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (pin != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.clickable(enabled = pin != null, onClick = onEditTime),
            )
            if (pin != null) {
                Text(
                    "Local time: ${zone.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
