package io.github.valeronm.breadcrumb.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.location.DeviceLocation
import kotlinx.coroutines.launch
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

/**
 * The place maps' shared corner — the Places map and the place editor carry the same buttons in
 * the same order, so one learned on either works on both. Bottom-right, clear of the filter chip
 * top-left, the compass top-right and the attribution bottom-left, lifted over the zoom readout
 * where dev builds show one. From the corner up: the basemap's light/dark switch, the way to where
 * the phone is, and the crosshair — which is how a pin is placed, wherever the map is looking.
 * Going to the phone's position only moves the map, so the crosshair can then be dropped there or
 * anywhere else the map is moved to.
 *
 * The way to the phone is offered only where [location] is given: on the Places map, where a
 * place is started from nothing. The editor opens on a spot already chosen — a stop the app
 * found, a place, or the crosshair's own pick — so there the button would only lead away from it.
 */
@Composable
internal fun BoxScope.MapCornerControls(
    shade: MapShadeState,
    location: MyLocationState?,
    aiming: Boolean,
    /** What the crosshair's button is for on this map, said to a screen reader. */
    aimDescription: String,
    /** What confirming the crosshair does on this map, e.g. "Put the pin here". */
    confirmLabel: String,
    onAim: () -> Unit,
    onCancelAim: () -> Unit,
    onConfirmAim: () -> Unit,
) {
    Column(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 12.dp, bottom = if (BuildConfig.DEV_TOOLS) 44.dp else 12.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (aiming) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallFloatingActionButton(onClick = onCancelAim, containerColor = MaterialTheme.colorScheme.surface) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
                }
                ExtendedFloatingActionButton(
                    onClick = onConfirmAim,
                    icon = { Icon(Icons.Filled.Check, contentDescription = null) },
                    text = { Text(confirmLabel) },
                )
            }
        } else {
            SmallFloatingActionButton(onClick = onAim, containerColor = MaterialTheme.colorScheme.surface) {
                Icon(Icons.Filled.PushPin, contentDescription = aimDescription)
            }
        }
        if (location != null && location.available) {
            SmallFloatingActionButton(onClick = location::goThere, containerColor = MaterialTheme.colorScheme.surface) {
                if (location.locating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.places_my_location))
                }
            }
        }
        SmallFloatingActionButton(onClick = shade::toggle, containerColor = MaterialTheme.colorScheme.surface) {
            Icon(
                if (shade.dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                contentDescription = stringResource(
                    if (shade.dark) R.string.places_map_light else R.string.places_map_dark,
                ),
            )
        }
    }
}

/**
 * The crosshair while it is up: a cross at the map's middle, where the pin would land, and a line
 * saying how to aim it. A long press drops a pin under a fingertip, which covers the very spot it
 * aims at; the cross stays visible while the map moves under it, at any zoom.
 */
@Composable
internal fun BoxScope.AimOverlay() {
    Crosshair(Modifier.align(Alignment.Center))
    LegendSurface(Modifier.align(Alignment.TopCenter).padding(top = 56.dp)) {
        Text(stringResource(R.string.places_pin_aim_hint), style = MaterialTheme.typography.labelSmall)
    }
}

/** A cross drawn light over dark so it reads on either basemap. */
@Composable
private fun Crosshair(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(44.dp)) {
        val c = size.width / 2
        val gap = 5.dp.toPx()
        for ((color, width) in listOf(Color.White to 5.dp.toPx(), ink to 2.dp.toPx())) {
            drawLine(color, Offset(c, 0f), Offset(c, c - gap), width, StrokeCap.Round)
            drawLine(color, Offset(c, c + gap), Offset(c, size.height), width, StrokeCap.Round)
            drawLine(color, Offset(0f, c), Offset(c - gap, c), width, StrokeCap.Round)
            drawLine(color, Offset(c + gap, c), Offset(size.width, c), width, StrokeCap.Round)
        }
    }
}

/**
 * The place maps' light/dark choice, shared by every map about places and kept in [AppSettings];
 * with nothing picked it follows the app theme. A flip builds a new map, so it carries the camera
 * across ([camera]) and the map stays where it was.
 */
internal class MapShadeState(
    private val picked: MutableState<Boolean?>,
    themeDark: Boolean,
    val camera: CameraCarry,
    private val persist: (Boolean) -> Unit,
) {
    val dark: Boolean = picked.value ?: themeDark

    fun toggle() {
        val next = !dark
        camera.carryToNextMap()
        picked.value = next
        persist(next)
    }
}

/** An unset pick is not saved with the screen, since the theme it stands for can change under it. */
@Composable
internal fun rememberMapShade(): MapShadeState {
    val context = LocalContext.current
    val picked = remember { mutableStateOf(AppSettings.placesMapDark(context)) }
    val camera = remember { CameraCarry() }
    return MapShadeState(picked, isSystemInDarkTheme(), camera) { AppSettings.setPlacesMapDark(context, it) }
}

/**
 * The phone's position on a place map: the last one known on open, which costs nothing, and one
 * fresh fix when the reader asks to go there — which moves the map ([goTo]) and nothing else.
 */
internal class MyLocationState(
    /** False without a location grant — then the button is not offered. */
    val available: Boolean,
    initial: Coordinate?,
    private val fetch: suspend () -> Coordinate?,
    private val launch: (suspend () -> Unit) -> Unit,
    private val onUnavailable: () -> Unit,
) {
    var position by mutableStateOf(initial)
        private set
    var goTo by mutableStateOf<MapCenterRequest?>(null)
        private set
    var locating by mutableStateOf(false)
        private set

    fun goThere() {
        if (locating) return
        locating = true
        launch {
            val here = fetch()
            locating = false
            if (here == null) {
                onUnavailable()
            } else {
                position = here
                goTo = MapCenterRequest(here)
            }
        }
    }
}

@Composable
internal fun rememberMyLocation(): MyLocationState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember {
        MyLocationState(
            available = DeviceLocation.granted(context),
            initial = DeviceLocation.lastKnown(context),
            fetch = { DeviceLocation.current(context) },
            launch = { block -> scope.launch { block() } },
            onUnavailable = { Toast.makeText(context, R.string.places_no_location, Toast.LENGTH_SHORT).show() },
        )
    }
}
