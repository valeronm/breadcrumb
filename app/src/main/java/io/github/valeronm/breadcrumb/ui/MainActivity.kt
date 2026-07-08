package io.github.valeronm.breadcrumb.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.BugReport
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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

@Composable
private fun RecordTab(
    foregroundOk: Boolean,
    backgroundOk: Boolean,
    autoOn: Boolean,
    batteryOk: Boolean,
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
                AutoRecordControls(autoOn = autoOn, status = status, onToggle = onToggleAuto)
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
                if (status.recording && LocationRecordingService.activeTrackId != null) {
                    Spacer(Modifier.height(16.dp))
                    CurrentTrackPreview(viewModel = viewModel, status = status)
                }
            }
        }
    }
}

@Composable
private fun CurrentTrackPreview(viewModel: TrackListViewModel, status: TrackingStatus.State) {
    val activeId = LocationRecordingService.activeTrackId ?: return
    // Reload the in-progress track's points whenever a new one is recorded (points count changes).
    val points by produceState<List<TrackPoint>>(emptyList(), activeId, status.points) {
        value = viewModel.getPoints(activeId)
    }
    val activity = remember(status.activityLabel) {
        ActivityType.entries.firstOrNull { it.label == status.activityLabel }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(220.dp).clipToBounds()) {
                if (points.size >= 2) {
                    MapLibreTrackMap(points = points, activity = activity)
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
                Spacer(Modifier.height(4.dp))
                Text(
                    formatKm(status.distanceMeters),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AutoRecordControls(
    autoOn: Boolean,
    status: TrackingStatus.State,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto recording", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (autoOn) {
                            "Armed — records automatically based on your activity."
                        } else {
                            "Off — turn on once and tracks record themselves."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = autoOn, onCheckedChange = onToggle)
            }
            if (autoOn) {
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        !status.tracking -> "Starting…"
                        status.recording ->
                            "Recording ${status.activityLabel}: ${formatKm(status.distanceMeters)}"
                        else -> "Paused — waiting for movement"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun TracksTab(
    tracks: List<TrackSummary>,
    viewModel: TrackListViewModel,
    onOpen: (Long) -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { (label, dayTracks) ->
            item(key = "header:$label") {
                DayHeader(label) {
                    viewModel.shareTracks(dayTracks.map { it.id }) { intent ->
                        if (intent != null) context.startActivity(intent)
                    }
                }
            }
            items(dayTracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onOpen = { onOpen(track.id) },
                    onDeleteRequest = { pendingDelete = track },
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

@Composable
private fun DayHeader(label: String, onShare: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
    var startConfirmations by remember {
        mutableFloatStateOf(AppSettings.startConfirmations(context).toFloat())
    }
    // Confirmation needs the periodic poll for its 2nd+ reading; off, a start happens instantly.
    var pollEnabled by remember { mutableStateOf(AppSettings.activityPollEnabled(context)) }
    var pollIntervalSec by remember {
        mutableFloatStateOf(AppSettings.activityPollIntervalSec(context).toFloat())
    }

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
        SliderSetting("Min time between points", intervalSec, 1f..30f, 1, { "${it.toInt()} s" }) {
            intervalSec = it
            AppSettings.setMinIntervalSec(context, it.toInt())
        }
        SliderSetting("Min distance between points", distanceM, 1f..50f, 1, { "${it.toInt()} m" }) {
            distanceM = it
            AppSettings.setMinDistanceM(context, it.toInt())
        }

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
        SliderSetting("Min duration", minDurationSec, 0f..300f, 30, { durationSettingLabel(it.toInt()) }) {
            minDurationSec = it
            AppSettings.setMinTrackDurationSec(context, it.toInt())
        }
        SliderSetting("Min length", minLengthM, 0f..500f, 50, { lengthSettingLabel(it.toInt()) }) {
            minLengthM = it
            AppSettings.setMinTrackLengthM(context, it.toInt())
        }
        SliderSetting("Min extent", minExtentM, 0f..500f, 50, { lengthSettingLabel(it.toInt()) }) {
            minExtentM = it
            AppSettings.setMinTrackExtentM(context, it.toInt())
        }

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
        SliderSetting("Resume window", resumeWindowSec, 0f..600f, 60, { durationSettingLabel(it.toInt()) }) {
            resumeWindowSec = it
            AppSettings.setResumeWindowSec(context, it.toInt())
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader(
            "Accuracy filter",
            canReset = accuracyGateM.toInt() != AppSettings.DEFAULT_ACCURACY_GATE_M ||
                requireGnssFix != AppSettings.DEFAULT_REQUIRE_GNSS_FIX,
        ) {
            accuracyGateM = AppSettings.DEFAULT_ACCURACY_GATE_M.toFloat()
            requireGnssFix = AppSettings.DEFAULT_REQUIRE_GNSS_FIX
            AppSettings.setAccuracyGateM(context, AppSettings.DEFAULT_ACCURACY_GATE_M)
            AppSettings.setRequireGnssFix(context, AppSettings.DEFAULT_REQUIRE_GNSS_FIX)
        }
        Text(
            "Fixes less accurate than this are flagged noisy and left out of the track. Applies to " +
                "newly recorded tracks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SliderSetting("Max accuracy radius", accuracyGateM, 10f..150f, 10, { "${it.toInt()} m" }) {
            accuracyGateM = it
            AppSettings.setAccuracyGateM(context, it.toInt())
        }
        Text(
            "Also drop fixes with no live satellite lock — the position the phone reports from Wi-Fi/" +
                "cell in a tunnel, which can look accurate but wander. Applies to newly recorded tracks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Require satellite fix", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = requireGnssFix,
                onCheckedChange = {
                    requireGnssFix = it
                    AppSettings.setRequireGnssFix(context, it)
                },
            )
        }

        Spacer(Modifier.height(24.dp))
        // Activity polling and its start sensitivity are one feature: the poll re-reads activity on
        // a timer, and confirmations tune how many of those readings start a track. The slider is
        // inert without the poll, so they share a section and a single Reset.
        SectionHeader(
            "Activity polling",
            canReset = pollEnabled != AppSettings.DEFAULT_ACTIVITY_POLL_ENABLED ||
                pollIntervalSec.toInt() != AppSettings.DEFAULT_ACTIVITY_POLL_INTERVAL_SEC ||
                startConfirmations.toInt() != AppSettings.DEFAULT_START_CONFIRMATIONS,
        ) {
            pollEnabled = AppSettings.DEFAULT_ACTIVITY_POLL_ENABLED
            pollIntervalSec = AppSettings.DEFAULT_ACTIVITY_POLL_INTERVAL_SEC.toFloat()
            startConfirmations = AppSettings.DEFAULT_START_CONFIRMATIONS.toFloat()
            AppSettings.setActivityPollEnabled(context, AppSettings.DEFAULT_ACTIVITY_POLL_ENABLED)
            AppSettings.setActivityPollIntervalSec(context, AppSettings.DEFAULT_ACTIVITY_POLL_INTERVAL_SEC)
            AppSettings.setStartConfirmations(context, AppSettings.DEFAULT_START_CONFIRMATIONS)
        }
        Text(
            "Re-reads your activity while armed so recording starts and stops promptly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Poll activity", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = pollEnabled,
                onCheckedChange = {
                    pollEnabled = it
                    AppSettings.setActivityPollEnabled(context, it)
                },
            )
        }
        SliderSetting(
            "Poll interval", pollIntervalSec, 30f..180f, 30, { durationSettingLabel(it.toInt()) },
            enabled = pollEnabled,
        ) {
            pollIntervalSec = it
            AppSettings.setActivityPollIntervalSec(context, it.toInt())
        }
        Text(
            "How many readings in a row must agree you're moving before a new track starts. Higher " +
                "rejects false starts but delays it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        SliderSetting(
            "Confirmations to start", startConfirmations, 1f..4f, 1, { confirmationsLabel(it.toInt()) },
            // A confirming reading can only come from the poll; without it a track starts instantly.
            enabled = pollEnabled,
        ) {
            startConfirmations = it
            AppSettings.setStartConfirmations(context, it.toInt())
        }

        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(24.dp))
            Text("Debug", style = MaterialTheme.typography.titleMedium)
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
    Column(Modifier.padding(top = 16.dp).alpha(if (enabled) 1f else 0.38f)) {
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

private fun confirmationsLabel(n: Int): String = if (n <= 1) "Instant" else "$n readings"

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

@Composable
private fun TrackRow(
    track: TrackSummary,
    onOpen: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    // Swipe right-to-left to request deletion: when the gesture commits we ask for confirmation
    // and reset the row back to settled (the actual delete happens via the dialog).
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
            onDeleteRequest()
            dismissState.reset()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
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
        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
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
    val minutes = ((end - startedAt) / 60000.0).roundToLong()
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
    Scaffold(
        topBar = {
            // Header lives in the top-bar chrome so the (interop) map view is inset below it
            // and can't composite over it.
            Column {
                TopAppBar(
                    title = { Text(summary?.let { ActivityType.labelFor(it.activityType) } ?: "Track") },
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
                if (summary != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 3.dp,
                        shadowElevation = 3.dp,
                    ) {
                        TrackStatsHeader(summary)
                    }
                }
                ColorModeSelector(colorMode) { colorMode = it }
            }
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
                else -> MapLibreTrackMap(
                    points = loaded,
                    noisyPoints = if (showNoisy) noisyPoints else emptyList(),
                    activity = activity,
                    colorMode = colorMode,
                    showLegend = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (showNoisy && loaded != null && loaded.size >= 2) {
                // Top-right, clear of the colour-metric legend (bottom-right) and attribution.
                NoisyLegend(noisyPoints, Modifier.align(Alignment.TopEnd).padding(12.dp))
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(dateFormat.format(Date(summary.startedAt)), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatItem("Distance", formatKm(summary.distanceMeters))
            StatItem("Duration", formatDuration(summary.startedAt, summary.endedAt))
            StatItem("Avg speed", if (avgKmh > 0) "%.0f km/h".format(avgKmh) else "—")
        }
        if (summary.ignoredCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                noisyFixesLabel(summary.ignoredCount, " from this track"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
