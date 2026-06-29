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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.Settings as AppSettings
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.location.LocationRecordingService
import io.github.valeronm.breadcrumb.location.TrackingStatus
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.formatKm
import io.github.valeronm.breadcrumb.util.isGranted
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import kotlinx.coroutines.CancellationException
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(220.dp).clipToBounds()) {
                if (points.size >= 2) {
                    TrackMap(points = points)
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
                    "${formatKm(status.distanceMeters)} · ${status.points} points",
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
                            "Recording ${status.activityLabel}: ${formatKm(status.distanceMeters)} · ${status.points} points"
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
        SectionHeader("Sampling") {
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
        SectionHeader("Keep a track only if") {
            minDurationSec = AppSettings.DEFAULT_TRACK_MIN_DURATION_SEC.toFloat()
            minLengthM = AppSettings.DEFAULT_TRACK_MIN_LENGTH_M.toFloat()
            AppSettings.setMinTrackDurationSec(context, AppSettings.DEFAULT_TRACK_MIN_DURATION_SEC)
            AppSettings.setMinTrackLengthM(context, AppSettings.DEFAULT_TRACK_MIN_LENGTH_M)
        }
        Text(
            "Shorter tracks are discarded when recording stops.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SliderSetting("Min duration", minDurationSec, 0f..300f, 15, { durationSettingLabel(it.toInt()) }) {
            minDurationSec = it
            AppSettings.setMinTrackDurationSec(context, it.toInt())
        }
        SliderSetting("Min length", minLengthM, 0f..1000f, 50, { lengthSettingLabel(it.toInt()) }) {
            minLengthM = it
            AppSettings.setMinTrackLengthM(context, it.toInt())
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

@Composable
private fun SectionHeader(title: String, onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onReset) { Text("Reset") }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Int,
    valueText: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 16.dp)) {
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
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = activityIcon(track.activityType),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ActivityType.labelFor(track.activityType),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        timeFormat.format(Date(track.startedAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${formatKm(track.distanceMeters)} · ${track.pointCount} pts · " +
                            formatDuration(track.startedAt, track.endedAt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (track.ignoredCount > 0) {
                        Text(
                            "⚠ ${track.ignoredCount} noisy ${if (track.ignoredCount == 1) "fix" else "fixes"} excluded",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(startedAt: Long, endedAt: Long?): String {
    val end = endedAt ?: return "recording"
    val seconds = ((end - startedAt) / 1000.0).roundToLong()
    val m = seconds / 60
    val s = seconds % 60
    return if (m >= 60) "%dh %02dm".format(m / 60, m % 60) else "%dm %02ds".format(m, s)
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
                else -> TrackMap(points = loaded, noisyPoints = noisyPoints)
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
            StatItem("Points", summary.pointCount.toString())
        }
        if (summary.ignoredCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "⚠ ${summary.ignoredCount} noisy ${if (summary.ignoredCount == 1) "fix" else "fixes"} excluded from this track",
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

private fun activityIcon(activityType: String): ImageVector = when (activityType.uppercase(Locale.US)) {
    "WALKING" -> Icons.AutoMirrored.Filled.DirectionsWalk
    "RUNNING" -> Icons.AutoMirrored.Filled.DirectionsRun
    "CYCLING" -> Icons.AutoMirrored.Filled.DirectionsBike
    "DRIVING" -> Icons.Filled.DirectionsCar
    else -> Icons.Filled.Place
}

@Composable
private fun TrackMap(points: List<TrackPoint>, noisyPoints: List<TrackPoint> = emptyList()) {
    val geoPoints = remember(points) { points.map { GeoPoint(it.latitude, it.longitude) } }
    val noisyGeoPoints = remember(noisyPoints) { noisyPoints.map { GeoPoint(it.latitude, it.longitude) } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // OSM tile usage policy requires a unique user agent.
            Configuration.getInstance().userAgentValue = ctx.packageName
            EdgeAwareMapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                // Start zoomed in so the first frame never shows the default world view.
                controller.setZoom(15.0)
                onResume()
            }
        },
        update = { map ->
            map.overlays.clear()
            val line = Polyline(map).apply {
                setPoints(geoPoints)
                outlinePaint.color = android.graphics.Color.parseColor("#2962FF")
                outlinePaint.strokeWidth = 12f
            }
            map.overlays.add(line)
            // Mark excluded "bad fix" points so the gaps in the line have an explanation. They're
            // not in the framing bounds below, so a far outlier won't shrink the actual route.
            for (noisy in noisyGeoPoints) {
                map.overlays.add(endpointMarker(map, noisy, "Noisy fix", R.drawable.ic_marker_noisy))
            }
            map.overlays.add(endpointMarker(map, geoPoints.first(), "Start", R.drawable.ic_marker_start))
            map.overlays.add(endpointMarker(map, geoPoints.last(), "End", R.drawable.ic_marker_end))
            val bounds = BoundingBox.fromGeoPointsSafe(geoPoints)
            // Center synchronously so the first drawn frame is already on the track (no flash),
            // then refine to fit the whole track.
            map.controller.setCenter(GeoPoint(bounds.centerLatitude, bounds.centerLongitude))
            // zoomToBoundingBox must run only once the MapView has real dimensions: called while the
            // view is still 0×0 its projection is degenerate and Projection.getCloserPixel spins
            // forever wrapping the longitude, pegging the main thread into an ANR. post{} doesn't
            // wait for layout (it just queues a message) — addOnFirstLayoutListener does. A single
            // point has no span to fit, so just pick a sensible zoom for it.
            val frame = {
                if (geoPoints.size > 1) map.zoomToBoundingBox(bounds, false, 80)
                else map.controller.setZoom(16.0)
            }
            if (map.width > 0 && map.height > 0) frame()
            else map.addOnFirstLayoutListener { _, _, _, _, _ -> frame() }
            map.invalidate()
        },
        onRelease = { it.onDetach() },
    )
}

private fun endpointMarker(map: MapView, at: GeoPoint, label: String, iconRes: Int): Marker =
    Marker(map).apply {
        position = at
        title = label
        icon = ContextCompat.getDrawable(map.context, iconRes)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setInfoWindow(null)
        setOnMarkerClickListener { _, _ -> true }
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
