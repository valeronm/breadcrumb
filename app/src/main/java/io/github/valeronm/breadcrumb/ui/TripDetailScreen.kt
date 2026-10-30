package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Clocks
import io.github.valeronm.breadcrumb.domain.RoutePlaces
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.Trip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * A trip of several parts as one: its span, its figures and every part on one map. What reads one
 * track's fixes — the metric, the graph, a cut, an edit — is on that part's own page.
 */
@Composable
internal fun TripDetailScreen(
    trip: Trip,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onOpenPart: (Long) -> Unit,
    /** Delete every part of the trip; the caller offers the undo and closes this screen. */
    onDelete: () -> Unit,
) {
    val first = trip.parts.first()
    val last = trip.parts.last()
    val reader = timelineZone()
    val zones by produceState(Clocks.both(reader), trip.parts.map { it.id }) {
        value = viewModel.zonesOfTrip(trip)
    }
    // Keyed on the parts' ids and ends, not the parts: the track list re-emits for changes these
    // fixes do not follow.
    val parts by produceState<List<TripPart>?>(null, trip.parts.map { it.id to it.endedAt }) {
        value = withContext(Dispatchers.Default) {
            trip.parts.map { part ->
                async { TripPart(viewModel.getTrackPoints(part.id).good, TrackOrigin.impliesMotion(part.source)) }
            }.awaitAll()
        }
    }
    val storedPlaces by viewModel.storedPlaces.collectAsStateWithLifecycle()
    val route = remember(parts, storedPlaces, first.startPlaceId, last.endPlaceId) {
        RoutePlaces.route(
            listOfNotNull(parts?.first()?.points?.firstOrNull(), parts?.last()?.points?.lastOrNull()),
            storedPlaces,
            AndroidDistance,
            startPlaceId = first.startPlaceId,
            endPlaceId = last.endPlaceId,
        )
    }
    val activity = remember(trip) { ActivityType.ofName(trip.main.activityType) }
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(activityLabel(LocalContext.current, trip.main.activityType)) },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.trip_parts))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            TripPartItems(trip) { id ->
                                menuOpen = false
                                onOpenPart(id)
                            }
                        }
                    }
                    // A trip still being recorded into has no settled rows to delete.
                    if (trip.endedAt != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete))
                        }
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize().clipToBounds()) {
            val loaded = parts
            if (loaded == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TripSpanRow(trip, zones)
                    Card(Modifier.fillMaxWidth()) { TrackStatsHeader(trip) }
                    Card(Modifier.weight(1f).fillMaxWidth()) {
                        MapLibreTripMapOfParts(
                            parts = loaded,
                            activity = activity,
                            endPlaces = route.places,
                            endLinks = route.links,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TripPartItems(trip: Trip, onOpenPart: (Long) -> Unit) {
    for (part in trip.parts) {
        DropdownMenuItem(
            // By length rather than clock: the parts run in order, and a join's instant has no
            // clock of its own to be read on.
            text = { Text(distanceText(part.distanceMeters) + " · " + durationText(part.startedAt, part.endedAt)) },
            trailingIcon = part.mark?.let { { OriginGlyph(it) } },
            onClick = { onOpenPart(part.id) },
        )
    }
}
