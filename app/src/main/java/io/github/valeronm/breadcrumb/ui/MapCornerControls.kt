package io.github.valeronm.breadcrumb.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
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
import io.github.valeronm.breadcrumb.util.LOCATION_PERMISSIONS
import io.github.valeronm.breadcrumb.util.anyPermanentlyDenied
import io.github.valeronm.breadcrumb.util.openAppSettings
import kotlinx.coroutines.launch
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

/** Bottom-right, clear of the filter chip top-left and the compass top-right. */
@Composable
internal fun BoxScope.MapCorner(
    /** Read here rather than by the caller, so a change recomposes this corner alone. */
    away: State<Boolean>,
    onReframe: (() -> Unit)?,
    corner: @Composable () -> Unit,
) {
    Column(
        Modifier.align(Alignment.BottomEnd).padding(MapInset - touchHalo(SmallFabSize)),
        verticalArrangement = Arrangement.spacedBy(8.dp - touchHalo(SmallFabSize) * 2),
        horizontalAlignment = Alignment.End,
    ) {
        if (onReframe != null) {
            AnimatedVisibility(away.value, enter = fadeIn(), exit = fadeOut()) {
                CornerFab(onReframe) {
                    Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.common_map_reset_view))
                }
            }
        }
        corner()
    }
}

@Composable
internal fun PinAimControls(
    aiming: Boolean,
    /** What the crosshair's button is for on this map, said to a screen reader. */
    aimDescription: String,
    /** What confirming the crosshair does on this map. */
    confirmLabel: String,
    onAim: () -> Unit,
    onCancelAim: () -> Unit,
    onConfirmAim: () -> Unit,
) {
    if (aiming) {
        // Stacked rather than side by side: at the credit's height the corner stays one button wide.
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp - touchHalo(SmallFabSize) * 2),
            horizontalAlignment = Alignment.End,
        ) {
            // A small FAB's height, touch target and shape, so it matches the one below it.
            ExtendedFloatingActionButton(
                onClick = onConfirmAim,
                modifier = Modifier.minimumInteractiveComponentSize().height(SmallFabSize),
                shape = FloatingActionButtonDefaults.smallShape,
                icon = { Icon(Icons.Filled.Check, contentDescription = null) },
                text = { Text(confirmLabel) },
            )
            CornerFab(onCancelAim) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
            }
        }
    } else {
        CornerFab(onAim) { Icon(Icons.Filled.PushPin, contentDescription = aimDescription) }
    }
}

@Composable
internal fun MyLocationControl(location: MyLocationState) {
    if (location.showingSettingsDialog) {
        val context = LocalContext.current
        BlockedStepDialog(
            step = SetupStep.LOCATION,
            bodyRes = R.string.places_location_reason,
            onOpenSettings = {
                location.dismissSettings()
                context.openAppSettings()
            },
            onDismiss = location::dismissSettings,
        )
    }
    CornerFab(location::goThere) {
        if (location.locating) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.places_my_location))
        }
    }
}

@Composable
private fun CornerFab(onClick: () -> Unit, content: @Composable () -> Unit) {
    SmallFloatingActionButton(onClick = onClick, containerColor = MaterialTheme.colorScheme.surface, content = content)
}

/** Material's small FAB container, which its library keeps internal. */
private val SmallFabSize = 40.dp

/** How far in from the map's right edge a [MapCorner] control reaches, its inset included. */
internal val CornerControlReach = MapInset + SmallFabSize

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
internal fun ColumnScope.AimHint() {
    MapStackLegend {
        Text(stringResource(R.string.places_pin_aim_hint), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The phone's position on a map, known only once the reader asks to go there ([goTo]), since a
 * position cached by any provider can be hours old. Asking is also what requests location when it
 * is not granted: wanting to see where you are is the intention the permission follows from.
 */
internal class MyLocationState(
    private val granted: () -> Boolean,
    /** Whether Android has stopped putting up its location dialog for this app. */
    private val blocked: () -> Boolean,
    private val fetch: suspend () -> Coordinate?,
    private val launch: (suspend () -> Unit) -> Unit,
    private val onUnavailable: () -> Unit,
) {
    var goTo by mutableStateOf<MapCenterRequest?>(null)
        private set
    var locating by mutableStateOf(false)
        private set

    var showingSettingsDialog by mutableStateOf(false)
        private set

    /** Puts up Android's location dialog, answering through [onAnswered]; a no-op while no
     *  launcher is registered. */
    var ask: () -> Unit = {}

    fun goThere() {
        when {
            locating -> Unit
            granted() -> locate()
            blocked() -> showingSettingsDialog = true
            else -> ask()
        }
    }

    /** A grant finishes the tap that asked for it. */
    fun onAnswered() {
        if (granted()) locate()
    }

    fun dismissSettings() {
        showingSettingsDialog = false
    }

    private fun locate() {
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
internal fun rememberMyLocation(say: (String) -> Unit): MyLocationState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noLocation = stringResource(R.string.places_no_location)
    val location = remember {
        MyLocationState(
            granted = { DeviceLocation.granted(context) },
            blocked = { context.anyPermanentlyDenied(LOCATION_PERMISSIONS, AppSettings.askedPermissions(context)) },
            fetch = { DeviceLocation.current(context) },
            launch = { block -> scope.launch { block() } },
            onUnavailable = { say(noLocation) },
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        location.onAnswered()
    }
    DisposableEffect(launcher) {
        location.ask = {
            // Recorded before the dialog: a process death before the answer would otherwise leave a
            // refusal reading as a permission never asked for.
            AppSettings.markPermissionsAsked(context, LOCATION_PERMISSIONS)
            launcher.launch(LOCATION_PERMISSIONS.toTypedArray())
        }
        onDispose { location.ask = {} }
    }
    return location
}
