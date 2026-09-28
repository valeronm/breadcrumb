package io.github.valeronm.breadcrumb.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
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
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.location.DeviceLocation
import kotlinx.coroutines.launch

@Composable
internal fun BoxScope.MapCornerControls(
    aiming: Boolean,
    /** What the crosshair's button is for on this map, said to a screen reader. */
    aimDescription: String,
    /** What confirming the crosshair does on this map. */
    confirmLabel: String,
    onAim: () -> Unit,
    onCancelAim: () -> Unit,
    onConfirmAim: () -> Unit,
) {
    CornerSlot {
        if (aiming) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp - touchHalo(SmallFabSize) * 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallFloatingActionButton(onClick = onCancelAim, containerColor = MaterialTheme.colorScheme.surface) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
                }
                // A small FAB's height, touch target and shape, so it matches the one beside it.
                ExtendedFloatingActionButton(
                    onClick = onConfirmAim,
                    modifier = Modifier.minimumInteractiveComponentSize().height(SmallFabSize),
                    shape = FloatingActionButtonDefaults.smallShape,
                    icon = { Icon(Icons.Filled.Check, contentDescription = null) },
                    text = { Text(confirmLabel) },
                )
            }
        } else {
            SmallFloatingActionButton(onClick = onAim, containerColor = MaterialTheme.colorScheme.surface) {
                Icon(Icons.Filled.PushPin, contentDescription = aimDescription)
            }
        }
    }
}

/** Takes the same corner as [MapCornerControls], so a map carries one or the other. */
@Composable
internal fun BoxScope.MyLocationControl(location: MyLocationState) {
    if (!location.available) return
    CornerSlot {
        SmallFloatingActionButton(onClick = location::goThere, containerColor = MaterialTheme.colorScheme.surface) {
            if (location.locating) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.places_my_location))
            }
        }
    }
}

/**
 * Bottom-right, clear of the filter chip top-left, the compass top-right and the attribution
 * bottom-left.
 */
@Composable
private fun BoxScope.CornerSlot(content: @Composable () -> Unit) {
    Box(Modifier.align(Alignment.BottomEnd).padding(MapInset - touchHalo(SmallFabSize))) { content() }
}

/** Material's small FAB container, which its library keeps internal. */
private val SmallFabSize = 40.dp

/**
 * The cross at the map's middle, where the pin would land. A long press drops a pin under a
 * fingertip, which covers the very spot it aims at; the cross stays visible while the map moves
 * under it, at any zoom. Drawn light over dark so it reads on either basemap.
 */
@Composable
internal fun BoxScope.AimCrosshair() {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.align(Alignment.Center).size(44.dp)) {
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

@Composable
internal fun AimHint(modifier: Modifier = Modifier) {
    LegendSurface(modifier) {
        Text(stringResource(R.string.places_pin_aim_hint), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The phone's position on a map, known only once the reader asks to go there ([goTo]), since a
 * position cached by any provider can be hours old.
 */
internal class MyLocationState(
    /** False without a location grant — then the button is not offered. */
    val available: Boolean,
    private val fetch: suspend () -> Coordinate?,
    private val launch: (suspend () -> Unit) -> Unit,
    private val onUnavailable: () -> Unit,
) {
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
            fetch = { DeviceLocation.current(context) },
            launch = { block -> scope.launch { block() } },
            onUnavailable = { Toast.makeText(context, R.string.places_no_location, Toast.LENGTH_SHORT).show() },
        )
    }
}
