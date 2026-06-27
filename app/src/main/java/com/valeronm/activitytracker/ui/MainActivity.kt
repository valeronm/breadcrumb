package com.valeronm.activitytracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.valeronm.activitytracker.R
import com.valeronm.activitytracker.data.Settings as AppSettings
import com.valeronm.activitytracker.data.db.TrackPoint
import com.valeronm.activitytracker.data.db.TrackSummary
import com.valeronm.activitytracker.location.LocationRecordingService
import com.valeronm.activitytracker.location.TrackingStatus
import com.valeronm.activitytracker.ui.theme.AppTheme
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

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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

private enum class HomeTab { RECORD, TRACKS, SETTINGS }

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
    var selectedTrackId by remember { mutableStateOf<Long?>(null) }
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

    val requestForeground = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        foregroundOk = context.foregroundGranted()
    }
    val requestBackground = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        backgroundOk = context.backgroundGranted()
    }
    val exportAllLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
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
    var renderedTrackId by remember { mutableStateOf<Long?>(null) }
    val presence = remember { Animatable(0f) }      // 0 = tabs shown, 1 = detail fully shown
    val backProgress = remember { Animatable(0f) }  // predictive back gesture progress, 0..1
    var backEdgeSign by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(selectedTrackId) {
        val selected = selectedTrackId
        if (selected != null) {
            renderedTrackId = selected
            backProgress.snapTo(0f)
            presence.animateTo(1f, tween(300))
        } else if (renderedTrackId != null) {
            presence.animateTo(0f, tween(300))
            renderedTrackId = null
            backProgress.snapTo(0f)
        }
    }

    PredictiveBackHandler(enabled = selectedTrackId != null) { events ->
        try {
            events.collect { event ->
                backEdgeSign = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                backProgress.snapTo(event.progress)
            }
            selectedTrackId = null // gesture committed -> dismiss
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
                                HomeTab.RECORD -> "Activity GPS Tracker"
                                HomeTab.TRACKS -> "Recorded tracks"
                                HomeTab.SETTINGS -> "Settings"
                            },
                        )
                    },
                    actions = {
                        if (selectedTab == HomeTab.TRACKS && tracks.isNotEmpty()) {
                            IconButton(onClick = { exportAllLauncher.launch(null) }) {
                                Icon(Icons.Filled.Download, contentDescription = "Export all as GPX")
                            }
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
                    NavigationBarItem(
                        selected = selectedTab == HomeTab.SETTINGS,
                        onClick = { selectedTab = HomeTab.SETTINGS },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
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
                        onOpen = { selectedTrackId = it },
                    )

                    HomeTab.SETTINGS -> SettingsTab()
                }
            }
        }

        // Detail overlay: animates in on open and scales/shifts with the predictive-back gesture.
        val rendered = renderedTrackId
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
                TrackMapScreen(
                    trackId = rendered,
                    summary = tracks.find { it.id == rendered },
                    viewModel = viewModel,
                    onBack = { selectedTrackId = null },
                )
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
                    "%.2f km · %d points".format(status.distanceMeters / 1000.0, status.points),
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
                        status.recording -> "Recording ${status.activityLabel}: %.2f km · %d points"
                            .format(status.distanceMeters / 1000.0, status.points)
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
            item(key = "header:$label") { DayHeader(label) }
            items(dayTracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onOpen = { onOpen(track.id) },
                    onShare = {
                        viewModel.share(track.id) { intent ->
                            if (intent != null) context.startActivity(intent)
                        }
                    },
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
                    "The ${activityLabel(track.activityType)} track from " +
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
private fun DayHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsTab() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Coming soon — server sync (Dawarich / OwnTracks), Wi-Fi-only uploads, " +
                "GPS cadence, and more.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
    onShare: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    // Swipe right-to-left to request deletion; we reject the dismiss so the row springs back and
    // a confirmation dialog handles the actual delete.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDeleteRequest()
            false
        },
    )
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
                        activityLabel(track.activityType),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        timeFormat.format(Date(track.startedAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "%.2f km · %d pts · %s".format(
                            track.distanceMeters / 1000.0,
                            track.pointCount,
                            formatDuration(track.startedAt, track.endedAt),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Export GPX")
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
    val points by produceState<List<TrackPoint>?>(initialValue = null, trackId) {
        value = viewModel.getPoints(trackId)
    }
    Scaffold(
        topBar = {
            // Header lives in the top-bar chrome so the (interop) map view is inset below it
            // and can't composite over it.
            Column {
                TopAppBar(
                    title = { Text(summary?.let { activityLabel(it.activityType) } ?: "Track") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                else -> TrackMap(points = loaded)
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
            StatItem("Distance", "%.2f km".format(summary.distanceMeters / 1000.0))
            StatItem("Duration", formatDuration(summary.startedAt, summary.endedAt))
            StatItem("Avg speed", if (avgKmh > 0) "%.0f km/h".format(avgKmh) else "—")
            StatItem("Points", summary.pointCount.toString())
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

private fun activityLabel(activityType: String): String =
    activityType.lowercase(Locale.US).replaceFirstChar { it.uppercase() }

private fun activityIcon(activityType: String): ImageVector = when (activityType.uppercase(Locale.US)) {
    "WALKING" -> Icons.AutoMirrored.Filled.DirectionsWalk
    "RUNNING" -> Icons.AutoMirrored.Filled.DirectionsRun
    "CYCLING" -> Icons.AutoMirrored.Filled.DirectionsBike
    "DRIVING" -> Icons.Filled.DirectionsCar
    else -> Icons.Filled.Place
}

@Composable
private fun TrackMap(points: List<TrackPoint>) {
    val geoPoints = remember(points) { points.map { GeoPoint(it.latitude, it.longitude) } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // OSM tile usage policy requires a unique user agent.
            Configuration.getInstance().userAgentValue = ctx.packageName
            EdgeAwareMapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
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
            map.overlays.add(endpointMarker(map, geoPoints.first(), "Start", R.drawable.ic_marker_start))
            map.overlays.add(endpointMarker(map, geoPoints.last(), "End", R.drawable.ic_marker_end))
            // Frame the whole track once the view has been laid out.
            val bounds = BoundingBox.fromGeoPointsSafe(geoPoints)
            map.post { map.zoomToBoundingBox(bounds, false, 80) }
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
