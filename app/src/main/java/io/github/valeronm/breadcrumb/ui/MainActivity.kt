package io.github.valeronm.breadcrumb.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.WindowManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.IgnoreReason
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.Settings as AppSettings
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.location.LocationRecordingService
import io.github.valeronm.breadcrumb.location.TrackingStatus
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.DebugLog
import io.github.valeronm.breadcrumb.util.formatKm
import io.github.valeronm.breadcrumb.util.isGranted
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScreen()
            }
        }
    }
}

// --- Permission helpers ------------------------------------------------------

private fun foregroundPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun Context.foregroundGranted(): Boolean = foregroundPermissions().all { isGranted(it) }

private fun Context.backgroundGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

private fun Context.isBatteryOptimizationIgnored(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
}

@Suppress("BatteryLife")
private fun Context.requestIgnoreBatteryOptimization() {
    runCatching {
        startActivity(
            Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }.onFailure {
        // Some OEMs don't expose the direct dialog; fall back to the settings list.
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

private enum class HomeTab { RECORD, TRACKS }

/** A full-screen page shown over the tabs, animated with predictive back. */
private sealed interface Overlay {
    data class TrackDetail(val id: Long) : Overlay
    data object Settings : Overlay
    data object Logs : Overlay
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val viewModel: TrackListViewModel = viewModel()
    val tracks by viewModel.tracks.collectAsState()
    val status by TrackingStatus.state.collectAsState()

    // Keep-screen-on while charging: live charger state + persisted preference; the window flag
    // holds the screen only while this activity is in the foreground (no wakelock, no permission).
    val charging = rememberChargingState()
    var keepScreenOn by remember { mutableStateOf(AppSettings.keepScreenOnCharging(context)) }
    val window = (context as? ComponentActivity)?.window
    LaunchedEffect(charging, keepScreenOn, window) {
        if (window == null) return@LaunchedEffect
        if (charging && keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Permission state, refreshed whenever the activity resumes (e.g. back from Settings).
    var foregroundOk by remember { mutableStateOf(context.foregroundGranted()) }
    var backgroundOk by remember { mutableStateOf(context.backgroundGranted()) }
    var autoOn by remember { mutableStateOf(AppSettings.isAutoRecord(context)) }
    var batteryOk by remember { mutableStateOf(context.isBatteryOptimizationIgnored()) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var selectedTab by remember { mutableStateOf(HomeTab.RECORD) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                foregroundOk = context.foregroundGranted()
                backgroundOk = context.backgroundGranted()
                autoOn = AppSettings.isAutoRecord(context)
                batteryOk = context.isBatteryOptimizationIgnored()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestForeground = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        foregroundOk = context.foregroundGranted()
    }
    val requestBackground = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        backgroundOk = context.backgroundGranted()
    }
    val exportAllLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.exportAll(treeUri) { count ->
                val message = if (count > 0) {
                    "Exported $count track${if (count == 1) "" else "s"} as GPX"
                } else {
                    "No tracks to export"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Reconcile persisted "armed" state with the actual service: if auto-recording is on but
    // the service isn't running (e.g. after a reinstall or being killed), restart it so the UI
    // doesn't get stuck on "Starting…".
    LaunchedEffect(foregroundOk, backgroundOk) {
        if (autoOn && foregroundOk && backgroundOk && !LocationRecordingService.isRunning) {
            LocationRecordingService.start(context)
        }
    }

    // The detail (map) screen overlays everything. Opening animates it in; the back gesture drives
    // a predictive scale/shift that previews the tabs underneath (Android 14+ predictive back).
    var renderedOverlay by remember { mutableStateOf<Overlay?>(null) }
    val presence = remember { Animatable(0f) }      // 0 = tabs shown, 1 = overlay fully shown
    val backProgress = remember { Animatable(0f) }  // predictive back gesture progress, 0..1
    var backEdgeSign by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(overlay) {
        val current = overlay
        if (current != null) {
            renderedOverlay = current
            backProgress.snapTo(0f)
            presence.animateTo(1f, tween(300))
        } else if (renderedOverlay != null) {
            presence.animateTo(0f, tween(300))
            renderedOverlay = null
            backProgress.snapTo(0f)
        }
    }

    PredictiveBackHandler(enabled = overlay != null) { events ->
        try {
            events.collect { event ->
                backEdgeSign = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                backProgress.snapTo(event.progress)
            }
            overlay = null // gesture committed -> dismiss
        } catch (cancelled: CancellationException) {
            backProgress.animateTo(0f, tween(200)) // gesture cancelled -> spring back
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // The tabbed UI stays composed underneath so it can be previewed during the back gesture.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (selectedTab) {
                                HomeTab.RECORD -> "Breadcrumb"
                                HomeTab.TRACKS -> "Recorded tracks"
                            },
                        )
                    },
                    actions = {
                        if (selectedTab == HomeTab.TRACKS && tracks.isNotEmpty()) {
                            IconButton(onClick = { exportAllLauncher.launch(null) }) {
                                Icon(Icons.Filled.Download, contentDescription = "Export all as GPX")
                            }
                        }
                        if (BuildConfig.DEBUG && selectedTab == HomeTab.RECORD) {
                            IconButton(onClick = { overlay = Overlay.Logs }) {
                                Icon(Icons.Filled.BugReport, contentDescription = "Logs")
                            }
                        }
                        IconButton(onClick = { overlay = Overlay.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == HomeTab.RECORD,
                        onClick = { selectedTab = HomeTab.RECORD },
                        icon = { Icon(Icons.Filled.MyLocation, contentDescription = null) },
                        label = { Text("Record") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == HomeTab.TRACKS,
                        onClick = { selectedTab = HomeTab.TRACKS },
                        icon = { Icon(Icons.Filled.Map, contentDescription = null) },
                        label = { Text("Tracks") },
                    )
                }
            },
        ) { inner ->
            Box(modifier = Modifier.fillMaxSize().padding(inner)) {
                when (selectedTab) {
                    HomeTab.RECORD -> RecordTab(
                        foregroundOk = foregroundOk,
                        backgroundOk = backgroundOk,
                        autoOn = autoOn,
                        batteryOk = batteryOk,
                        charging = charging,
                        keepScreenOn = keepScreenOn,
                        onToggleKeepScreenOn = { enabled ->
                            keepScreenOn = enabled
                            AppSettings.setKeepScreenOnCharging(context, enabled)
                        },
                        status = status,
                        viewModel = viewModel,
                        onGrantForeground = {
                            requestForeground.launch(foregroundPermissions().toTypedArray())
                        },
                        onGrantBackground = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                // Android 11+ only grants this from the app's settings page.
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ),
                                )
                            } else {
                                requestBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        },
                        onToggleAuto = { enabled ->
                            autoOn = enabled
                            if (enabled) LocationRecordingService.start(context)
                            else LocationRecordingService.stop(context)
                        },
                        onRequestBattery = { context.requestIgnoreBatteryOptimization() },
                    )

                    HomeTab.TRACKS -> TracksTab(
                        tracks = tracks,
                        viewModel = viewModel,
                        onOpen = { overlay = Overlay.TrackDetail(it) },
                        onReplay = { track ->
                            TrackReplayer.start(context, track.id)
                            selectedTab = HomeTab.RECORD
                        },
                    )
                }
            }
        }

        // Overlay (track detail or settings): animates in on open and scales/shifts with the
        // predictive-back gesture, previewing the tabs underneath.
        val rendered = renderedOverlay
        if (rendered != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val enter = presence.value
                        val back = backProgress.value
                        val scale = (0.92f + 0.08f * enter) * (1f - 0.10f * back)
                        scaleX = scale
                        scaleY = scale
                        translationX =
                            (1f - enter) * size.width * 0.25f + backEdgeSign * back * 24.dp.toPx()
                        alpha = enter * (1f - 0.2f * back)
                        transformOrigin = TransformOrigin(if (backEdgeSign > 0f) 1f else 0f, 0.5f)
                        shape = RoundedCornerShape(back * 48f)
                        clip = back > 0f
                    },
            ) {
                when (rendered) {
                    is Overlay.TrackDetail -> TrackMapScreen(
                        trackId = rendered.id,
                        summary = tracks.find { it.id == rendered.id },
                        viewModel = viewModel,
                        onBack = { overlay = null },
                    )

                    Overlay.Settings -> SettingsScreen(viewModel = viewModel, onBack = { overlay = null })

                    Overlay.Logs -> LogsScreen(onBack = { overlay = null })
                }
            }
        }
    }
}

/** Live charger state from the sticky ACTION_BATTERY_CHANGED broadcast (reacts to plug/unplug). */
@Composable
private fun rememberChargingState(): Boolean {
    val context = LocalContext.current
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                // Plugged, not "actively charging": adaptive charging / a full battery report
                // STATUS_NOT_CHARGING while on the charger, and that's still the car-mount case.
                charging = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            }
        }
        // Sticky broadcast: registration delivers the current state immediately.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return charging
}

@Composable
private fun RecordTab(
    foregroundOk: Boolean,
    backgroundOk: Boolean,
    autoOn: Boolean,
    batteryOk: Boolean,
    charging: Boolean,
    keepScreenOn: Boolean,
    onToggleKeepScreenOn: (Boolean) -> Unit,
    status: TrackingStatus.State,
    viewModel: TrackListViewModel,
    onGrantForeground: () -> Unit,
    onGrantBackground: () -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onRequestBattery: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        when {
            !foregroundOk -> PermissionCard(
                title = "Location & activity access needed",
                body = "Grant location and physical-activity access so the app can detect " +
                    "whether you're walking, driving or cycling and record GPS.",
                button = "Grant permissions",
                onClick = onGrantForeground,
            )

            !backgroundOk -> PermissionCard(
                title = "Allow background location",
                body = "Set location access to \"Allow all the time\" so tracks keep recording " +
                    "when the screen is off or the app is closed.",
                button = "Allow in the background",
                onClick = onGrantBackground,
            )

            else -> {
                AutoRecordControls(autoOn = autoOn, onToggle = onToggleAuto)
                if (autoOn && !batteryOk) {
                    Spacer(Modifier.height(8.dp))
                    PermissionCard(
                        title = "Keep recording in the background",
                        body = "Allow this app to ignore battery optimization so Android " +
                            "doesn't stop tracking after a while in the background.",
                        button = "Allow unrestricted",
                        onClick = onRequestBattery,
                    )
                }
                // The middle stretches so the keep-screen-on row is anchored at the bottom; while
                // recording (or replaying, debug), the track preview card fills all of it.
                val replay = if (BuildConfig.DEBUG) {
                    TrackReplayer.state.collectAsState().value
                } else {
                    null
                }
                when {
                    replay != null -> {
                        Spacer(Modifier.height(16.dp))
                        ReplayBanner(replay) { TrackReplayer.stop() }
                        Spacer(Modifier.height(8.dp))
                        CurrentTrackPreview(
                            status = replay.status,
                            points = replay.points,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    autoOn && status.recording && LocationRecordingService.activeTrackId != null -> {
                        Spacer(Modifier.height(16.dp))
                        LiveTrackPreview(
                            viewModel = viewModel,
                            status = status,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    autoOn -> {
                        Spacer(Modifier.height(16.dp))
                        RecorderStateCard(status)
                        Spacer(Modifier.weight(1f))
                    }
                    else -> Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                KeepScreenOnRow(
                    charging = charging,
                    enabled = keepScreenOn,
                    onToggle = onToggleKeepScreenOn,
                )
            }
        }
    }
}

/** DEBUG: banner shown above the preview while a stored track is being replayed through it. */
@Composable
private fun ReplayBanner(replay: TrackReplayer.Replay, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Replaying ${replay.trackLabel} at ${replay.speedX}×",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStop) { Text("Stop") }
    }
}

/** Loads the recorder's in-progress track and renders it via [CurrentTrackPreview]. */
@Composable
private fun LiveTrackPreview(
    viewModel: TrackListViewModel,
    status: TrackingStatus.State,
    modifier: Modifier = Modifier,
) {
    val activeId = LocationRecordingService.activeTrackId ?: return
    // Reload the in-progress track's points whenever a new one is recorded (points count changes).
    val points by produceState<List<TrackPoint>>(emptyList(), activeId, status.points) {
        value = viewModel.getPoints(activeId)
    }
    CurrentTrackPreview(status = status, points = points, modifier = modifier)
}

/** The live "current track" card: map preview + ticking stats. Pure — fed by recorder or replay. */
@Composable
private fun CurrentTrackPreview(
    status: TrackingStatus.State,
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
) {
    val activity = remember(status.activityLabel) {
        ActivityType.entries.firstOrNull { it.label == status.activityLabel }
    }
    Card(modifier = modifier) {
        Column {
            // The map takes whatever height the card is given beyond the stats block.
            Box(modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                if (points.size >= 2) {
                    MapLibreTrackMap(points = points, activity = activity, directionalEnd = true)
                } else {
                    Text(
                        "Waiting for GPS fix…",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Current track · ${status.activityLabel}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                // Live trip stats; the status flow updates per fix, which keeps these ticking.
                val startedAt = status.startedAtMillis
                val durationS = startedAt?.let { (System.currentTimeMillis() - it) / 1000.0 } ?: 0.0
                val avgKmh = if (durationS > 0) (status.distanceMeters / durationS) * 3.6 else 0.0
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatItem("Distance", formatKm(status.distanceMeters))
                    StatItem(
                        "Duration",
                        startedAt?.let { formatDuration(it, System.currentTimeMillis()) } ?: "—",
                    )
                    StatItem("Speed", status.speedMps?.let { "%.0f km/h".format(it * 3.6f) } ?: "—")
                    StatItem("Avg", if (avgKmh > 0) "%.0f km/h".format(avgKmh) else "—")
                    StatItem("Elevation", status.altitudeM?.let { "%.0f m".format(it) } ?: "—")
                }
            }
        }
    }
}

/** Armed-but-not-recording state, shown where the track preview goes once recording starts. */
@Composable
private fun RecorderStateCard(status: TrackingStatus.State) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (status.tracking) "Paused" else "Starting…",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (status.tracking) {
                    "Waiting for movement — recording starts on its own."
                } else {
                    "The recording service is starting up."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Settings-style switch with a check/cross icon in the thumb mirroring its state. */
@Composable
private fun IconSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        thumbContent = {
            Icon(
                if (checked) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}

/**
 * Keep-screen-on toggle; only actionable on the charger (car-mount use), greyed out on battery.
 * Deliberately card-less and small — a utility, not a peer of the main recording control.
 */
@Composable
private fun KeepScreenOnRow(
    charging: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Keep screen on",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (charging) "While the app is open, on the charger." else "Available while charging.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconSwitch(
            checked = enabled && charging,
            onCheckedChange = onToggle,
            enabled = charging,
        )
    }
}

/** Master on/off pill for the whole recorder, styled like Android settings' main toggle. */
@Composable
private fun AutoRecordControls(
    autoOn: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onToggle(!autoOn) },
        shape = CircleShape,
        color = if (autoOn) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (autoOn) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Auto recording",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconSwitch(checked = autoOn, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TracksTab(
    tracks: List<TrackSummary>,
    viewModel: TrackListViewModel,
    onOpen: (Long) -> Unit,
    onReplay: (TrackSummary) -> Unit,
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<TrackSummary?>(null) }

    if (tracks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No tracks yet. They'll appear here once recording captures some movement.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val groups = remember(tracks) { groupTracksByDay(tracks) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        // Rows within a day sit tight so the group reads as one visual block.
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEach { (label, dayTracks) ->
            stickyHeader(key = "header:$label") {
                DayHeader(label, dayTracks) {
                    viewModel.shareTracks(dayTracks.map { it.id }) { intent ->
                        if (intent != null) context.startActivity(intent)
                    }
                }
            }
            itemsIndexed(dayTracks, key = { _, track -> track.id }) { index, track ->
                TrackRow(
                    track = track,
                    shape = groupedRowShape(index, dayTracks.size),
                    onOpen = { onOpen(track.id) },
                    onDeleteRequest = { pendingDelete = track },
                    // DEBUG: long-press replays the track through the Record tab's live view.
                    onReplay = if (BuildConfig.DEBUG) {
                        { onReplay(track) }
                    } else {
                        null
                    },
                )
            }
        }
    }

    pendingDelete?.let { track ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Delete this track?") },
            text = {
                Text(
                    "The ${ActivityType.labelFor(track.activityType)} track from " +
                        "${dateFormat.format(Date(track.startedAt))} will be permanently " +
                        "deleted. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(track.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Corner shape for a row in a day group: large outer corners on the group's first/last edge,
 * small inner corners between neighbours — the rows read as one grouped block.
 */
private fun groupedRowShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 12.dp
    val inner = 4.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

private class DayActivityTotal(val activity: ActivityType?, val meters: Double, val durationMs: Long)

private fun dayActivityTotals(tracks: List<TrackSummary>): List<DayActivityTotal> =
    tracks.groupBy { ActivityType.ofName(it.activityType) }
        .map { (activity, list) ->
            DayActivityTotal(
                activity = activity,
                meters = list.sumOf { it.distanceMeters },
                durationMs = list.sumOf { (it.endedAt ?: it.startedAt) - it.startedAt },
            )
        }
        .sortedByDescending { it.meters }

@Composable
private fun DayHeader(label: String, dayTracks: List<TrackSummary>, onShare: () -> Unit) {
    val totals = remember(dayTracks) { dayActivityTotals(dayTracks) }
    Column(
        // Opaque background: the header is sticky, rows scroll underneath it.
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "Share $label tracks")
            }
        }
        // Day totals per recorded activity, in the row style: tinted glyph + distance · duration.
        Row(
            modifier = Modifier.padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (total in totals) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        activityIcon(total.activity),
                        contentDescription = total.activity?.label,
                        tint = activityColor(total.activity),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${formatKm(total.meters)} · ${formatDurationMs(total.durationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(viewModel: TrackListViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var intervalSec by remember { mutableFloatStateOf(AppSettings.minIntervalSec(context).toFloat()) }
    var distanceM by remember { mutableFloatStateOf(AppSettings.minDistanceM(context).toFloat()) }
    var minDurationSec by remember {
        mutableFloatStateOf(AppSettings.minTrackDurationSec(context).toFloat())
    }
    var minLengthM by remember { mutableFloatStateOf(AppSettings.minTrackLengthM(context).toFloat()) }
    var minExtentM by remember { mutableFloatStateOf(AppSettings.minTrackExtentM(context).toFloat()) }
    var resumeWindowSec by remember { mutableFloatStateOf(AppSettings.resumeWindowSec(context).toFloat()) }
    var accuracyGateM by remember { mutableFloatStateOf(AppSettings.accuracyGateM(context).toFloat()) }
    var requireGnssFix by remember { mutableStateOf(AppSettings.requireGnssFix(context)) }
    var gpsGiveUpSec by remember { mutableFloatStateOf(AppSettings.gpsGiveUpSec(context).toFloat()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
        SectionHeader(
            "Sampling",
            canReset = intervalSec.toInt() != AppSettings.DEFAULT_SAMPLING_MIN_INTERVAL_SEC ||
                distanceM.toInt() != AppSettings.DEFAULT_SAMPLING_MIN_DISTANCE_M,
        ) {
            intervalSec = AppSettings.DEFAULT_SAMPLING_MIN_INTERVAL_SEC.toFloat()
            distanceM = AppSettings.DEFAULT_SAMPLING_MIN_DISTANCE_M.toFloat()
            AppSettings.setMinIntervalSec(context, AppSettings.DEFAULT_SAMPLING_MIN_INTERVAL_SEC)
            AppSettings.setMinDistanceM(context, AppSettings.DEFAULT_SAMPLING_MIN_DISTANCE_M)
        }
        Text(
            "How densely points are recorded while moving. Applies to the next track.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        GroupedRows(
            {
                SliderSetting("Min time between points", intervalSec, 1f..30f, 1, { "${it.toInt()} s" }) {
                    intervalSec = it
                    AppSettings.setMinIntervalSec(context, it.toInt())
                }
            },
            {
                SliderSetting("Min distance between points", distanceM, 1f..50f, 1, { "${it.toInt()} m" }) {
                    distanceM = it
                    AppSettings.setMinDistanceM(context, it.toInt())
                }
            },
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader(
            "Keep a track only if",
            canReset = minDurationSec.toInt() != AppSettings.DEFAULT_TRACK_MIN_DURATION_SEC ||
                minLengthM.toInt() != AppSettings.DEFAULT_TRACK_MIN_LENGTH_M ||
                minExtentM.toInt() != AppSettings.DEFAULT_TRACK_MIN_EXTENT_M,
        ) {
            minDurationSec = AppSettings.DEFAULT_TRACK_MIN_DURATION_SEC.toFloat()
            minLengthM = AppSettings.DEFAULT_TRACK_MIN_LENGTH_M.toFloat()
            minExtentM = AppSettings.DEFAULT_TRACK_MIN_EXTENT_M.toFloat()
            AppSettings.setMinTrackDurationSec(context, AppSettings.DEFAULT_TRACK_MIN_DURATION_SEC)
            AppSettings.setMinTrackLengthM(context, AppSettings.DEFAULT_TRACK_MIN_LENGTH_M)
            AppSettings.setMinTrackExtentM(context, AppSettings.DEFAULT_TRACK_MIN_EXTENT_M)
        }
        Text(
            "Shorter tracks are discarded when recording stops. Extent is how far the track " +
                "spread — it rejects a stationary walk that only wandered on GPS noise.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        GroupedRows(
            {
                SliderSetting("Min duration", minDurationSec, 0f..300f, 30, { durationSettingLabel(it.toInt()) }) {
                    minDurationSec = it
                    AppSettings.setMinTrackDurationSec(context, it.toInt())
                }
            },
            {
                SliderSetting("Min length", minLengthM, 0f..500f, 50, { lengthSettingLabel(it.toInt()) }) {
                    minLengthM = it
                    AppSettings.setMinTrackLengthM(context, it.toInt())
                }
            },
            {
                SliderSetting("Min extent", minExtentM, 0f..500f, 50, { lengthSettingLabel(it.toInt()) }) {
                    minExtentM = it
                    AppSettings.setMinTrackExtentM(context, it.toInt())
                }
            },
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader(
            "Auto-pause",
            canReset = resumeWindowSec.toInt() != AppSettings.DEFAULT_STITCH_RESUME_WINDOW_SEC,
        ) {
            resumeWindowSec = AppSettings.DEFAULT_STITCH_RESUME_WINDOW_SEC.toFloat()
            AppSettings.setResumeWindowSec(context, AppSettings.DEFAULT_STITCH_RESUME_WINDOW_SEC)
        }
        Text(
            "A brief stop keeps the same track; it resumes as a new segment when you move again " +
                "within this window. Off = always a new track.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        GroupedRows(
            {
                SliderSetting("Resume window", resumeWindowSec, 0f..600f, 60, { durationSettingLabel(it.toInt()) }) {
                    resumeWindowSec = it
                    AppSettings.setResumeWindowSec(context, it.toInt())
                }
            },
        )

        Spacer(Modifier.height(24.dp))
        SectionHeader(
            "Accuracy filter",
            canReset = accuracyGateM.toInt() != AppSettings.DEFAULT_ACCURACY_GATE_M ||
                requireGnssFix != AppSettings.DEFAULT_REQUIRE_GNSS_FIX ||
                gpsGiveUpSec.toInt() != AppSettings.DEFAULT_GPS_GIVE_UP_SEC,
        ) {
            accuracyGateM = AppSettings.DEFAULT_ACCURACY_GATE_M.toFloat()
            requireGnssFix = AppSettings.DEFAULT_REQUIRE_GNSS_FIX
            gpsGiveUpSec = AppSettings.DEFAULT_GPS_GIVE_UP_SEC.toFloat()
            AppSettings.setAccuracyGateM(context, AppSettings.DEFAULT_ACCURACY_GATE_M)
            AppSettings.setRequireGnssFix(context, AppSettings.DEFAULT_REQUIRE_GNSS_FIX)
            AppSettings.setGpsGiveUpSec(context, AppSettings.DEFAULT_GPS_GIVE_UP_SEC)
        }
        Text(
            "Fixes less accurate than this are flagged noisy and left out of the track. Applies to " +
                "newly recorded tracks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        GroupedRows(
            {
                SliderSetting("Max accuracy radius", accuracyGateM, 10f..150f, 10, { "${it.toInt()} m" }) {
                    accuracyGateM = it
                    AppSettings.setAccuracyGateM(context, it.toInt())
                }
            },
            {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Require satellite fix", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Also drop fixes with no live satellite lock — the position the phone " +
                                "reports from Wi-Fi/cell in a tunnel, which can look accurate but wander.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    IconSwitch(
                        checked = requireGnssFix,
                        onCheckedChange = {
                            requireGnssFix = it
                            AppSettings.setRequireGnssFix(context, it)
                        },
                    )
                }
            },
            {
                Text(
                    "If GPS runs this long without a usable fix (indoors on a false start), switch " +
                        "it off and retry on motion or when another app gets a fix. Off = keep " +
                        "searching forever.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SliderSetting("GPS give-up", gpsGiveUpSec, 0f..600f, 60, { durationSettingLabel(it.toInt()) }) {
                    gpsGiveUpSec = it
                    AppSettings.setGpsGiveUpSec(context, it.toInt())
                }
            },
        )


        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(24.dp))
            Text("Debug", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            GroupedRows(
                {
                    Text(
                        "Insert a synthetic track for testing the list, map, swipe and share.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    var seeding by remember { mutableStateOf(false) }
                    Button(
                        enabled = !seeding,
                        onClick = {
                            seeding = true
                            viewModel.seedSampleTrack {
                                seeding = false
                                Toast.makeText(context, "Seeded a sample track", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { Text("Seed sample track") }
                },
            )
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Breadcrumb ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by DebugLog.entries.collectAsState(initial = emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs (${entries.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, DebugLog.dump())
                        }
                        context.startActivity(Intent.createChooser(share, "Share logs"))
                    }) { Icon(Icons.Filled.Share, contentDescription = "Share logs") }
                    IconButton(onClick = { DebugLog.clear() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear logs")
                    }
                },
            )
        },
    ) { inner ->
        if (entries.isEmpty()) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No logs yet — arm recording and move around.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Newest first so the latest events are visible without scrolling.
            LazyColumn(modifier = Modifier.padding(inner).fillMaxSize().padding(horizontal = 12.dp)) {
                items(entries.asReversed()) { e ->
                    val color = when (e.level) {
                        'E' -> MaterialTheme.colorScheme.error
                        'W' -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        "${DebugLog.formatTime(e.timeMillis)}  ${e.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Android-settings-style group: each row is its own card, large corners on the group's outer
 * edges and small ones between neighbours (same look as the track list's day groups).
 */
@Composable
private fun GroupedRows(vararg rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            Card(modifier = Modifier.fillMaxWidth(), shape = groupedRowShape(index, rows.size)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { row() }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, canReset: Boolean, onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        // Only offer Reset when something in the group differs from its default.
        TextButton(onClick = onReset, enabled = canReset) { Text("Reset") }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Int,
    valueText: (Float) -> String,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    // Dim the whole row when disabled (the Slider greys itself, the labels need help).
    Column(Modifier.alpha(if (enabled) 1f else 0.38f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueText(value),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            enabled = enabled,
            onValueChange = { raw ->
                // Snap to the nearest step so values land on round numbers.
                val snapped = ((raw / step).roundToInt() * step).toFloat()
                    .coerceIn(range.start, range.endInclusive)
                onChange(snapped)
            },
            valueRange = range,
        )
    }
}

private fun durationSettingLabel(sec: Int): String = when {
    sec <= 0 -> "Off"
    sec < 60 -> "$sec s"
    sec % 60 == 0 -> "${sec / 60} min"
    else -> "${sec / 60}m ${sec % 60}s"
}

private fun lengthSettingLabel(m: Int): String = when {
    m <= 0 -> "Off"
    m < 1000 -> "$m m"
    else -> "%.1f km".format(m / 1000.0)
}

private fun groupTracksByDay(tracks: List<TrackSummary>): List<Pair<String, List<TrackSummary>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    // groupBy preserves encounter order, and tracks arrive newest-first, so days stay descending.
    return tracks
        .groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        .map { (date, list) -> dayLabel(date, today) to list }
}

private val dayHeaderFormat = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.getDefault())

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(dayHeaderFormat)
}

private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: TrackSummary,
    shape: RoundedCornerShape,
    onOpen: () -> Unit,
    onDeleteRequest: () -> Unit,
    onReplay: (() -> Unit)? = null,
) {
    // Swipe right-to-left to request deletion: veto the dismissal itself (so the row springs back
    // on its own) and ask for confirmation — the actual delete happens via the dialog. Resetting
    // from a targetValue observer instead loses to an in-progress drag and leaves the row stuck
    // showing the red background after Cancel.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDeleteRequest()
                false
            } else {
                true
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onReplay),
            shape = shape,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val activity = ActivityType.ofName(track.activityType)
                val activityTint = activityColor(activity)
                // Activity token: the glyph in the activity colour on a soft tonal disc of the same
                // colour (M3 "tonal" style) — a clear category cue that stays quiet.
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                        .background(activityTint.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = activityIcon(activity),
                        contentDescription = ActivityType.labelFor(track.activityType),
                        tint = activityTint,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val start = timeFormat.format(Date(track.startedAt))
                    val timeLine = track.endedAt?.let { "$start – ${timeFormat.format(Date(it))}" } ?: start
                    Text(
                        timeLine,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${formatKm(track.distanceMeters)} · ${formatDuration(track.startedAt, track.endedAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (track.ignoredCount > 0) {
                    // Noisy fixes are a caution, not an error — a small amber corner marker. The label
                    // is kept for screen readers.
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = noisyFixesLabel(track.ignoredCount),
                        tint = WarningAmber.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.Top).size(16.dp),
                    )
                }
            }
        }
    }
}

private fun formatDuration(startedAt: Long, endedAt: Long?): String {
    val end = endedAt ?: return "recording"
    return formatDurationMs(end - startedAt)
}

private fun formatDurationMs(durationMs: Long): String {
    val minutes = (durationMs / 60000.0).roundToLong()
    return if (minutes >= 60) "%dh %02dm".format(minutes / 60, minutes % 60) else "%dm".format(minutes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackMapScreen(
    trackId: Long,
    summary: TrackSummary?,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
) {
    // null = still loading the track's points from the database.
    val context = LocalContext.current
    val points by produceState<List<TrackPoint>?>(initialValue = null, trackId) {
        value = viewModel.getPoints(trackId)
    }
    val noisyPoints by produceState<List<TrackPoint>>(initialValue = emptyList(), trackId) {
        value = viewModel.getIgnoredPoints(trackId)
    }
    val activity = remember(summary) {
        summary?.let { ActivityType.ofName(it.activityType) }
    }
    var colorMode by remember { mutableStateOf(ColorMode.SPEED) }
    // Noisy (ignored) fixes are hidden by default; the warning toggle shows them with a legend.
    var showNoisy by remember(trackId) { mutableStateOf(false) }
    // Point picked on the metric graph, highlighted on the map. Index into the good-points list.
    var selectedIndex by remember(trackId) { mutableStateOf<Int?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(summary?.let { ActivityType.labelFor(it.activityType) } ?: "Track")
                        if (summary != null) {
                            Text(
                                dateFormat.format(Date(summary.startedAt)) +
                                    (summary.endedAt?.let { " – ${timeFormat.format(Date(it))}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (noisyPoints.isNotEmpty()) {
                        IconButton(onClick = { showNoisy = !showNoisy }) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription =
                                    if (showNoisy) "Hide noisy fixes" else "Show noisy fixes",
                                tint = if (showNoisy) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    IconButton(onClick = {
                        viewModel.shareTracks(listOf(trackId)) { intent ->
                            if (intent != null) context.startActivity(intent)
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share GPX")
                    }
                },
            )
        },
    ) { inner ->
        Box(modifier = Modifier.padding(inner).fillMaxSize().clipToBounds()) {
            val loaded = points
            when {
                loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                loaded.size < 2 -> Text(
                    "Not enough points to draw this track on a map.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (summary != null) {
                        Card(Modifier.fillMaxWidth()) { TrackStatsHeader(summary) }
                    }
                    val graph = remember(loaded, colorMode, activity) {
                        metricGraphData(loaded, colorMode, activity)
                    }
                    // Metric chips, map, and scrubber read as one group: small gaps, small
                    // corners between neighbours.
                    Column(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val blocks = if (graph != null) 3 else 2
                        Card(Modifier.fillMaxWidth(), shape = groupedRowShape(0, blocks)) {
                            ColorModeSelector(colorMode) { colorMode = it }
                        }
                        // The map card takes the stretch.
                        Card(Modifier.weight(1f).fillMaxWidth(), shape = groupedRowShape(1, blocks)) {
                            Box(Modifier.fillMaxSize().clipToBounds()) {
                                MapLibreTrackMap(
                                    points = loaded,
                                    noisyPoints = if (showNoisy) noisyPoints else emptyList(),
                                    activity = activity,
                                    colorMode = colorMode,
                                    showLegend = true,
                                    selectedPoint = selectedIndex?.let { loaded.getOrNull(it) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (showNoisy) {
                                    // Top-right, clear of the colour-metric legend (bottom-right).
                                    NoisyLegend(noisyPoints, Modifier.align(Alignment.TopEnd).padding(12.dp))
                                }
                            }
                        }
                        if (graph != null) {
                            Card(Modifier.fillMaxWidth(), shape = groupedRowShape(2, 3)) {
                                MetricGraph(
                                    graph = graph,
                                    selectedIndex = selectedIndex,
                                    onSelect = { selectedIndex = it },
                                    modifier = Modifier.fillMaxWidth().height(130.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Per-point series for the metric graph: values (null = gap), map-matching colours, and a unit. */
@Immutable
internal class MetricGraphData(
    val points: List<TrackPoint>,
    val values: List<Float?>,
    val colors: IntArray,
    val unit: String,
)

/** Null when [mode] has no numeric series (categorical Source) or no point carries the metric. */
internal fun metricGraphData(
    points: List<TrackPoint>,
    mode: ColorMode,
    activity: ActivityType?,
): MetricGraphData? {
    val speeds by lazy { TrackQuality.pointSpeedsKmh(points) }
    val values: List<Float?> = when (mode) {
        ColorMode.SPEED -> List(points.size) { speeds[it] }
        ColorMode.ELEVATION -> points.map { it.altitude?.toFloat() }
        ColorMode.ACCURACY -> points.map { it.accuracy }
        ColorMode.SATELLITES -> points.map { it.satellitesInFix?.toFloat() }
        ColorMode.CN0 -> points.map { it.cn0 }
        ColorMode.PROVIDER -> return null
    }
    if (values.all { it == null }) return null
    val unit = when (mode) {
        ColorMode.SPEED -> "km/h"
        ColorMode.ELEVATION, ColorMode.ACCURACY -> "m"
        ColorMode.SATELLITES -> "sat"
        ColorMode.CN0 -> "dB"
        ColorMode.PROVIDER -> ""
    }
    val colors = trackColoring(points, TrackQuality.pointSpeedsKmh(points), mode, activity).colors
    return MetricGraphData(points, values, colors, unit)
}

private val graphTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * The metric polyline alone, rasterized once per (graph, size) into a bitmap and blitted per frame.
 * Recording/issuing thousands of drawLine ops every frame is what made scrubbing long tracks lag
 * (the cursor overlay invalidates each frame); a cached bitmap makes the plot a single drawImage.
 * Takes only the (immutable) series and scale, never the selection, so Compose also skips it.
 */
@Composable
private fun MetricPlot(
    graph: MetricGraphData,
    minV: Float,
    span: Float,
    t0: Long,
    tSpan: Float,
    modifier: Modifier,
) {
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    val padPx = with(LocalDensity.current) { 8.dp.toPx() }
    Spacer(
        modifier.drawWithCache {
            val bitmap = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            CanvasDrawScope().draw(this, layoutDirection, ComposeCanvas(bitmap), size) {
                val h = size.height - 2 * padPx
                var prev: Offset? = null
                for (i in graph.points.indices) {
                    val v = graph.values[i]
                    if (v == null) {
                        prev = null
                        continue
                    }
                    if (graph.points[i].segmentStart) prev = null
                    val x = (graph.points[i].timestamp - t0) / tSpan * size.width
                    val y = padPx + h - ((v - minV) / span) * h
                    val current = Offset(x, y)
                    prev?.let { drawLine(Color(graph.colors[i]), it, current, strokeWidth = strokePx) }
                    prev = current
                }
            }
            onDrawBehind { drawImage(bitmap) }
        },
    )
}

/**
 * The selected colour metric over the track's time span, stroked point-to-point in the same colours
 * as the map's track line, with a time axis. Missing values and segment starts break the line.
 * Tapping or dragging picks the nearest point ([onSelect]); the selection is drawn as a cursor with
 * a value/time readout, and the caller highlights the same point on the map.
 */
@Composable
private fun MetricGraph(
    graph: MetricGraphData,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier,
) {
    val present = graph.values.filterNotNull()
    val minV = present.min()
    val maxV = present.max()
    val span = (maxV - minV).let { if (it < 1e-3f) 1f else it }
    val t0 = graph.points.first().timestamp
    val tSpan = (graph.points.last().timestamp - t0).coerceAtLeast(1L).toFloat()

    // x (0..width) -> index of the nearest point that actually has a value.
    fun indexAt(x: Float, width: Float): Int? {
        if (width <= 0f) return null
        // Keep the epoch-millis math in Long: t0 (~1.8e12) + Float promotes to Float, whose
        // precision at that magnitude quantizes the target to ~131 s steps.
        val target = t0 + ((x / width).coerceIn(0f, 1f) * tSpan).toLong()
        var best: Int? = null
        var bestDist = Long.MAX_VALUE
        for (i in graph.points.indices) {
            if (graph.values[i] == null) continue
            val d = kotlin.math.abs(graph.points[i].timestamp - target)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    Surface(modifier = modifier, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxSize()) {
            val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
            val padPx = with(LocalDensity.current) { 8.dp.toPx() }
            val cursorColor = MaterialTheme.colorScheme.onSurface
            val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            val view = LocalView.current
            // Tick only when the scrubber actually lands on a different point, throttled so a fast
            // drag across ~1 Hz data feels like a picker, not a buzz. The last index lives in a
            // holder rather than comparing against the composed selectedIndex: the gesture lambdas
            // run inside pointerInput(graph), whose closure captures one composition and goes stale.
            val lastReported = remember(graph) { arrayOf<Int?>(null) }
            val lastTick = remember(graph) { longArrayOf(0L) }
            fun select(index: Int?) {
                if (index != null && index != lastReported[0]) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTick[0] >= 30) {
                        lastTick[0] = now
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                lastReported[0] = index
                onSelect(index)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Static plot in its own skippable composable: scrubbing recomposes MetricGraph per
                // touch event, and redrawing the full multi-thousand-segment polyline each time is
                // what made long tracks feel laggy. Only the cursor overlay below redraws.
                MetricPlot(graph, minV, span, t0, tSpan, Modifier.fillMaxSize())
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(graph) {
                            detectTapGestures { offset ->
                                select(indexAt(offset.x, size.width.toFloat()))
                            }
                        }
                        .pointerInput(graph) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                select(indexAt(change.position.x, size.width.toFloat()))
                            }
                        },
                ) {
                    val h = size.height - 2 * padPx
                    val sel = selectedIndex?.takeIf { it in graph.points.indices }
                    val selValue = sel?.let { graph.values[it] }
                    if (sel != null && selValue != null) {
                        val x = (graph.points[sel].timestamp - t0) / tSpan * size.width
                        val y = padPx + h - ((selValue - minV) / span) * h
                        drawLine(
                            cursorColor.copy(alpha = 0.6f),
                            Offset(x, 0f),
                            Offset(x, size.height),
                            strokeWidth = strokePx / 2,
                        )
                        drawCircle(cursorColor, radius = strokePx * 2, center = Offset(x, y))
                    }
                }
                Text(
                    "%.0f %s".format(maxV, graph.unit),
                    modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Text(
                    "%.0f %s".format(minV, graph.unit),
                    modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                val sel = selectedIndex?.takeIf { it in graph.points.indices }
                val selValue = sel?.let { graph.values[it] }
                if (sel != null && selValue != null) {
                    Text(
                        "%.0f %s · %s".format(
                            selValue, graph.unit,
                            graphTimeFormat.format(Date(graph.points[sel].timestamp)),
                        ),
                        modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(graphTimeFormat.format(Date(t0)), style = MaterialTheme.typography.labelSmall, color = labelColor)
                Text(
                    graphTimeFormat.format(Date(t0 + (tSpan / 2).toLong())),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Text(
                    graphTimeFormat.format(Date(t0 + tSpan.toLong())),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
        }
    }
}

// Chip colours match the marker drawables (ic_marker_noisy / _jump / _gnss).
private fun noisyLegendEntry(reason: IgnoreReason?): Pair<String, Color> = when (reason) {
    IgnoreReason.JUMP -> "Speed jump" to Color(0xFFE53935)
    IgnoreReason.NO_GNSS -> "No satellite fix" to Color(0xFFAB47BC)
    IgnoreReason.ACCURACY, null -> "Low accuracy" to Color(0xFFFF8F00)
}

/** Legend for the noisy-fix markers: one row per rejection reason present in [noisyPoints]. */
@Composable
private fun NoisyLegend(noisyPoints: List<TrackPoint>, modifier: Modifier) {
    val entries = remember(noisyPoints) {
        noisyPoints.map { noisyLegendEntry(IgnoreReason.fromCode(it.ignoreReason)) }.distinct()
    }
    LegendSurface(modifier) {
        for ((label, color) in entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TrackStatsHeader(summary: TrackSummary) {
    val durationS = summary.endedAt?.let { (it - summary.startedAt) / 1000.0 } ?: 0.0
    val avgKmh = if (durationS > 0) (summary.distanceMeters / durationS) * 3.6 else 0.0
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            HeaderStat("Distance", formatKm(summary.distanceMeters), Modifier.weight(1f))
            HeaderStat("Duration", formatDuration(summary.startedAt, summary.endedAt), Modifier.weight(1f))
            HeaderStat("Avg speed", if (avgKmh > 0) "%.0f km/h".format(avgKmh) else "—", Modifier.weight(1f))
        }
        if (summary.ignoredCount > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                noisyFixesLabel(summary.ignoredCount, " from this track"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun HeaderStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun noisyFixesLabel(count: Int, suffix: String = ""): String =
    "⚠ $count noisy ${if (count == 1) "fix" else "fixes"} excluded$suffix"

private fun activityIcon(activity: ActivityType?): ImageVector = when (activity) {
    ActivityType.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityType.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityType.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
    ActivityType.DRIVING -> Icons.Filled.DirectionsCar
    else -> Icons.Filled.Place
}

// A qualitative (categorical) palette for activity type. M3 has no categorical roles, so this is a
// derived set: one fixed saturation + lightness, only the hue rotates, so every category carries
// equal visual weight. It's a calmer sibling of the map's speed ramp (lower saturation) so the list
// stays quiet. Green is nudged toward teal to avoid colliding with the app's green theme accent.
// STILL/UNKNOWN fall back to the neutral scheme colour.
private const val ACTIVITY_SAT = 0.5f
private const val ACTIVITY_LUM = 0.62f

@Composable
private fun activityColor(activity: ActivityType?): Color = when (activity) {
    ActivityType.DRIVING -> Color.hsl(210f, ACTIVITY_SAT, ACTIVITY_LUM) // blue
    ActivityType.CYCLING -> Color.hsl(165f, ACTIVITY_SAT, ACTIVITY_LUM) // teal-green
    ActivityType.RUNNING -> Color.hsl(30f, ACTIVITY_SAT, ACTIVITY_LUM)  // orange
    ActivityType.WALKING -> Color.hsl(275f, ACTIVITY_SAT, ACTIVITY_LUM) // violet
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// Static, per-activity speed→colour scale so tracks are visually comparable across the whole list:
// red (slow) → green (a good cruising pace) → blue (fast). Hue runs 0°(red)→240°(blue), so with an
// evenly-spaced min/mid/max the midpoint speed lands exactly on green.
private const val HUE_RED = 0f
private const val HUE_GREEN = 120f
private const val HUE_BLUE = 240f
private const val SPEED_SATURATION = 0.9f
private const val SPEED_LUMINANCE = 0.5f

/** Speed thresholds (km/h) anchoring the red / green / blue points of the colour ramp per activity. */
private data class SpeedScale(val minKmh: Float, val midKmh: Float, val maxKmh: Float)

private fun speedScaleFor(activity: ActivityType): SpeedScale = when (activity) {
    ActivityType.DRIVING, ActivityType.UNKNOWN -> SpeedScale(30f, 90f, 150f)
    ActivityType.CYCLING -> SpeedScale(10f, 22f, 34f)
    ActivityType.RUNNING -> SpeedScale(6f, 11f, 16f)
    ActivityType.WALKING, ActivityType.STILL -> SpeedScale(2f, 5f, 8f)
}

// --- Track line colouring by metric ------------------------------------------------------------

/** Which per-point metric the track line is coloured by. */
enum class ColorMode(val label: String) {
    SPEED("Speed"),
    ELEVATION("Elevation"),
    ACCURACY("Accuracy"),
    SATELLITES("Satellites"),
    CN0("Signal"),
    PROVIDER("Source"),
}

private val WarningAmber = Color(0xFFFFB300) // caution marker (e.g. noisy-fix count) — not an error
private const val HUE_AMBER = 40f
private val NO_DATA_COLOR = Color.hsl(0f, 0f, 0.6f) // grey for points the metric has no value for
private val NO_DATA_ARGB = NO_DATA_COLOR.toArgb()

/** Legend content for the current colour mode. */
internal sealed interface Legend {
    /** Continuous red→green→blue ramp with anchor labels. */
    data class Ramp(val left: String, val mid: String, val right: String) : Legend
    /** Discrete swatches (e.g. per location provider). */
    data class Categories(val entries: List<Pair<String, Color>>) : Legend
    /** No point in the track carries this metric. */
    data class None(val message: String) : Legend
}

internal class TrackColoring(val colors: IntArray, val legend: Legend)

/** ARGB on the red(0°)→green(120°)→blue(240°) ramp for [value] between the [redAt] and [blueAt] anchors. */
private fun rampColor(value: Float?, redAt: Float, blueAt: Float): Int {
    if (value == null) return NO_DATA_ARGB
    val t = ((value - redAt) / (blueAt - redAt)).coerceIn(0f, 1f)
    val hue = HUE_RED + t * (HUE_BLUE - HUE_RED)
    return Color.hsl(hue, SPEED_SATURATION, SPEED_LUMINANCE).toArgb()
}

private fun rampColoring(
    values: List<Float?>, redAt: Float, blueAt: Float, unit: String, emptyMsg: String,
): TrackColoring {
    if (values.all { it == null }) {
        return TrackColoring(IntArray(values.size) { NO_DATA_ARGB }, Legend.None(emptyMsg))
    }
    val colors = IntArray(values.size) { rampColor(values[it], redAt, blueAt) }
    fun num(v: Float) = "%.0f".format(v)
    // Unit only on the rightmost label, else three "… unit" labels overflow the fixed-width legend.
    val right = num(blueAt).let { if (unit.isEmpty()) it else "$it $unit" }
    return TrackColoring(colors, Legend.Ramp(num(redAt), num((redAt + blueAt) / 2f), right))
}

private fun providerArgb(provider: String?): Int = when (provider) {
    "gps" -> Color.hsl(HUE_GREEN, SPEED_SATURATION, SPEED_LUMINANCE)
    "fused" -> Color.hsl(HUE_AMBER, SPEED_SATURATION, SPEED_LUMINANCE)
    "network" -> Color.hsl(HUE_RED, SPEED_SATURATION, SPEED_LUMINANCE)
    else -> NO_DATA_COLOR
}.toArgb()

/**
 * Per-point colours + legend for [mode]. Ramps go red→green→blue between two anchor values; where an
 * anchor is "worse" it's placed at red (e.g. accuracy: 50 m = red, 0 m = blue). Points missing the
 * metric are grey. [provider] is categorical, not a ramp.
 */
internal fun trackColoring(
    points: List<TrackPoint>, speedsKmh: FloatArray, mode: ColorMode, activity: ActivityType?,
): TrackColoring = when (mode) {
    ColorMode.SPEED -> {
        val s = speedScaleFor(activity ?: ActivityType.UNKNOWN)
        rampColoring(List(points.size) { speedsKmh[it] }, s.minKmh, s.maxKmh, "km/h", "No speed data")
    }
    ColorMode.ELEVATION -> {
        val alts = points.map { it.altitude?.toFloat() }
        val present = alts.filterNotNull()
        if (present.isEmpty()) {
            TrackColoring(IntArray(points.size) { NO_DATA_ARGB }, Legend.None("No elevation data"))
        } else {
            val lo = present.min()
            val hi = present.max()
            val span = if (hi - lo < 1f) 1f else hi - lo // avoid a zero-width ramp on a flat track
            rampColoring(alts, lo, lo + span, "m", "No elevation data")
        }
    }
    // Lower accuracy radius is better, so 0 m sits at the blue (good) end.
    ColorMode.ACCURACY -> rampColoring(points.map { it.accuracy }, 50f, 0f, "m", "No accuracy data")
    ColorMode.SATELLITES ->
        rampColoring(points.map { it.satellitesInFix?.toFloat() }, 0f, 12f, "sat", "No satellite data")
    ColorMode.CN0 -> rampColoring(points.map { it.cn0 }, 15f, 45f, "dB", "No signal data")
    ColorMode.PROVIDER -> {
        val colors = IntArray(points.size) { providerArgb(points[it].provider) }
        val used = points.mapNotNull { it.provider }.distinct().sorted()
        if (used.isEmpty()) TrackColoring(colors, Legend.None("No source data"))
        else TrackColoring(colors, Legend.Categories(used.map { it to Color(providerArgb(it)) }))
    }
}

/** Horizontally-scrollable chips to pick how the track line is coloured. */
@Composable
private fun ColorModeSelector(selected: ColorMode, onSelect: (ColorMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in ColorMode.entries) {
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(mode.label) },
            )
        }
    }
}

@Composable
internal fun TrackLegend(legend: Legend, modifier: Modifier) {
    when (legend) {
        is Legend.None ->
            LegendSurface(modifier) {
                Text(legend.message, style = MaterialTheme.typography.labelSmall)
            }
        is Legend.Ramp ->
            LegendSurface(modifier) {
                Box(
                    Modifier
                        .width(132.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.hsl(HUE_RED, SPEED_SATURATION, SPEED_LUMINANCE),
                                    Color.hsl(HUE_GREEN, SPEED_SATURATION, SPEED_LUMINANCE),
                                    Color.hsl(HUE_BLUE, SPEED_SATURATION, SPEED_LUMINANCE),
                                ),
                            ),
                        ),
                )
                Spacer(Modifier.height(2.dp))
                Row(Modifier.width(132.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(legend.left, style = MaterialTheme.typography.labelSmall)
                    Text(legend.mid, style = MaterialTheme.typography.labelSmall)
                    Text(legend.right, style = MaterialTheme.typography.labelSmall)
                }
            }
        is Legend.Categories ->
            LegendSurface(modifier) {
                for ((label, color) in legend.entries) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
    }
}

@Composable
private fun LegendSurface(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
private fun PermissionCard(title: String, body: String, button: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}
