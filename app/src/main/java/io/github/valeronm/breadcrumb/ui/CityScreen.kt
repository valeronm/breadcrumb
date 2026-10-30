package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.atlas.AtlasCity
import io.github.valeronm.breadcrumb.domain.atlas.ListedCity

/**
 * Every place the history holds in one city, on a map of its own.
 *
 * It draws all of them, with no chips: a city's places are often an old trip's rare stops from long
 * ago, which the Places map hides until its chips are switched on.
 */
@Composable
internal fun CityScreen(
    city: ListedCity,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onOpenPlace: (String) -> Unit,
) {
    val summaries by viewModel.places.collectAsStateWithLifecycle()
    val mergeableAnchors by viewModel.mergeableAnchors.collectAsStateWithLifecycle()
    val inCity = remember(summaries, city) {
        summaries?.let { placesIn(it, city.city) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(listedCityName(city), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackNavIcon(onBack) },
            )
        },
    ) { padding ->
        if (inCity == null) {
            DerivingState(Modifier.fillMaxSize().padding(padding))
        } else {
            val mapPlaces = remember(inCity, mergeableAnchors) { overviewPlacesOf(inCity, mergeableAnchors) }
            val camera = remember { CameraCarry() }
            MapCard(Modifier.fillMaxSize().padding(padding)) {
                MapLibrePlacesMap(
                    places = mapPlaces,
                    onOpen = onOpenPlace,
                    camera = camera,
                    modifier = Modifier.fillMaxSize(),
                    maxZoom = CITY_MAX_ZOOM,
                )
            }
        }
    }
}

// A fit to places a street apart would otherwise zoom past street level.
private const val CITY_MAX_ZOOM = 15.0

/** Matched by atlas row rather than name, since two towns can share a name. */
internal fun placesIn(places: List<PlaceResolver.PlaceSummary>, city: AtlasCity): List<PlaceResolver.PlaceSummary> =
    places.filter { it.isListed && it.locality?.row == city.row }
