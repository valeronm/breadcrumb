package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.Coordinate
import org.maplibre.android.camera.CameraPosition

/** A layer's content is non-null, while [start] is null for a pick opened with no map showing. */
internal class NewPlacePick(val start: CameraPosition?)

/**
 * Every listed place is drawn, whatever the Places tab's filter, so the spot is picked against all
 * of them. Tapping one does nothing: this page is for a spot none of them covers.
 */
@Composable
internal fun NewPlaceScreen(
    viewModel: TrackListViewModel,
    /** Where the map opens; null fits every place. */
    start: CameraPosition?,
    onClose: () -> Unit,
    onPicked: (Coordinate) -> Unit,
) {
    val summaries by viewModel.places.collectAsStateWithLifecycle()
    val places = remember(summaries) {
        summaries.orEmpty().filter { it.isListed }.map { overviewPlaceOf(it) }
    }
    val camera = remember { CameraCarry(start) }
    val myLocation = rememberMyLocation()
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(stringResource(R.string.places_new_place)) },
                navigationIcon = { BackNavIcon(onClose) },
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
            Card(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    MapLibrePlacesMap(
                        places = places,
                        // With no [start], the fit waits for the places, which may still be deriving
                        // on a cold launch.
                        frameKey = start != null || summaries != null,
                        onOpen = {},
                        camera = camera,
                        modifier = Modifier.fillMaxSize(),
                        goTo = myLocation.goTo,
                    )
                    AimCrosshair()
                    AimHint(Modifier.align(Alignment.TopStart).padding(MapInset))
                    MyLocationControl(myLocation)
                }
            }
            Button(
                onClick = { camera.center()?.let(onPicked) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.places_new_here))
            }
        }
    }
}
